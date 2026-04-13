package com.nousresearch.hermes.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Approval system for sensitive operations.
 * Mirrors Python's approval.py functionality.
 */
public class ApprovalSystem {
    private static final Logger logger = LoggerFactory.getLogger(ApprovalSystem.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    
    public enum ApprovalType {
        TERMINAL_COMMAND,
        FILE_WRITE,
        FILE_DELETE,
        CODE_EXECUTION,
        BROWSER_ACTION,
        SUBAGENT_SPAWN
    }
    
    public enum ApprovalMode {
        AUTO,      // Auto-approve safe operations
        PROMPT,    // Prompt user for approval
        REQUIRE,   // Require explicit approval
        DENY       // Deny all
    }
    
    private final Map<ApprovalType, ApprovalMode> modeMap = new HashMap<>();
    private final Set<String> approvedCommands = ConcurrentHashMap.newKeySet();
    private final Set<String> deniedCommands = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> sessionApprovals = new ConcurrentHashMap<>();
    private final long sessionTimeoutMs = TimeUnit.MINUTES.toMillis(30);
    
    // Dangerous patterns that always require approval
    private final List<DangerPattern> dangerPatterns = List.of(
        new DangerPattern(ApprovalType.TERMINAL_COMMAND, "rm -rf", ApprovalMode.REQUIRE),
        new DangerPattern(ApprovalType.TERMINAL_COMMAND, "sudo", ApprovalMode.REQUIRE),
        new DangerPattern(ApprovalType.TERMINAL_COMMAND, "dd if=", ApprovalMode.REQUIRE),
        new DangerPattern(ApprovalType.TERMINAL_COMMAND, "mkfs", ApprovalMode.DENY),
        new DangerPattern(ApprovalType.TERMINAL_COMMAND, "> /dev/sda", ApprovalMode.DENY),
        new DangerPattern(ApprovalType.FILE_WRITE, "/etc/", ApprovalMode.REQUIRE),
        new DangerPattern(ApprovalType.FILE_WRITE, "/usr/lib/systemd/", ApprovalMode.REQUIRE),
        new DangerPattern(ApprovalType.FILE_DELETE, "*", ApprovalMode.REQUIRE)
    );
    
    // Callback for external approval (e.g., GUI, web interface)
    private Consumer<ApprovalRequest> externalApprover;
    
    public ApprovalSystem() {
        // Default modes
        modeMap.put(ApprovalType.TERMINAL_COMMAND, ApprovalMode.PROMPT);
        modeMap.put(ApprovalType.FILE_WRITE, ApprovalMode.PROMPT);
        modeMap.put(ApprovalType.FILE_DELETE, ApprovalMode.REQUIRE);
        modeMap.put(ApprovalType.CODE_EXECUTION, ApprovalMode.PROMPT);
        modeMap.put(ApprovalType.BROWSER_ACTION, ApprovalMode.AUTO);
        modeMap.put(ApprovalType.SUBAGENT_SPAWN, ApprovalMode.AUTO);
    }
    
    /**
     * Check if an operation requires approval and get it.
     */
    public ApprovalResult requestApproval(ApprovalType type, String operation, String details) {
        // Check danger patterns first
        for (DangerPattern pattern : dangerPatterns) {
            if (pattern.type == type && matchesPattern(operation, pattern.pattern)) {
                if (pattern.mode == ApprovalMode.DENY) {
                    return ApprovalResult.denied("Operation matches denied pattern: " + pattern.pattern);
                }
                // Force require mode for dangerous patterns
                return promptForApproval(type, operation, details, true);
            }
        }
        
        // Check configured mode
        ApprovalMode mode = modeMap.getOrDefault(type, ApprovalMode.PROMPT);
        
        return switch (mode) {
            case AUTO -> ApprovalResult.approved();
            case DENY -> ApprovalResult.denied("Operation type is denied by policy");
            case REQUIRE, PROMPT -> promptForApproval(type, operation, details, false);
        };
    }
    
    /**
     * Quick check if operation would need approval (without prompting).
     */
    public boolean wouldNeedApproval(ApprovalType type, String operation) {
        // Check danger patterns
        for (DangerPattern pattern : dangerPatterns) {
            if (pattern.type == type && matchesPattern(operation, pattern.pattern)) {
                return pattern.mode != ApprovalMode.AUTO;
            }
        }
        
        ApprovalMode mode = modeMap.getOrDefault(type, ApprovalMode.PROMPT);
        return mode != ApprovalMode.AUTO;
    }
    
    /**
     * Check if already approved in this session.
     */
    public boolean isSessionApproved(String operationKey) {
        Long expiry = sessionApprovals.get(operationKey);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            sessionApprovals.remove(operationKey);
            return false;
        }
        return true;
    }
    
    /**
     * Add session approval.
     */
    public void addSessionApproval(String operationKey) {
        sessionApprovals.put(operationKey, System.currentTimeMillis() + sessionTimeoutMs);
    }
    
    /**
     * Clear all session approvals.
     */
    public void clearSessionApprovals() {
        sessionApprovals.clear();
    }
    
    /**
     * Set approval mode for a type.
     */
    public void setMode(ApprovalType type, ApprovalMode mode) {
        modeMap.put(type, mode);
    }
    
    /**
     * Set external approval callback.
     */
    public void setExternalApprover(Consumer<ApprovalRequest> callback) {
        this.externalApprover = callback;
    }
    
    /**
     * Pre-approve a command pattern.
     */
    public void preApprove(String pattern) {
        approvedCommands.add(pattern.toLowerCase());
        logger.info("Pre-approved pattern: {}", pattern);
    }
    
    /**
     * Pre-deny a command pattern.
     */
    public void preDeny(String pattern) {
        deniedCommands.add(pattern.toLowerCase());
        logger.info("Pre-denied pattern: {}", pattern);
    }
    
    // ==================== Private Methods ====================
    
    private ApprovalResult promptForApproval(ApprovalType type, String operation, 
                                              String details, boolean isDangerous) {
        // Check pre-approved patterns
        String lowerOp = operation.toLowerCase();
        for (String pattern : approvedCommands) {
            if (lowerOp.contains(pattern)) {
                return ApprovalResult.approved();
            }
        }
        
        // Check pre-denied patterns
        for (String pattern : deniedCommands) {
            if (lowerOp.contains(pattern)) {
                return ApprovalResult.denied("Operation matches denied pattern");
            }
        }
        
        // Check session approval
        String sessionKey = type + ":" + operation;
        if (isSessionApproved(sessionKey)) {
            return ApprovalResult.approved();
        }
        
        // Use external approver if set
        if (externalApprover != null) {
            ApprovalRequest request = new ApprovalRequest(type, operation, details, isDangerous);
            externalApprover.accept(request);
            return request.waitForResponse(DEFAULT_TIMEOUT_SECONDS);
        }
        
        // Console prompt
        return consolePrompt(type, operation, details, isDangerous, sessionKey);
    }
    
    private ApprovalResult consolePrompt(ApprovalType type, String operation, 
                                          String details, boolean isDangerous, 
                                          String sessionKey) {
        // Build approval prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("\n");
        prompt.append("╔════════════════════════════════════════════════════════════