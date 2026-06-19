package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Business service that creates and lists workspaces while reusing TenantManager underneath. */
public class WorkspaceService {
    private final FileWorkspaceRepository repository;
    private final TenantManager tenantManager;

    public WorkspaceService(TenantManager tenantManager) {
        this(new FileWorkspaceRepository(Constants.getHermesHome().resolve("business/workspaces")), tenantManager);
    }

    public WorkspaceService(Path rootDir, TenantManager tenantManager) {
        this(new FileWorkspaceRepository(rootDir), tenantManager);
    }

    public WorkspaceService(FileWorkspaceRepository repository, TenantManager tenantManager) {
        this.repository = repository;
        this.tenantManager = tenantManager;
    }

    public WorkspaceRecord createWorkspace(String workspaceId, String name, String description, String owner,
                                           Map<String, Object> metadata) {
        validateId(workspaceId, "workspaceId");
        if (repository.exists(workspaceId)) {
            throw new WorkspaceAlreadyExistsException(workspaceId);
        }

        String tenantId = workspaceId;
        if (!tenantManager.exists(tenantId)) {
            TenantProvisioningRequest request = TenantProvisioningRequest.builder(tenantId, owner != null ? owner : "business")
                .tenantName(name != null && !name.isBlank() ? name : workspaceId)
                .description(description)
                .config(Map.of("workspaceId", workspaceId, "businessFacade", true))
                .build();
            tenantManager.createTenant(request);
        }

        Instant now = Instant.now();
        WorkspaceRecord record = new WorkspaceRecord(workspaceId, tenantId,
            name != null && !name.isBlank() ? name : workspaceId,
            description,
            owner != null && !owner.isBlank() ? owner : "business",
            now,
            now);
        record.setMetadata(metadata);
        repository.save(record);
        return record;
    }

    public List<WorkspaceRecord> listWorkspaces() {
        return repository.list();
    }

    public Optional<WorkspaceRecord> getWorkspace(String workspaceId) {
        return repository.findById(workspaceId);
    }

    public WorkspaceRecord requireWorkspace(String workspaceId) {
        return getWorkspace(workspaceId).orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    public boolean exists(String workspaceId) {
        return repository.exists(workspaceId);
    }

    /**
     * Resolve the TenantContext for a workspace.
     * Workspace is a business facade over a tenant; this gives access to
     * the underlying tenant runtime (agent execution, tool sandbox, etc).
     */
    public TenantContext resolveTenantContext(String workspaceId) {
        WorkspaceRecord workspace = requireWorkspace(workspaceId);
        return tenantManager.getTenant(workspace.getTenantId());
    }

    /** Expose the underlying TenantManager for advanced wiring (e.g. TeamBlueprintRuntime). */
    public TenantManager getTenantManager() {
        return tenantManager;
    }

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    public static class WorkspaceAlreadyExistsException extends RuntimeException {
        public WorkspaceAlreadyExistsException(String workspaceId) { super("Workspace already exists: " + workspaceId); }
    }

    public static class WorkspaceNotFoundException extends RuntimeException {
        public WorkspaceNotFoundException(String workspaceId) { super("Workspace not found: " + workspaceId); }
    }
}
