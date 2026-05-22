package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.sandbox.TenantFileSandbox;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * FIXED: Tenant-aware tool dispatcher that routes all tool calls through tenant sandbox.
 */
public class TenantAwareToolDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareToolDispatcher.class);
    
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
                case "file_read", "file_write", "file_list" -> dispatchFileTool(toolName, args);
                case "execute_python", "execute_javascript" -> dispatchCodeTool(toolName, args);
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
        String pathStr = (String) args.get("path");
        TenantFileSandbox sandbox = tenantContext.getFileSandbox();
        
        try {
            switch (toolName) {
                case "file_read": {
                    var validation = sandbox.validatePath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    if (!Files.exists(path)) {
                        return ToolRegistry.toolError("File not found: " + pathStr);
                    }
                    String content = Files.readString(path);
                    return ToolRegistry.toolResult(Map.of("content", content));
                }
                case "file_write": {
                    var validation = sandbox.validatePath(pathStr, TenantFileSandbox.AccessMode.WRITE);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    String content = (String) args.get("content");
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, content);
                    return ToolRegistry.toolResult(Map.of("status", "written"));
                }
                case "file_list": {
                    var validation = sandbox.validatePath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    var files = Files.list(path).map(p -> p.getFileName().toString()).toList();
                    return ToolRegistry.toolResult(Map.of("files", files));
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
        String language = "python".equals(toolName) ? "python" : "javascript";
        
        try {
            ProcessBuilder pb = new ProcessBuilder(getInterpreter(language), "-c", code);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int timeout = 60;
            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                return ToolRegistry.toolError("Code execution timed out");
            }
            
            String output = new String(process.getInputStream().readAllBytes());
            return ToolRegistry.toolResult(Map.of("stdout", output, "exit_code", process.exitValue()));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Code execution failed: " + e.getMessage());
        }
    }
    
    private String dispatchTerminalTool(String toolName, Map<String, Object> args) {
        String command = (String) args.get("command");
        
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int timeout = 300;
            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                return ToolRegistry.toolError("Command timed out");
            }
            
            String output = new String(process.getInputStream().readAllBytes());
            return ToolRegistry.toolResult(Map.of("stdout", output, "exit_code", process.exitValue()));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Command execution failed: " + e.getMessage());
        }
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
    
    private String getInterpreter(String language) {
        return "python".equals(language) ? "python3" : "node";
    }
}
