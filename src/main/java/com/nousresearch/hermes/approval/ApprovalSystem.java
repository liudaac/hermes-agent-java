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
        SUBAGENT_SPAWN,
        SKILL_INSTALL
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
        // S1-4a: Normalize command before pattern matching to prevent
        // shell line-continuation bypass (rm -rf \<newline>/ → rm -rf /)
        String normalizedOperation = normalizeCommandForDetection(operation);
        
        // Check danger patterns first
        for (DangerPattern pattern : dangerPatterns) {
            if (pattern.type == type && matchesPattern(normalizedOperation, pattern.pattern)) {
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
        // S1-4a: null 安全检查
        if (operation == null || operation.isEmpty()) {
            ApprovalMode mode = modeMap.getOrDefault(type, ApprovalMode.PROMPT);
            return mode != ApprovalMode.AUTO;
        }

        // S1-4a: Normalize before pattern matching
        String normalizedOperation = normalizeCommandForDetection(operation);
        
        // Check danger patterns
        for (DangerPattern pattern : dangerPatterns) {
            if (pattern.type == type && matchesPattern(normalizedOperation, pattern.pattern)) {
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
        prompt.append("╔════════════════════════════════════════════════════════════╗\n");
        prompt.append("║  APPROVAL REQUIRED                                         ║\n");
        prompt.append("╠════════════════════════════════════════════════════════════╣\n");
        prompt.append("║  Type: ").append(type).append("\n");
        prompt.append("║  Operation: ").append(operation).append("\n");
        if (details != null && !details.isEmpty()) {
            prompt.append("║  Details: ").append(details).append("\n");
        }
        if (isDangerous) {
            prompt.append("║  ⚠️  This operation is flagged as dangerous!\n");
        }
        prompt.append("╠════════════════════════════════════════════════════════════╣\n");
        prompt.append("║  Options:                                                  ║\n");
        prompt.append("║    (y) yes     - Approve this operation                    ║\n");
        prompt.append("║    (n) no      - Deny this operation                       ║\n");
        prompt.append("║    (a) always  - Approve and remember for this session     ║\n");
        prompt.append("║    (d) deny    - Deny and remember for this session        ║\n");
        prompt.append("╚════════════════════════════════════════════════════════════╝\n");
        prompt.append("Your choice: ");
        
        System.out.print(prompt.toString());
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String input = reader.readLine();
            
            if (input == null) {
                return ApprovalResult.denied("No input provided");
            }
            
            String choice = input.trim().toLowerCase();
            
            return switch (choice) {
                case "y", "yes" -> ApprovalResult.approved();
                case "n", "no" -> ApprovalResult.denied("User denied");
                case "a", "always" -> {
                    addSessionApproval(sessionKey);
                    yield ApprovalResult.approved();
                }
                case "d", "deny" -> {
                    preDeny(operation);
                    yield ApprovalResult.denied("User denied and remembered");
                }
                default -> {
                    System.out.println("Invalid choice, defaulting to deny.");
                    yield ApprovalResult.denied("Invalid choice");
                }
            };
            
        } catch (Exception e) {
            logger.error("Error reading approval input: {}", e.getMessage());
            return ApprovalResult.denied("Error: " + e.getMessage());
        }
    }
    
    private boolean matchesPattern(String operation, String pattern) {
        return operation.toLowerCase().contains(pattern.toLowerCase());
    }
    
    /**
     * Normalize a command string before dangerous-pattern matching.
     *
     * <p>S1-4a 补丁（对齐 Python 原版 commit 17f07aebd）：
     * 防止 shell 行续行绕过。</p>
     *
     * <p>POSIX shell 中，反斜杠紧跟换行符是行续行——shell 会同时删除
     * 两个字符并拼接 token，所以 {@code rm -rf \<newline>/} 实际执行为
     * {@code rm -rf /}。如果不折叠行续行，<code>\<newline></code> 会
     * 楔在 token 之间，导致 {@code rm -rf} 的正则匹配失败。</p>
     *
     * <p>处理步骤（对齐 Python _normalize_command_for_detection）：</p>
     * <ol>
     *   <li>去除 null 字节</li>
     *   <li>折叠 shell 行续行（\ + \n 或 \ + \r\n → 空）</li>
     *   <li>折叠反斜杠转义（\x → x，仅非换行字符）</li>
     *   <li>折叠空字符串字面量（'' 或 "" → 空）</li>
     *   <li>折叠 $IFS / ${IFS} 为空格</li>
     * </ol>
     *
     * @param command 原始命令字符串
     * @return 规范化后的命令字符串，用于模式匹配
     */
    static String normalizeCommandForDetection(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }

        String result = command;

        // 1. 去除 null 字节
        result = result.replace("\0", "");

        // 2. 折叠 shell 行续行：\ + \n 或 \ + \r\n → 空
        //    POSIX shell 会同时删除 \ 和换行符并拼接 token
        //    必须在反斜杠转义折叠之前执行（否则 \n 会被当作转义处理）
        result = result.replaceAll("\\\\\\r?\\n", "");

        // 3. 折叠反斜杠转义：\x → x（仅非换行字符）
        //    防止 r\m → rm 绕过
        result = result.replaceAll("\\\\([^.\\n])", "$1");

        // 4. 折叠空字符串字面量：'' 或 "" → 空
        //    防止 r''m → rm 绕过
        result = result.replace("''", "").replace("\"\"", "");

        // 5. 折叠 $IFS / ${IFS} 为空格
        //    POSIX shell 中 IFS 默认为 <space><tab><newline>
        //    rm${IFS}-rf${IFS}/ 会执行为 rm -rf /
        result = result.replaceAll("\\$\\{IFS\\b[^}]*\\}|\\$IFS\\b", " ");

        return result;
    }
    
    // ==================== Inner Classes ====================
    
    public static class DangerPattern {
        public final ApprovalType type;
        public final String pattern;
        public final ApprovalMode mode;
        
        public DangerPattern(ApprovalType type, String pattern, ApprovalMode mode) {
            this.type = type;
            this.pattern = pattern;
            this.mode = mode;
        }
    }
}