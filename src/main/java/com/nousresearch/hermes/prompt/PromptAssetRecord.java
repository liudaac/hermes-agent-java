package com.nousresearch.hermes.prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned prompt asset managed at workspace scope. */
public class PromptAssetRecord {
    private String workspaceId;
    private String assetId;
    private String name;
    private String purpose;
    private String content;
    private int version = 1;
    private String status = "ACTIVE";
    private List<String> tags = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getWorkspaceId() { return workspaceId; }
    public PromptAssetRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getAssetId() { return assetId; }
    public PromptAssetRecord setAssetId(String assetId) { this.assetId = assetId; return this; }
    public String getName() { return name; }
    public PromptAssetRecord setName(String name) { this.name = name; return this; }
    public String getPurpose() { return purpose; }
    public PromptAssetRecord setPurpose(String purpose) { this.purpose = purpose; return this; }
    public String getContent() { return content; }
    public PromptAssetRecord setContent(String content) { this.content = content; return this; }
    public int getVersion() { return version; }
    public PromptAssetRecord setVersion(int version) { this.version = version; return this; }
    public String getStatus() { return status; }
    public PromptAssetRecord setStatus(String status) { this.status = status; return this; }
    public List<String> getTags() { return tags; }
    public PromptAssetRecord setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public PromptAssetRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public PromptAssetRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public PromptAssetRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
