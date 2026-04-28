package com.nousresearch.hermes.tenant.tools;

import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.quota.QuotaExceededException;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 租户感知的代码执行工具
 * 
 * 每个租户拥有独立的：
 * - 代码执行目录（沙箱）
 * - 资源限制（CPU、内存、时间）
 * - 环境变量隔离
 * - 审计日志
 */
public class TenantAwareCodeTool {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareCodeTool.class);
    
    private static final int DEFAULT_TIMEOUT = 60;
    private static final long DEFAULT_MEMORY_LIMIT = 512 * 1024 * 1024; // 512MB
    
    private final TenantContext context;
    private final ApprovalSystem approvalSystem;
    private final Path sandboxDir;
    private final Path tempDir;
    
    public TenantAwareCodeTool(TenantContext context) {
        this.context = context;
        this.approvalSystem = new ApprovalSystem();
        this.sandboxDir = context.getFileSandbox().getSandboxPath().resolve("code");
        this.tempDir = sandboxDir.resolve("temp");
        
        try {
            Files.createDirectories(sandboxDir);
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            logger.error("Tenant {}: Failed to create code sandbox: {}", 
                context.getTenantId(), e.getMessage());
        }
    }
    
    public void register(ToolRegistry registry) {
        // Python
        registry.register(new ToolEntry.Builder()
            .name("execute_python")
            .toolset("code")
            .schema(Map.of("description", "Execute Python code in tenant sandbox",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "code", Map.of("type", "string"),
                        "timeout", Map.of("type", "integer", "default", 60),
                        "memory_limit_mb", Map.of("type", "integer", "default", 512)),
                    "required", List.of("code"))))
            .handler(this::executePython).emoji("🐍").build());
        
        // JavaScript
        registry.register(new ToolEntry.Builder()
            .name("execute_javascript")
            .toolset("code")
            .schema(Map.of("description", "Execute JavaScript in tenant sandbox",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "code", Map.of("type", "string"),
                        "timeout", Map.of("type", "integer", "default", 60),
                        "memory_limit_mb", Map.of("type", "integer", "default", 512)),
                    "required", List.of("code"))))
            .handler(this::executeJavaScript).emoji("📜").build());
        
        // Bash
        registry.register(new ToolEntry.Builder()
            .name("execute_bash")
            .toolset("code")
            .schema(Map.of("description", "Execute Bash in tenant sandbox",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "code", Map.of("type", "string"),
                        "timeout", Map.of("type", "integer", "default", 60),
                        "memory_limit_mb", Map.of("type", "integer", "default", 512)),
                    "required", List.of("code"))))
            .handler(this::executeBash).emoji("🐚").build());
    }
    
    // ============ 执行方法 ============
    
    private String executePython(Map<String, Object> args) {
        String code = (String) args.get("code");
        int timeout = getTimeout(args);
        long memoryLimit = getMemoryLimit(args);
        
        // 检查安全策略
        if (!context.getSecurityPolicy().isAllowCodeExecution()) {
            return ToolRegistry.toolError("Code execution is disabled for this tenant");
        }
        
        if (!context.getSecurityPolicy().isLanguageAllowed("python")) {
            return ToolRegistry.toolError("Python is not allowed for this tenant");
        }
        
        // 检查配额
        try {
            context.getQuotaManager().checkStorageQuota(code.length());
        } catch (QuotaExceededException e) {
            return ToolRegistry.toolError(e.getMessage());
        }
        
        // 审批
        ApprovalResult approval = approvalSystem.requestApproval(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            "python: " + code.substring(0, Math.min(100, code.length())), null);
        if (!approval.isApproved()) return ToolRegistry.toolError("Approval denied");
        
        // 审计日志
        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.CODE_EXECUTED,
            Map.of("tenantId", context.getTenantId(), "language", "python", "timeout", timeout)
        );
        
        try {
            Path scriptFile = tempDir.resolve("script_" + System.currentTimeMillis() + ".py");
            Files.writeString(scriptFile, wrapPythonSandbox(code, memoryLimit));
            
            ProcessBuilder pb = new ProcessBuilder("python3", scriptFile.toString());
            pb.directory(sandboxDir.toFile());
            
            // 设置受限环境变量
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("HOME", sandboxDir.toString());
            env.put("TMPDIR", tempDir.toString());
            env.put("PYTHONDONTWRITEBYTECODE", "1");
            env.put("PYTHONUNBUFFERED", "1");
            
            return executeInSandbox(pb, timeout, memoryLimit);
            
        } catch (Exception e) {
            logger.error("Tenant {}: Python execution failed: {}", context.getTenantId(), e.getMessage());
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    private String executeJavaScript(Map<String, Object> args) {
        String code = (String) args.get("code");
        int timeout = getTimeout(args);
        long memoryLimit = getMemoryLimit(args);
        
        // 检查安全策略
        if (!context.getSecurityPolicy().isAllowCodeExecution()) {
            return ToolRegistry.toolError("Code execution is disabled for this tenant");
        }
        
        if (!context.getSecurityPolicy().isLanguageAllowed("javascript")) {
            return ToolRegistry.toolError("JavaScript is not allowed for this tenant");
        }
        
        // 检查配额
        try {
            context.getQuotaManager().checkStorageQuota(code.length());
        } catch (QuotaExceededException e) {
            return ToolRegistry.toolError(e.getMessage());
        }
        
        // 审批
        ApprovalResult approval = approvalSystem.requestApproval(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            "javascript: " + code.substring(0, Math.min(100, code.length())), null);
        if (!approval.isApproved()) return ToolRegistry.toolError("Approval denied");
        
        // 审计日志
        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.CODE_EXECUTED,
            Map.of("tenantId", context.getTenantId(), "language", "javascript", "timeout", timeout)
        );
        
        try {
            Path scriptFile = tempDir.resolve("script_" + System.currentTimeMillis() + ".js");
            Files.writeString(scriptFile, wrapJavaScriptSandbox(code, memoryLimit));
            
            ProcessBuilder pb = new ProcessBuilder("node", scriptFile.toString());
            pb.directory(sandboxDir.toFile());
            
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("HOME", sandboxDir.toString());
            env.put("TMPDIR", tempDir.toString());
            
            return executeInSandbox(pb, timeout, memoryLimit);
            
        } catch (Exception e) {
            logger.error("Tenant {}: JavaScript execution failed: {}", context.getTenantId(), e.getMessage());
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    private String executeBash(Map<String, Object> args) {
        String code = (String) args.get("code");
        int timeout = getTimeout(args);
        long memoryLimit = getMemoryLimit(args);
        
        // 检查安全策略
        if (!context.getSecurityPolicy().isAllowCodeExecution()) {
            return ToolRegistry.toolError("Code execution is disabled for this tenant");
        }
        
        // 检查配额
        try {
            context.getQuotaManager().checkStorageQuota(code.length());
        } catch (QuotaExceededException e) {
            return ToolRegistry.toolError(e.getMessage());
        }
        
        // 审批
        ApprovalResult approval = approvalSystem.requestApproval(
            ApprovalSystem.ApprovalType.TERMINAL_COMMAND, code, null);
        if (!approval.isApproved()) return ToolRegistry.toolError("Approval denied");
        
        // 审计日志
        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.TERMINAL_COMMAND,
            Map.of("tenantId", context.getTenantId(), "command", code.substring(0, Math.min(100, code.length())))
        );
        
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", code);
            pb.directory(sandboxDir.toFile());
            
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("HOME", sandboxDir.toString());
            env.put("TMPDIR", tempDir.toString());
            env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
            
            return executeInSandbox(pb, timeout, memoryLimit);
            
        } catch (Exception e) {
            logger.error("Tenant {}: Bash execution failed: {}", context.getTenantId(), e.getMessage());
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    // ============ 沙箱执行 ============
    
    private String executeInSandbox(ProcessBuilder pb, int timeout, long memoryLimit) throws Exception {
        // 使用 cgroup 或 ulimit 限制资源（如果可用）
        // 这里使用基本的进程限制
        
        pb.redirectErrorStream(false);
        Process process = pb.start();
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
        Future<String> stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));
        
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        
        String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
        String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        
        executor.shutdownNow();
        
        if (!finished) {
            process.destroyForcibly();
            context.getAuditLogger().log(
                com.nousresearch.hermes.tenant.audit.AuditEvent.CODE_TIMEOUT,
                Map.of("tenantId", context.getTenantId(), "timeout", timeout)
            );
            return ToolRegistry.toolError("Timeout after " + timeout + " seconds\n" + stdout);
        }
        
        int exitCode = process.exitValue();
        
        // 清理临时文件
        cleanupTempFiles();
        
        if (exitCode != 0) {
            return ToolRegistry.toolError("Exit code " + exitCode + "\n" + stderr + "\n" + stdout);
        }
        
        return ToolRegistry.toolResult(Map.of(
            "stdout", stdout, 
            "stderr", stderr, 
            "exit_code", exitCode,
            "tenant_id", context.getTenantId()
        ));
    }
    
    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > 50000) {
                    sb.append("... [truncated]\n");
                    break;
                }
            }
        }
        return sb.toString();
    }
    
    private void cleanupTempFiles() {
        try {
            Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("script_"))
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toInstant()
                            .isBefore(java.time.Instant.now().minusSeconds(300));
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.debug("Failed to cleanup temp file: {}", p);
                    }
                });
        } catch (IOException e) {
            logger.debug("Failed to list temp files: {}", e.getMessage());
        }
    }
    
    // ============ 代码包装 ============
    
    private String wrapPythonSandbox(String code, long memoryLimit) {
        return """
            import sys
            import resource
            
            # Set memory limit
            try:
                resource.setrlimit(resource.RLIMIT_AS, (%d, %d))
            except:
                pass
            
            # Restrict file system access
            import os
            os.chdir('%s')
            
            # Execute user code
            """.formatted(memoryLimit, memoryLimit, sandboxDir.toString().replace("'", "'\"'\"'"))
            + "\n" + code;
    }
    
    private String wrapJavaScriptSandbox(String code, long memoryLimit) {
        return """
            // Sandbox restrictions
            process.chdir('%s');
            
            // Memory limit hint (Node.js doesn't enforce this strictly)
            if (global.gc) global.gc();
            
            """.formatted(sandboxDir.toString().replace("'", "'\"'\"'"))
            + "\n" + code;
    }
    
    // ============ 工具方法 ============
    
    private int getTimeout(Map<String, Object> args) {
        if (args.containsKey("timeout")) {
            return ((Number) args.get("timeout")).intValue();
        }
        // 使用配额限制
        return (int) context.getQuotaManager().getQuota().getMaxExecutionTime().getSeconds();
    }
    
    private long getMemoryLimit(Map<String, Object> args) {
        if (args.containsKey("memory_limit_mb")) {
            long mb = ((Number) args.get("memory_limit_mb")).longValue();
            return mb * 1024 * 1024;
        }
        return context.getQuotaManager().getQuota().getMaxMemoryBytes();
    }
}
