package com.nousresearch.hermes.memory.store;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A long-term memory entry stored in the memory store.
 *
 * <p>Memories are scoped by tenant, optionally by user or agent,
 * and carry metadata for filtering. Each entry has an optional
 * embedding vector for semantic retrieval and time-based validity
 * for fact invalidation.</p>
 */
public class MemoryEntry {

    public enum MemoryType {
        PREFERENCE,   // user preference ("prefers concise answers")
        DECISION,     // a decision made ("chose Postgres over MySQL")
        FACT,         // a factual observation ("user works at Acme Inc")
        CONTEXT,      // situational context ("project deadline is Friday")
        FEEDBACK      // user feedback ("didn't like verbose responses")
    }

    private String id;
    private String tenantId;
    private String userId;
    private String agentId;
    private MemoryType type;
    private String content;
    private String category;
    private Map<String, String> metadata;
    private float[] embedding;
    private long createdAt;      // epoch millis
    private Long validFrom;     // epoch millis, null = now
    private Long validUntil;    // epoch millis, null = still valid
    private Long expiresAt;     // epoch millis, null = never expires
    private String source;      // e.g. "session_decay:abc123"

    // ── Builder ─────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final MemoryEntry entry = new MemoryEntry();

        public Builder tenantId(String v) { entry.tenantId = v; return this; }
        public Builder userId(String v) { entry.userId = v; return this; }
        public Builder agentId(String v) { entry.agentId = v; return this; }
        public Builder type(MemoryType v) { entry.type = v; return this; }
        public Builder content(String v) { entry.content = v; return this; }
        public Builder category(String v) { entry.category = v; return this; }
        public Builder metadata(Map<String, String> v) { entry.metadata = v; return this; }
        public Builder embedding(float[] v) { entry.embedding = v; return this; }
        public Builder createdAt(long v) { entry.createdAt = v; return this; }
        public Builder validFrom(Long v) { entry.validFrom = v; return this; }
        public Builder validUntil(Long v) { entry.validUntil = v; return this; }
        public Builder expiresAt(Long v) { entry.expiresAt = v; return this; }
        public Builder source(String v) { entry.source = v; return this; }

        public MemoryEntry build() {
            if (entry.tenantId == null || entry.tenantId.isBlank())
                throw new IllegalArgumentException("tenantId is required");
            if (entry.content == null || entry.content.isBlank())
                throw new IllegalArgumentException("content is required");
            if (entry.type == null)
                entry.type = MemoryType.FACT;
            if (entry.createdAt == 0)
                entry.createdAt = System.currentTimeMillis();
            if (entry.validFrom == null)
                entry.validFrom = entry.createdAt;
            return entry;
        }
    }

    // ── Getters / Setters ────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public MemoryType getType() { return type; }
    public void setType(MemoryType type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getValidFrom() { return validFrom; }
    public void setValidFrom(Long validFrom) { this.validFrom = validFrom; }

    public Long getValidUntil() { return validUntil; }
    public void setValidUntil(Long validUntil) { this.validUntil = validUntil; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public boolean isValid() {
        long now = System.currentTimeMillis();
        if (validUntil != null && validUntil <= now) return false;
        if (expiresAt != null && expiresAt <= now) return false;
        return true;
    }
}
