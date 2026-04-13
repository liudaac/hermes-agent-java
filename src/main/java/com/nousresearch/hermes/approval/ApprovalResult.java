package com.nousresearch.hermes.approval;

/**
 * Result of an approval request.
 */
public class ApprovalResult {
    private final boolean approved;
    private final String reason;
    private final boolean sessionApproved;
    
    private ApprovalResult(boolean approved, String reason, boolean sessionApproved) {
        this.approved = approved;
        this.reason = reason;
        this.sessionApproved = sessionApproved;
    }
    
    public static ApprovalResult approved() {
        return new ApprovalResult(true, null, false);
    }
    
    public static ApprovalResult approvedForSession() {
        return new ApprovalResult(true, null, true);
    }
    
    public static ApprovalResult denied(String reason) {
        return new ApprovalResult(false, reason, false);
    }
    
    public static ApprovalResult timeout() {
        return new ApprovalResult(false, "Approval timeout", false);
    }
    
    public boolean isApproved() {
        return approved;
    }
    
    public boolean isDenied() {
        return !approved;
    }
    
    public String getReason() {
        return reason;
    }
    
    public boolean isSessionApproved() {
        return sessionApproved;
    }
    
    @Override
    public String toString() {
        return approved ? "APPROVED" + (sessionApproved ? " (session)" : "") 
                       : "DENIED: " + reason;
    }
}
