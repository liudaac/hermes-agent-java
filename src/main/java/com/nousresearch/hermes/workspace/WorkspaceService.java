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

/**
 * 工作空间服务 — B 端租户隔离的基本单元，底层复用 TenantManager。
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建工作空间（自动按需创建底层 tenant）</li>
 *   <li>查询、校验工作空间存在性</li>
 *   <li>解析工作空间对应的 TenantContext（供 Agent Runtime、工具沙箱使用）</li>
 * </ul>
 * <p>工作空间是业务层 facade，一个 workspace 对应一个 tenant，存储在
 * <code>~/.hermes/business/workspaces/{workspaceId}/workspace.json</code>。</p>
 */
public class WorkspaceService {
    /** 工作空间持久化仓库 */
    private final FileWorkspaceRepository repository;
    /** 租户管理器 — 负责底层 tenant 的创建和运行时上下文 */
    private final TenantManager tenantManager;

    /**
     * 默认构造函数 — 使用 ~/.hermes/business/workspaces 作为持久化根目录。
     *
     * @param tenantManager 租户管理器
     */
    public WorkspaceService(TenantManager tenantManager) {
        this(new FileWorkspaceRepository(Constants.getHermesHome().resolve("business/workspaces")), tenantManager);
    }

    /**
     * 指定持久化根目录的构造函数。
     *
     * @param rootDir       工作空间存储的根目录
     * @param tenantManager 租户管理器
     */
    public WorkspaceService(Path rootDir, TenantManager tenantManager) {
        this(new FileWorkspaceRepository(rootDir), tenantManager);
    }

    /**
     * 完整构造函数，便于测试注入 Repository。
     *
     * @param repository    工作空间持久化仓库
     * @param tenantManager 租户管理器
     */
    public WorkspaceService(FileWorkspaceRepository repository, TenantManager tenantManager) {
        this.repository = repository;
        this.tenantManager = tenantManager;
    }

    /** 创建新工作空间 — B端租户隔离的基本单元。 */
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

    /** 列出所有工作空间。 */
    public List<WorkspaceRecord> listWorkspaces() {
        return repository.list();
    }

    /**
     * 按 ID 查询工作空间。
     *
     * @param workspaceId 工作空间 ID
     * @return 工作空间记录（可能为空）
     */
    public Optional<WorkspaceRecord> getWorkspace(String workspaceId) {
        return repository.findById(workspaceId);
    }

    /** 获取工作空间，不存在时抛出异常。 */
    public WorkspaceRecord requireWorkspace(String workspaceId) {
        return getWorkspace(workspaceId).orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    /**
     * 判断工作空间是否存在。
     *
     * @param workspaceId 工作空间 ID
     * @return true 表示存在
     */
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

    /** 校验 ID 格式 — 2-64 位，仅允许字母、数字、点、下划线、横线。 */
    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    /** 工作空间已存在异常 — 创建时重复调用抛出。 */
    public static class WorkspaceAlreadyExistsException extends RuntimeException {
        public WorkspaceAlreadyExistsException(String workspaceId) { super("Workspace already exists: " + workspaceId); }
    }

    /** 工作空间不存在异常 — 用于统一 404 语义。 */
    public static class WorkspaceNotFoundException extends RuntimeException {
        public WorkspaceNotFoundException(String workspaceId) { super("Workspace not found: " + workspaceId); }
    }
}
