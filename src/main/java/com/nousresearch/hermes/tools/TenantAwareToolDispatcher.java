package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.sandbox.ProcessOptions;
import com.nousresearch.hermes.tenant.sandbox.ProcessResult;
import com.nousresearch.hermes.tenant.sandbox.TenantFileSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FIXED: Tenant-aware tool dispatcher that routes all tool calls through tenant sandbox.
 */
public class TenantAwareToolDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareToolDispatcher.class);
    private static final int MAX_SEARCH_RESULTS = 100;
    
    private final TenantContext tenantContext;
    private final ToolRegistry globalRegistry;
    
    public TenantAwareToolDispatcher(TenantContext tenantContext, ToolRegistry globalRegistry) {
        this.tenantContext = tenantContext;
        this.globalRegistry = globalRegistry;
    }
    
    public String dispatch(String toolName, Map<String, Object> args) {
        logger.debug("Dispatching tool: {} for tenant: {}", toolName, tenantContext.getTenantId());
        
        try {
            String result = switch (toolName) {
                case "read_file", "write_file", "list_directory", "search_files",
                     "file_read", "file_write", "file_list" -> dispatchFileTool(toolName, args);
                case "execute_python", "execute_javascript", "execute_bash" -> dispatchCodeTool(toolName, args);
                case "terminal", "execute_command" -> dispatchTerminalTool(toolName, args);
                case "memory_read", "memory_write", "memory_search" -> dispatchMemoryTool(toolName, args);
                default -> dispatchGenericTool(toolName, args);
            };
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error dispatching tool: {}", toolName, e);
            return ToolRegistry.toolError("Tool execution failed: " + e.getMessage());
        }
    }
    
    private String dispatchFileTool(String toolName, Map<String, Object> args) {
        TenantFileSandbox sandbox = tenantContext.getFileSandbox();
        
        try {
            switch (toolName) {
                case "read_file", "file_read": {
                    String pathStr = (String) args.get("path");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    int offset = ((Number) args.getOrDefault("offset", 1)).intValue();
                    int limit = ((Number) args.getOrDefault("limit", 1000)).intValue();
                    List<String> lines = content.isEmpty() ? List.of() : content.lines().toList();
                    int start = Math.max(0, offset - 1);
                    int end = Math.min(lines.size(), start + limit);
                    String returned = start < end ? String.join("\n", lines.subList(start, end)) : "";
                    return ToolRegistry.toolResult(Map.of(
                        "path", sandbox.getSandboxRoot().relativize(path).toString(),
                        "content", returned,
                        "total_lines", lines.size(),
                        "returned_lines", Math.max(0, end - start),
                        "offset", start + 1,
                        "truncated", end < lines.size()
                    ));
                }
                case "write_file", "file_write": {
                    String pathStr = (String) args.get("path");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.WRITE);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    String content = (String) args.getOrDefault("content", "");
                    boolean append = Boolean.TRUE.equals(args.get("append"));
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    if (append) {
                        Files.writeString(path, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.writeString(path, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                    return ToolRegistry.toolResult(Map.of(
                        "path", sandbox.getSandboxRoot().relativize(path).toString(),
                        "bytes_written", content.getBytes(StandardCharsets.UTF_8).length,
                        "append", append,
                        "success", true,
                        "status", append ? "appended" : "written"
                    ));
                }
                case "list_directory", "file_list": {
                    String pathStr = (String) args.getOrDefault("path", ".");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    if (!Files.isDirectory(path)) {
                        return ToolRegistry.toolError("Not a directory: " + pathStr);
                    }
                    List<Map<String, Object>> entries = new ArrayList<>();
                    try (var stream = Files.list(path)) {
                        stream.forEach(p -> {
                            try {
                                Map<String, Object> entry = new HashMap<>();
                                entry.put("name", p.getFileName().toString());
                                entry.put("path", sandbox.getSandboxRoot().relativize(p).toString());
                                entry.put("is_directory", Files.isDirectory(p));
                                entry.put("is_file", Files.isRegularFile(p));
                                entry.put("size", Files.size(p));
                                entry.put("last_modified", Files.getLastModifiedTime(p).toString());
                                entries.add(entry);
                            } catch (IOException e) {
                                logger.debug("Could not read directory entry: {}", p, e);
                            }
                        });
                    }
                    return ToolRegistry.toolResult(Map.of(
                        "path", sandbox.getSandboxRoot().relativize(path).toString(),
                        "entries", entries,
                        "files", entries.stream().map(e -> e.get("name")).toList(),
                        "count", entries.size()
                    ));
                }
                case "search_files": {
                    String pattern = (String) args.get("pattern");
                    if (pattern == null || pattern.isBlank()) {
                        return ToolRegistry.toolError("Search pattern is required");
                    }
                    String pathStr = (String) args.getOrDefault("path", ".");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path root = validation.path();
                    List<String> results = new ArrayList<>();
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                                    .matches(Paths.get(file.getFileName().toString()))) {
                                results.add(sandbox.getSandboxRoot().relativize(file).toString());
                                if (results.size() >= MAX_SEARCH_RESULTS) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    return ToolRegistry.toolResult(Map.of(
                        "pattern", pattern,
                        "path", sandbox.getSandboxRoot().relativize(root).toString(),
                        "results", results,
                        "count", results.size(),
                        "truncated", results.size() >= MAX_SEARCH_RESULTS
                    ));
                }
                default:
                    return ToolRegistry.toolError("Unknown file tool: " + toolName);
            }
        } catch (IOException e) {
            return ToolRegistry.toolError("File operation failed: " + e.getMessage());
        }
    }
    
    private String dispatchCodeTool(String toolName, Map<String, Object> args) {
        String code = (String) args.get("code");
        if (code == null) {
            return ToolRegistry.toolError("Code is required");
        }

        String command = switch (toolName) {
            case "execute_python" -> "python3";
            case "execute_javascript" -> "node";
            case "execute_bash" -> "/bin/bash";
            default -> throw new IllegalArgumentException("Unknown code tool: " + toolName);
        };
        List<String> execCommand = "execute_bash".equals(toolName)
            ? List.of(command, "-c", code)
            : List.of(command, "-c", code);

        int timeout = ((Number) args.getOrDefault("timeout", 60)).intValue();
        long memoryLimitMb = ((Number) args.getOrDefault("memory_limit_mb", 512)).longValue();
        return executeInTenantSandbox(execCommand, timeout, memoryLimitMb);
    }
    
    private String dispatchTerminalTool(String toolName, Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return ToolRegistry.toolError("Command is required");
        }

        String cwd = (String) args.get("cwd");
        int timeout = ((Number) args.getOrDefault("timeout", 300)).intValue();
        var validation = validateTenantPath(cwd == null || cwd.isBlank() ? "." : cwd,
            TenantFileSandbox.AccessMode.READ);
        if (!validation.isAllowed()) {
            return ToolRegistry.toolError("Access denied: " + validation.getReason());
        }

        return executeInTenantSandbox(List.of("/bin/bash", "-c", command), timeout, 512,
            validation.path());
    }
    
    private String dispatchMemoryTool(String toolName, Map<String, Object> args) {
        var memoryManager = tenantContext.getMemoryManager();
        
        return switch (toolName) {
            case "memory_read" -> {
                int limit = ((Number) args.getOrDefault("limit", 10)).intValue();
                var memories = memoryManager.search((String) args.get("query"), limit);
                yield ToolRegistry.toolResult(Map.of("memories", memories));
            }
            case "memory_write" -> {
                boolean added = memoryManager.addMemory((String) args.get("content"));
                yield ToolRegistry.toolResult(Map.of("status", added ? "saved" : "failed"));
            }
            case "memory_search" -> {
                int limit = ((Number) args.getOrDefault("limit", 10)).intValue();
                var results = memoryManager.search((String) args.get("query"), limit);
                yield ToolRegistry.toolResult(Map.of("results", results));
            }
            default -> ToolRegistry.toolError("Unknown memory tool: " + toolName);
        };
    }
    
    private String dispatchGenericTool(String toolName, Map<String, Object> args) {
        var entry = globalRegistry.getAllTools().stream()
            .filter(t -> t.getName().equals(toolName))
            .findFirst()
            .orElse(null);
        
        if (entry == null) {
            return ToolRegistry.toolError("Unknown tool: " + toolName);
        }
        
        return entry.getHandler().apply(args);
    }

    private TenantFileSandbox.PathValidationResult validateTenantPath(String pathStr, TenantFileSandbox.AccessMode mode) {
        TenantFileSandbox sandbox = tenantContext.getFileSandbox();
        if (pathStr == null || pathStr.isBlank()) {
            pathStr = ".";
        }

        Path sandboxRoot = sandbox.getSandboxRoot().toAbsolutePath().normalize();
        Path requested = Paths.get(pathStr);
        Path candidate;

        if (requested.isAbsolute()) {
            candidate = requested.toAbsolutePath().normalize();
            if (!candidate.startsWith(sandboxRoot)) {
                return TenantFileSandbox.PathValidationResult.rejected(
                    "absolute path outside tenant sandbox", candidate);
            }
        } else {
            candidate = sandboxRoot.resolve(requested).normalize();
            if (!candidate.startsWith(sandboxRoot)) {
                return TenantFileSandbox.PathValidationResult.rejected(
                    "path traversal outside tenant sandbox", candidate);
            }
        }

        return sandbox.validatePath(candidate.toString(), mode);
    }

    private String executeInTenantSandbox(List<String> command, int timeout, long memoryLimitMb) {
        return executeInTenantSandbox(command, timeout, memoryLimitMb,
            tenantContext.getFileSandbox().getSandboxRoot());
    }

    private String executeInTenantSandbox(List<String> command, int timeout, long memoryLimitMb, Path workDirectory) {
        try {
            ProcessOptions options = ProcessOptions.builder()
                .timeoutSeconds(timeout)
                .maxMemoryMB(memoryLimitMb)
                .workDirectory(workDirectory)
                .redirectErrorStream(false)
                .build();
            ProcessResult result = tenantContext.exec(command, options);

            Map<String, Object> output = new HashMap<>();
            output.put("stdout", result.getStdout() == null ? "" : result.getStdout());
            output.put("stderr", result.getStderr() == null ? "" : result.getStderr());
            output.put("exit_code", result.getExitCode());
            output.put("success", result.isSuccess());
            output.put("timed_out", result.isTimedOut());
            output.put("tenant_id", tenantContext.getTenantId());
            if (result.getError() != null) {
                output.put("error", result.getError());
            }
            return result.isSuccess() ? ToolRegistry.toolResult(output) : ToolRegistry.toolError(
                result.getError() != null ? result.getError() : "Process exited with code " + result.getExitCode(),
                output);
        } catch (Exception e) {
            return ToolRegistry.toolError("Process sandbox execution failed: " + e.getMessage());
        }
    }
}
