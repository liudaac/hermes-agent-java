package com.nousresearch.hermes.workspace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Business-facing workspace façade over a tenant. */
public class WorkspaceRecord {
    private String workspaceId;
    private String tenantId;
    private String name;
    private String description;
    private String owner;
    private String status = "ACTIVE";
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public WorkspaceRecord() {
    }

    public WorkspaceRecord(String workspaceId, String tenantId, String name, String description, String owner,
                           Instant createdAt, Instant updatedAt) {
        this.workspaceId = workspaceId;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getWorkspaceId() { return workspaceId; }
    public WorkspaceRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getTenantId() { return tenantId; }
    public WorkspaceRecord setTenantId(String tenantId) { this.tenantId = tenantId; return this; }
    public String getName() { return name; }
    public WorkspaceRecord setName(String name) { this.name = name; return this; }
    public String getDescription() { return description; }
    public WorkspaceRecord setDescription(String description) { this.description = description; return this; }
    public String getOwner() { return owner; }
    public WorkspaceRecord setOwner(String owner) { this.owner = owner; return this; }
    public String getStatus() { return status; }
    public WorkspaceRecord setStatus(String status) { this.status = status; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public WorkspaceRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public WorkspaceRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public WorkspaceRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
}
