package com.nousresearch.hermes.approval;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Approval request for external approval systems.
 */
public class ApprovalRequest {
    private final ApprovalSystem.ApprovalType type;
    private final String operation;
    private final String details;
    private final boolean dangerous;
    
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<Boolean> response = new AtomicReference<>();
    private final AtomicReference<String> reason = new AtomicReference<>();
    private volatile boolean sessionApproval = false;
    
    public ApprovalRequest(ApprovalSystem.ApprovalType type, String operation, 
                          String details, boolean dangerous) {
        this.type = type;
        this.operation = operation;
        this.details = details;
        this.dangerous = dangerous;
    }
    
    public ApprovalSystem.ApprovalType getType() {
        return type;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public String getDetails() {
        return details;
    }
    
    public boolean isDangerous() {
        return dangerous;
    }
    
    /**
     * Approve this request.
     */
    public void approve() {
        approve(false);
    }
    
    /**
     * Approve this request for the session.
     */
    public void approveForSession() {
        approve(true);
    }
    
    private void approve(boolean session) {
        response.set(true);
        sessionApproval = session;
        latch.countDown();
    }
    
    /**
     * Deny this request.
     */
    public void deny(String reason) {
        response.set(false);
        this.reason.set(reason);
        latch.countDown();
    }
    
    /**
     * Wait for response with timeout.
     */
    public ApprovalResult waitForResponse(int timeoutSeconds) {
        try {
            boolean received = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!received) {
                return ApprovalResult.timeout();
            }
            
            Boolean approved = response.get();
            if (approved == null) {
                return ApprovalResult.timeout();
            }
            
            if (approved) {
                return sessionApproval ? ApprovalResult.approvedForSession() 
                                        : ApprovalResult.approved();
            } else {
                return ApprovalResult.denied(reason.get() != null ? reason.get() : "User denied");
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalResult.timeout();
        }
    }
    
    @Override
    public String toString() {
        return String.format("ApprovalRequest[type=%s, operation=%s, dangerous=%s]", 
                            type, operation, dangerous);
    }
}
