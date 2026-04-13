package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Code execution tool supporting multiple languages.
 */
public class CodeTool {
    private static final Logger logger = LoggerFactory.getLogger(CodeTool.class);
    private static final int DEFAULT_TIMEOUT = 60;
    
    private final ApprovalSystem approvalSystem;
    private final Path tempDir;
    
    public CodeTool(ApprovalSystem approvalSystem) {
        this.approvalSystem = approvalSystem;
        this.tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "hermes-code");
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            logger.error("Failed to create temp dir: {}", e.getMessage());
        }
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Builder()
            .name("execute_python")
            .toolset("code")
            .schema(Map.of("description", "Execute Python code",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("code", Map.of("type", "string"), "timeout", Map.of("type", "integer", "default", 60)),
                    "required", List.of("code"))))
            .handler(this::executePython).emoji("🐍").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("execute_javascript")
            .toolset("code")
            .schema(Map.of("description", "Execute JavaScript",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("code", Map.of("type", "string"), "timeout", Map.of("type", "integer", "default", 60)),
                    "required", List.of("code"))))
            .handler(this::executeJavaScript).emoji("📜").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("execute_bash")
            .toolset("code")
            .schema(Map.of("description", "Execute Bash",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("code", Map.of("type", "string"), "timeout", Map.of("type", "integer", "default", 60)),
                    "required", List.of("code"))))
            .handler(this::executeBash).emoji("🐚").build());
    }
    
    private String executePython(Map<String, Object> args) {
        String code = (String) args.get("code");
        int timeout = args.containsKey("timeout") ? ((Number) args.get("timeout")).intValue() : DEFAULT_TIMEOUT;
        
        ApprovalResult approval = approvalSystem.requestApproval(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            "python: " + code.substring(0, Math.min(100, code.length())), null);
        if (!approval.isApproved()) return ToolRegistry.toolError("Approval denied");
        
        try {
            Path scriptFile = tempDir.resolve("script_" + System.currentTimeMillis() + ".py");
            Files.writeString(scriptFile, code);
            ProcessBuilder pb = new ProcessBuilder("python3", scriptFile.toString());
            return executeProcess(pb, timeout);
        } catch (Exception e) {
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    private String executeJavaScript(Map<String, Object> args) {
        String code = (String) args.get("code");
        int timeout = args.containsKey("timeout") ? ((Number) args.get("timeout")).intValue() : DEFAULT_TIMEOUT;
        
        ApprovalResult approval = approvalSystem.requestApproval(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            "javascript: " + code.substring(0, Math.min(100, code.length())), null);
        if (!approval.isApproved()) return ToolRegistry.toolError("Approval denied");
        
        try {
            Path scriptFile = tempDir.resolve("script_" + System.currentTimeMillis() + ".js");
            Files.writeString(scriptFile, code);
            ProcessBuilder pb = new ProcessBuilder("node", scriptFile.toString());
            return executeProcess(pb, timeout);
        } catch (Exception e) {
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    private String executeBash(Map<String, Object> args) {
        String code = (String) args.get("code");
        int timeout = args.containsKey("timeout") ? ((Number) args.get("timeout")).intValue() : DEFAULT_TIMEOUT;
        
        ApprovalResult approval = approvalSystem.requestApproval(
            ApprovalSystem.ApprovalType.TERMINAL_COMMAND, code, null);
        if (!approval.isApproved()) return ToolRegistry.toolError("Approval denied");
        
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", code);
            return executeProcess(pb, timeout);
        } catch (Exception e) {
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    private String executeProcess(ProcessBuilder pb, int timeout) throws Exception {
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
            return ToolRegistry.toolError("Timeout after " + timeout + " seconds\n" + stdout);
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            return ToolRegistry.toolError("Exit code " + exitCode + "\n" + stderr + "\n" + stdout);
        }
        
        return ToolRegistry.toolResult(Map.of("stdout", stdout, "stderr", stderr, "exit_code", exitCode));
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
}
