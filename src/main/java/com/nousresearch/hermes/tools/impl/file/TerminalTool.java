package com.nousresearch.hermes.tools.impl.file;

import com.nousresearch.hermes.tools.ToolEntry;
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
    
    /** Commands that require explicit user approval via chat. */
    private static final List<String> RISKY_PATTERNS = List.of(
        "rm -rf", "rm -r", "rmdir", "mv /", "cp /", "chmod 777",
        "chown", "kill -9", "pkill", "shutdown", "reboot",
        "git push --force", "git push -f", "git reset --hard",
        "DROP TABLE", "DROP DATABASE", "DELETE FROM",
        "truncate", "shred", "wget -O -", "curl | bash", "curl | sh"
    );
    
    /**
     * Register terminal tools.
     */
    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("execute_command")
            .toolset("terminal")
            .schema(Map.of(
                "description", "Execute a terminal command. Risky commands (rm -rf, git push --force, DROP TABLE, etc.) require approved=true. If you get a 'NEEDS APPROVAL' error, ask the user to confirm and retry with approved=true.",
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
                        ),
                        "approved", Map.of(
                            "type", "boolean",
                            "description", "Set to true when the user has explicitly confirmed execution of a risky command. Default: false.",
                            "default", false
                        )
                    ),
                    "required", List.of("command")
                )
            ))
            .handler(TerminalTool::execute)
            .emoji("⚡")
            .risk(com.nousresearch.hermes.approval.ToolRisk.MEDIUM)
            .requiresApproval(false)
            .approvalType(com.nousresearch.hermes.approval.ApprovalSystem.ApprovalType.TERMINAL_COMMAND)
            .approvalMessageTemplate("Terminal command: {command}")
            .build());
    }
    
    /**
     * Execute terminal command.
     */
    private static String execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        String cwd = (String) args.get("cwd");
        boolean approved = Boolean.TRUE.equals(args.get("approved"));
        int timeout = args.containsKey("timeout") ? 
            ((Number) args.get("timeout")).intValue() : 300;
        
        if (command == null || command.trim().isEmpty()) {
            return ToolRegistry.toolError("Command is required");
        }
        
        // Safety check: DANGER = always blocked, RISKY = needs approval
        String danger = checkDanger(command);
        if (danger != null) {
            logger.warn("Blocked dangerous command: {}", command);
            return ToolRegistry.toolError("BLOCKED (dangerous): " + danger, Map.of(
                "safety_check", "danger",
                "command", command
            ));
        }
        
        String risky = checkRisky(command);
        if (risky != null && !approved) {
            logger.info("Risky command needs approval: {}", command);
            return ToolRegistry.toolError(
                "NEEDS APPROVAL: This command is flagged as risky (" + risky + "). " +
                "Ask the user to confirm, then retry with approved=true.",
                Map.of(
                    "safety_check", "needs_approval",
                    "risk_pattern", risky,
                    "command", command
                )
            );
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
            
            Future<String> stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));
            
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
     * Check if command is unconditionally dangerous (always blocked).
     */
    private static String checkDanger(String command) {
        String lower = command.toLowerCase();
        for (String pattern : DANGEROUS_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return pattern;
            }
        }
        return null;
    }
    
    /**
     * Check if command is risky and requires user approval.
     * Returns the matched risk pattern, or null if safe.
     */
    private static String checkRisky(String command) {
        String lower = command.toLowerCase();
        for (String pattern : RISKY_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return pattern;
            }
        }
        // Check for sudo
        if (lower.trim().startsWith("sudo")) {
            return "sudo";
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
