package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
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
import java.util.Optional;

/**
 * FIXED: Tenant-aware tool dispatcher that routes all tool calls through tenant sandbox.
 */
import com.nousresearch.hermes.approval.ApprovalMessageHandler;
import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.collaboration.Negotiator;

public class TenantAwareToolDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareToolDispatcher.class);
    private static final int MAX_SEARCH_RESULTS = 100;
    
    private final TenantContext tenantContext;
    private final ToolRegistry globalRegistry;
    
    // Per-tenant approval system (NOT shared across tenants)
    private ApprovalSystem approvalSystem;
    private ApprovalMessageHandler approvalMessageHandler;
    
    // AI原生组织：结构化协商引擎
    private Negotiator negotiator;
    
    public TenantAwareToolDispatcher(TenantContext tenantContext, ToolRegistry globalRegistry) {
        this.tenantContext = tenantContext;
        this.globalRegistry = globalRegistry;
    }
    
    public void setApprovalSystem(ApprovalSystem approvalSystem) {
        this.approvalSystem = approvalSystem;
    }
    
    public void setApprovalMessageHandler(ApprovalMessageHandler handler) {
        this.approvalMessageHandler = handler;
    }
    
    public void setNegotiator(Negotiator negotiator) {
        this.negotiator = negotiator;
    }
    
    public String dispatch(String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Map.of();
        logger.debug("Dispatching tool: {} for tenant: {}", toolName, tenantContext.getTenantId());

        // --- Plugin hook: pre_tool_call ---
        HookEngine hookEngine = getHookEngine();
        if (hookEngine != null) {
            Optional<String> blocked = hookEngine.checkToolBlocked(
                    toolName, safeArgs,
                    tenantContext.getTenantId(),  // task_id (reused as tenant_id)
                    tenantContext.getTenantId(),  // session_id (reused as tenant_id)
                    ""  // tool_call_id not available at this layer
            );
            if (blocked.isPresent()) {
                logger.info("Tool '{}' blocked by plugin hook: {}", toolName, blocked.get());
                String result = ToolRegistry.toolError("Blocked by policy: " + blocked.get());
                tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, result);
                return result;
            }
        }

        var permission = tenantContext.getToolRegistry().checkPermission(toolName, safeArgs);
        if (!permission.isAllowed()) {
            return ToolRegistry.toolError(permission.getReason());
        }

        // Compute tool entry once for both approval and negotiation
        var optEntry = globalRegistry.getAllTools().stream()
            .filter(t -> t.getName().equals(toolName))
            .findFirst();

        // Per-tenant approval check BEFORE execution
        if (approvalSystem != null) {
            if (optEntry.isPresent() && optEntry.get().requiresApproval()) {
                var entry = optEntry.get();
                String operation = entry.getApprovalMessageTemplate();
                if (operation == null || operation.isEmpty()) {
                    operation = toolName + " with args: " + safeArgs;
                } else {
                    for (var ae : safeArgs.entrySet()) {
                        String val = String.valueOf(ae.getValue());
                        if (val.length() > 100) val = val.substring(0, 97) + "...";
                        operation = operation.replace("{" + ae.getKey() + "}", val);
                    }
                }
                String details = "Tenant: " + tenantContext.getTenantId()
                    + ", Tool: " + toolName + " (risk: " + entry.getRisk() + ")";
                
                approvalSystem.setMode(entry.getApprovalType(), entry.getRisk().toDefaultMode());
                
                if (approvalMessageHandler != null) {
                    final String op = operation;
                    approvalSystem.setExternalApprover(request ->
                        approvalMessageHandler.registerRequest(tenantContext.getTenantId(),
                            (com.nousresearch.hermes.approval.ApprovalRequest) request));
                }
                
                ApprovalResult approval = approvalSystem.requestApproval(
                    entry.getApprovalType(), operation, details);
                
                if (!approval.isApproved()) {
                    String denied = ToolRegistry.toolError("Approval denied: " + approval.getReason());
                    tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, denied);
                    return denied;
                }
            }
        }

        // AI原生组织：高风险工具自动协商
        if (negotiator != null && optEntry.isPresent()) {
            var entry = optEntry.get();
            if (entry.getRisk().ordinal() >= com.nousresearch.hermes.approval.ToolRisk.MEDIUM.ordinal()) {
                double confidence = entry.getRisk() == com.nousresearch.hermes.approval.ToolRisk.HIGH ? 0.5 : 0.8;
                var negResult = negotiator.autoNegotiate(
                    tenantContext.getTenantId(), toolName, 
                    toolName + " with args: " + safeArgs.keySet(), confidence);
                if (negResult.needsHuman()) {
                    String denied = ToolRegistry.toolError("Escalated for review: " + negResult.detail);
                    tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, denied);
                    return denied;
                }
            }
        }

        String result;
        try {
            result = switch (toolName) {
                case "read_file", "write_file", "list_directory", "search_files",
                     "file_read", "file_write", "file_list" -> dispatchFileTool(toolName, safeArgs);
                case "execute_python", "execute_javascript", "execute_bash" -> dispatchCodeTool(toolName, safeArgs);
                case "terminal", "execute_command" -> dispatchTerminalTool(toolName, safeArgs);
                case "memory_read", "memory_write", "memory_search" -> dispatchMemoryTool(toolName, safeArgs);
                default -> dispatchGenericTool(toolName, safeArgs);
            };
        } catch (Exception e) {
            logger.error("Error dispatching tool: {}", toolName, e);
            result = ToolRegistry.toolError("Tool execution failed: " + e.getMessage());
        }

        // --- Plugin hook: post_tool_call ---
        if (hookEngine != null) {
            Map<String, Object> postCtx = new HashMap<>();
            postCtx.put("tool_name", toolName);
            postCtx.put("args", safeArgs);
            postCtx.put("result", result);
            postCtx.put("task_id", tenantContext.getTenantId());
            postCtx.put("session_id", tenantContext.getTenantId());
            hookEngine.invoke(HookType.POST_TOOL_CALL, postCtx);
        }

        // --- Plugin hook: transform_tool_result ---
        if (hookEngine != null) {
            Map<String, Object> transformCtx = new HashMap<>();
            transformCtx.put("tool_name", toolName);
            transformCtx.put("args", safeArgs);
            transformCtx.put("result", result);
            transformCtx.put("task_id", tenantContext.getTenantId());
            transformCtx.put("session_id", tenantContext.getTenantId());
            List<Object> transforms = hookEngine.invoke(HookType.TRANSFORM_TOOL_RESULT, transformCtx);
            for (Object t : transforms) {
                if (t instanceof String s && !s.isEmpty()) {
                    result = s;
                    logger.debug("Tool result transformed by plugin for '{}'", toolName);
                }
            }
        }

        tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, result);
        return result;
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

    private HookEngine getHookEngine() {
        PluginManager pm = PluginManager.getInstance();
        return pm != null ? pm.getHookEngineFacade() : null;
    }
}
