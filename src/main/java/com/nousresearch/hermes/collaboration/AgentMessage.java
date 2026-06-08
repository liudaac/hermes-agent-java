package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Peer-to-peer message between agents in the organization.
 * 
 * <p>Provides structured communication beyond the master-worker
 * SubAgent pattern. Agents can request, negotiate, delegate, and
 * notify each other as peers.</p>
 */
public class AgentMessage {
    
    public enum Type {
        /** Request another agent to do something. */
        REQUEST,
        /** Response to a request. */
        RESPONSE,
        /** Notification — no reply expected. */
        NOTIFY,
        /** Negotiation proposal between agents. */
        PROPOSE,
        /** Accept a proposal. */
        ACCEPT,
        /** Reject a proposal with reason. */
        REJECT,
        /** Escalate to a senior agent or human. */
        ESCALATE,
        /** Broadcast to all agents. */
        BROADCAST
    }
    
    public enum Status {
        PENDING,
        DELIVERED,
        PROCESSED,
        FAILED,
        TIMEOUT
    }
    
    private final String messageId;
    private final String senderId;
    private final String receiverId;
    private final Type type;
    private final String action;
    private final Map<String, Object> payload;
    private final String replyTo;    // messageId this is replying to
    private final Instant timestamp;
    private final long timeoutMs;
    private volatile Status status;
    private String resultText;
    
    private AgentMessage(Builder builder) {
        this.messageId = builder.messageId != null ? builder.messageId 
            : UUID.randomUUID().toString().substring(0, 8);
        this.senderId = builder.senderId;
        this.receiverId = builder.receiverId;
        this.type = builder.type;
        this.action = builder.action;
        this.payload = builder.payload != null ? builder.payload : Map.of();
        this.replyTo = builder.replyTo;
        this.timestamp = Instant.now();
        this.timeoutMs = builder.timeoutMs > 0 ? builder.timeoutMs : 300_000;
        this.status = Status.PENDING;
    }
    
    // Getters
    public String getMessageId() { return messageId; }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
    public Type getType() { return type; }
    public String getAction() { return action; }
    public Map<String, Object> getPayload() { return payload; }
    public String getReplyTo() { return replyTo; }
    public Instant getTimestamp() { return timestamp; }
    public long getTimeoutMs() { return timeoutMs; }
    public Status getStatus() { return status; }
    public String getResultText() { return resultText; }
    
    public void setStatus(Status status) { this.status = status; }
    public void setResultText(String resultText) { this.resultText = resultText; }
    
    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp.toEpochMilli() > timeoutMs;
    }
    
    public static Builder builder(String senderId, String receiverId, Type type) {
        return new Builder(senderId, receiverId, type);
    }
    
    public static class Builder {
        private String messageId;
        private final String senderId;
        private final String receiverId;
        private final Type type;
        private String action;
        private Map<String, Object> payload;
        private String replyTo;
        private long timeoutMs = 300_000;
        
        Builder(String senderId, String receiverId, Type type) {
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.type = type;
        }
        
        public Builder messageId(String id) { this.messageId = id; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder payload(Map<String, Object> payload) { this.payload = payload; return this; }
        public Builder replyTo(String replyTo) { this.replyTo = replyTo; return this; }
        public Builder timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        
        public AgentMessage build() {
            return new AgentMessage(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format("AgentMessage[%s] %s → %s: %s(%s)", 
            messageId, senderId, receiverId, type, action);
    }
}
