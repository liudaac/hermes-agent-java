package com.nousresearch.hermes.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Handles approve/deny commands received from messaging platforms.
 * 
 * <p>This bridges the gap between user-facing messaging (Feishu, QQ, Discord, etc.)
 * and the internal ApprovalSystem. When a tool needs approval, the request is
 * sent to the user via their messaging channel. Their response is routed back
 * through this handler to complete or deny the pending request.</p>
 * 
 * <p>Usage:</p>
 * <pre>
 *   // In the messaging adapter:
 *   if (message.startsWith("/approve")) {
 *       handler.handleApproveCommand(userId, message);
 *   }
 *   if (message.startsWith("/deny")) {
 *       handler.handleDenyCommand(userId, message);
 *   }
 * </pre>
 */
public class ApprovalMessageHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ApprovalMessageHandler.class);
    
    // pending requests by user ID
    private final Map<String, ApprovalRequest> pendingRequests = new ConcurrentHashMap<>();
    
    // Optional callback to send approval request text to the user
    private BiConsumer<String, String> requestSender; // (userId, message)
    
    public ApprovalMessageHandler() {
    }
    
    /**
     * Set the callback used to send approval requests to the user.
     * This is called when a tool needs approval — the formatted request
     * is sent to the user's messaging channel.
     */
    public void setRequestSender(BiConsumer<String, String> sender) {
        this.requestSender = sender;
    }
    
    /**
     * Register a pending approval request for a user.
     * Returns the request ID so the user can reference it in their response.
     */
    public String registerRequest(String userId, ApprovalRequest request) {
        String requestId = "approval-" + System.currentTimeMillis() + "-" + userId.hashCode();
        pendingRequests.put(requestId, request);
        
        // Send the formatted approval prompt to the user's messaging channel
        if (requestSender != null) {
            String prompt = formatApprovalPrompt(requestId, request);
            requestSender.accept(userId, prompt);
        }
        
        return requestId;
    }
    
    /**
     * Handle an approve command from a user.
     * 
     * @param userId the user who sent the command
     * @param message the full message text (e.g., "/approve abc123" or "/approve")
     * @return response message to send back to the user, or null if no pending request found
     */
    public String handleApproveCommand(String userId, String message) {
        String requestId = extractRequestId(message);
        if (requestId != null) {
            return approveById(requestId);
        }
        
        // Try auto-matching: if user only has one pending request, approve it
        String autoKey = findSinglePending(userId);
        if (autoKey != null) {
            return approveById(autoKey);
        }
        
        return "No pending approval request found. Send the request ID, e.g., /approve approval-123456789-abcde";
    }
    
    /**
     * Handle a deny command from a user.
     */
    public String handleDenyCommand(String userId, String message) {
        String reason = extractReason(message);
        String requestId = extractRequestId(message);
        
        if (requestId != null) {
            return denyById(requestId, reason);
        }
        
        // Auto-matching
        String autoKey = findSinglePending(userId);
        if (autoKey != null) {
            return denyById(autoKey, reason);
        }
        
        return "No pending approval request found.";
    }
    
    /**
     * Handle the approve-for-session command (/approve-session).
     */
    public String handleApproveSessionCommand(String userId, String message) {
        String requestId = extractRequestId(message);
        if (requestId != null) {
            ApprovalRequest req = pendingRequests.remove(requestId);
            if (req != null) {
                req.approveForSession();
                logger.info("Approval request {} approved for session", requestId);
                return "Approved for the rest of this session.";
            }
        }
        
        String autoKey = findSinglePending(userId);
        if (autoKey != null) {
            ApprovalRequest req = pendingRequests.remove(autoKey);
            if (req != null) {
                req.approveForSession();
                return "Approved for the rest of this session.";
            }
        }
        
        return "No pending approval request found.";
    }
    
    // ---- Private helpers ----
    
    private String approveById(String requestId) {
        ApprovalRequest req = pendingRequests.remove(requestId);
        if (req != null) {
            req.approve();
            logger.info("Approval request {} approved", requestId);
            return "Approved: " + req.getOperation().substring(0, Math.min(80, req.getOperation().length()));
        }
        return "Approval request " + requestId + " not found or already resolved.";
    }
    
    private String denyById(String requestId, String reason) {
        ApprovalRequest req = pendingRequests.remove(requestId);
        if (req != null) {
            String reasonText = reason != null ? reason : "User denied via messaging";
            req.deny(reasonText);
            logger.info("Approval request {} denied: {}", requestId, reasonText);
            return "Denied: " + req.getOperation().substring(0, Math.min(80, req.getOperation().length()));
        }
        return "Approval request " + requestId + " not found or already resolved.";
    }
    
    private String findSinglePending(String userId) {
        String userPrefix = "-" + userId.hashCode();
        for (String key : pendingRequests.keySet()) {
            if (key.endsWith(userPrefix)) {
                return key;
            }
        }
        return null;
    }
    
    private String extractRequestId(String message) {
        // Parse "approval-123456789-abcde" from "/approve approval-123456789-abcde"
        String[] parts = message.trim().split("\\s+");
        for (String part : parts) {
            if (part.startsWith("approval-")) {
                return part;
            }
        }
        return null;
    }
    
    private String extractReason(String message) {
        // Everything after the first two tokens (command + requestId) is the reason
        String[] parts = message.trim().split("\\s+", 3);
        if (parts.length > 2) {
            return parts[2];
        }
        return null;
    }
    
    /**
     * Format an approval prompt for display in messaging channels.
     */
    static String formatApprovalPrompt(String requestId, ApprovalRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔐 **Approval Required**\n\n");
        sb.append("**Type:** ").append(request.getType()).append("\n");
        sb.append("**Operation:** ").append(request.getOperation()).append("\n");
        if (request.getDetails() != null && !request.getDetails().isEmpty()) {
            sb.append("**Details:** ").append(request.getDetails()).append("\n");
        }
        if (request.isDangerous()) {
            sb.append("⚠️ **This operation is flagged as dangerous!**\n");
        }
        sb.append("\n**Respond with:**\n");
        sb.append("- `").append("/approve ").append(requestId).append("` to approve\n");
        sb.append("- `").append("/approve-session ").append(requestId).append("` to approve for this session\n");
        sb.append("- `").append("/deny ").append(requestId).append(" [reason]` to deny\n");
        sb.append("\n⏳ Waiting for your response (timeout: 5 min)...");
        return sb.toString();
    }
}
