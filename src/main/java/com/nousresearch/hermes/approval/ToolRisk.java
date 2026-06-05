package com.nousresearch.hermes.approval;

/**
 * Risk level for tool operations.
 * 
 * <p>Used to determine whether a tool call requires human approval before execution.
 * Works with {@link ApprovalSystem} to create a tiered safety mechanism:</p>
 * <ul>
 *   <li>NONE - Safe read-only operations, always auto-approve</li>
 *   <li>LOW - Minor side effects, auto-approve by default</li>
 *   <li>MEDIUM - Noticeable side effects, prompt for approval</li>
 *   <li>HIGH - Significant side effects, require explicit approval</li>
 *   <li>CRITICAL - Potentially destructive, require dual-approval or deny</li>
 * </ul>
 */
public enum ToolRisk {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;
    
    /**
     * Whether this risk level requires approval by default.
     */
    public boolean requiresApprovalByDefault() {
        return this == MEDIUM || this == HIGH || this == CRITICAL;
    }
    
    /**
     * Map to ApprovalSystem.ApprovalMode for programmatic usage.
     */
    public ApprovalSystem.ApprovalMode toDefaultMode() {
        return switch (this) {
            case NONE, LOW -> ApprovalSystem.ApprovalMode.AUTO;
            case MEDIUM -> ApprovalSystem.ApprovalMode.PROMPT;
            case HIGH -> ApprovalSystem.ApprovalMode.REQUIRE;
            case CRITICAL -> ApprovalSystem.ApprovalMode.DENY;
        };
    }
}
