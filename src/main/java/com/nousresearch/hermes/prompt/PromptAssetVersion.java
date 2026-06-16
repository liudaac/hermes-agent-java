package com.nousresearch.hermes.prompt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable version snapshot for a prompt asset. */
public class PromptAssetVersion {
    private int version;
    private String status = "DRAFT";
    private String content;
    private String changeSummary;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant activatedAt;

    public int getVersion() { return version; }
    public PromptAssetVersion setVersion(int version) { this.version = version; return this; }
    public String getStatus() { return status; }
    public PromptAssetVersion setStatus(String status) { this.status = status; return this; }
    public String getContent() { return content; }
    public PromptAssetVersion setContent(String content) { this.content = content; return this; }
    public String getChangeSummary() { return changeSummary; }
    public PromptAssetVersion setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public PromptAssetVersion setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public PromptAssetVersion setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getActivatedAt() { return activatedAt; }
    public PromptAssetVersion setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; return this; }
}
