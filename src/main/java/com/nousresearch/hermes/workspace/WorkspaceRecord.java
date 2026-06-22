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

    /** 获取WorkspaceId。 */
    public String getWorkspaceId() { return workspaceId; }
    public WorkspaceRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    /** 获取TenantId。 */
    public String getTenantId() { return tenantId; }
    public WorkspaceRecord setTenantId(String tenantId) { this.tenantId = tenantId; return this; }
    /** 获取Name。 */
    public String getName() { return name; }
    public WorkspaceRecord setName(String name) { this.name = name; return this; }
    /** 获取Description。 */
    public String getDescription() { return description; }
    public WorkspaceRecord setDescription(String description) { this.description = description; return this; }
    /** 获取Owner。 */
    public String getOwner() { return owner; }
    public WorkspaceRecord setOwner(String owner) { this.owner = owner; return this; }
    /** 获取Status。 */
    public String getStatus() { return status; }
    public WorkspaceRecord setStatus(String status) { this.status = status; return this; }
    /** 获取CreatedAt。 */
    public Instant getCreatedAt() { return createdAt; }
    public WorkspaceRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    /** 获取UpdatedAt。 */
    public Instant getUpdatedAt() { return updatedAt; }
    public WorkspaceRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
    /** 获取Metadata。 */
    public Map<String, Object> getMetadata() { return metadata; }
    public WorkspaceRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
}
