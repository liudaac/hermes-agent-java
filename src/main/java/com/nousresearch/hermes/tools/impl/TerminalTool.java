package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Terminal command execution tool.
 * Executes shell commands with safety controls.
 */
public class TerminalTool {
    private static final Logger logger = LoggerFactory.getLogger(TerminalTool.class);
    private static final long DEFAULT_TIMEOUT_MS = 300000; // 5 minutes
    private static final long MAX_OUTPUT_CHARS = 50000;
    
    private static final List<String> DANGEROUS_PATTERNS = List.of(
        "rm -rf /", "rm -rf /*", "rm -rf ~", "> /dev/sda", "dd if=/dev/zero",
        "mkfs.", "format", ":(){ :|:& };:"
    );
    
    /**
     * Register terminal tools.
     */
    public static void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Builder()
            .name("execute_command")
            .toolset("terminal")
            .schema(Map.of(
                "description", "Execute a terminal command",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "command", Map.of(
                            "type", "string",
                            "description", "Command to execute"
                        ),
                        "cwd", Map.of(
                            "type", "string",
                            "description", "Working directory (optional)"
                        ),
                        "timeout", Map.of(
                            "type", "integer",
                            "description", "Timeout in seconds (default: 300)"
                        )
                    ),
                    "required", List.of("command")
                )
            ))
            .handler(TerminalTool::execute)
            .emoji("⚡")
            .build());
    }
    
    /**
     * Execute terminal command.
     */
    private static String execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        String cwd = (String) args.get("cwd");
        int timeout = args.containsKey("timeout") ? 
            ((Number) args.get("timeout")).intValue() : 300;
        
        if (command == null || command.trim().isEmpty()) {
            return ToolRegistry.toolError("Command is required");
        }
        
        // Safety check
        String checkResult = checkSafety(command);
        if (checkResult != null) {
            return ToolRegistry.toolError(checkResult, Map.of(
                "safety_check", "failed",
                "command", command
            ));
        }
        
        try {
            logger.info("Executing command: {}", command);
            
            // Prepare process builder
            ProcessBuilder pb = new ProcessBuilder();
            
            // Use shell for complex commands
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("/bin/bash", "-c", command);
            }
            
            // Set working directory
            if (cwd != null && !cwd.isEmpty()) {
                pb.directory(new File(cwd));
            }
            
            // Start process
            Process process = pb.start();
            
            // Read output with timeout
            ExecutorService executor = Executors.newFixedThreadPool(2);
            
            Future<String> stdoutFuture = executor.submit(() -> readStream(process.inputStream()));
            Future<String> stderrFuture = executor.submit(() -> readStream(process.errorStream()));
            
            // Wait for completion
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            
            String stdout = "";
            String stderr = "";
            
            try {
                stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                stdoutFuture.cancel(true);
            }
            
            try {
                stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                stderrFuture.cancel(true);
            }
            
            executor.shutdownNow();
            
            if (!finished) {
                process.destroyForcibly();
                return ToolRegistry.toolError("Command timed out after " + timeout + " seconds", Map.of(
                    "stdout", truncate(stdout),
                    "stderr", truncate(stderr),
                    "timed_out", true
                ));
            }
            
            int exitCode = process.exitValue();
            
            // Build result
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("command", command);
            result.put("exit_code", exitCode);
            result.put("stdout", truncate(stdout));
            result.put("stderr", truncate(stderr));
            result.put("success", exitCode == 0);
            
            return ToolRegistry.toolResult(result);
            
        } catch (Exception e) {
            logger.error("Command execution failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Check command safety.
     */
    private static String checkSafety(String command) {
        String lower = command.toLowerCase();
        
        for (String pattern : DANGEROUS_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return "Command contains dangerous pattern: " + pattern;
            }
        }
        
        // Check for sudo without restrictions
        if (lower.trim().startsWith("sudo") && !lower.contains("-u ")) {
            // Allow sudo but log warning
            logger.warn("Command uses sudo: {}", command);
        }
        
        return null;
    }
    
    /**
     * Read stream to string.
     */
    private static String readStream(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > MAX_OUTPUT_CHARS * 2) {
                    sb.append("... [output truncated]\n");
                    break;
                }
            }
        }
        return sb.toString();
    }
    
    /**
     * Truncate output if too long.
     */
    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, (int) MAX_OUTPUT_CHARS) + 
               "\n... [truncated " + (text.length() - MAX_OUTPUT_CHARS) + " chars]";
    }
}
