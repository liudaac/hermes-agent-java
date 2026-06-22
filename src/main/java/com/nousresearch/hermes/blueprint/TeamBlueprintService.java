package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.prompt.PromptAssetService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 团队蓝图服务 — 管理版本化的智能体团队配置。
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建团队蓝图（含成员、Prompt 资产引用、运营手册）</li>
 *   <li>创建 Draft 版本 — 支持迭代优化后激活</li>
 *   <li>版本激活 — 切换 ACTIVE 版本，旧版本置为 INACTIVE</li>
 *   <li>校验 Prompt 资产引用存在性</li>
 * </ul>
 * <p>团队蓝图按 workspace 隔离，支持多版本管理。</p>
 */
public class TeamBlueprintService {
    /** 团队蓝图持久化仓库 */
    private final FileTeamBlueprintRepository repository;
    /** 工作空间服务 — 校验 workspace 存在性 */
    private final WorkspaceService workspaceService;
    /** Prompt 资产服务 — 校验 promptAssetRefs 引用的资产存在性 */
    private final PromptAssetService promptAssetService;

    /**
     * 默认构造函数 — 使用 ~/.hermes/business/workspaces 作为持久化根目录。
     *
     * @param workspaceService 工作空间服务
     */
    public TeamBlueprintService(WorkspaceService workspaceService) {
        this(new FileTeamBlueprintRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService,
            new PromptAssetService(workspaceService));
    }

    /**
     * 指定持久化根目录的构造函数。
     *
     * @param workspacesRoot   团队蓝图存储的根目录
     * @param workspaceService 工作空间服务
     */
    public TeamBlueprintService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileTeamBlueprintRepository(workspacesRoot), workspaceService,
            new PromptAssetService(workspacesRoot, workspaceService));
    }

    /**
     * 注入 Repository 的构造函数（便于测试）。
     *
     * @param repository       团队蓝图持久化仓库
     * @param workspaceService 工作空间服务
     */
    public TeamBlueprintService(FileTeamBlueprintRepository repository, WorkspaceService workspaceService) {
        this(repository, workspaceService, new PromptAssetService(workspaceService));
    }

    /**
     * 完整构造函数，便于测试注入所有依赖。
     *
     * @param repository        团队蓝图持久化仓库
     * @param workspaceService  工作空间服务
     * @param promptAssetService Prompt 资产服务
     */
    public TeamBlueprintService(FileTeamBlueprintRepository repository, WorkspaceService workspaceService, PromptAssetService promptAssetService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.promptAssetService = promptAssetService;
    }

    /** 创建团队蓝图 — 定义团队的成员、能力和运营手册。 */
    public TeamBlueprintRecord createTeamBlueprint(String workspaceId, String teamId, String name, String description,
                                                   String scenario, String scenarioId, List<AgentBlueprintRecord> agents,
                                                   List<String> promptAssetRefs, String operatingManual,
                                                   Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        validatePromptAssetRefs(workspaceId, promptAssetRefs);
        validateId(teamId, "teamId");
        if (repository.exists(workspaceId, teamId)) {
            throw new TeamBlueprintAlreadyExistsException(workspaceId, teamId);
        }

        Instant now = Instant.now();
        TeamBlueprintVersion v1 = new TeamBlueprintVersion()
            .setVersion(1)
            .setStatus("ACTIVE")
            .setChangeSummary("Initial business team blueprint")
            .setAgents(agents)
            .setPromptAssetRefs(promptAssetRefs)
            .setOperatingManual(operatingManual)
            .setCreatedAt(now)
            .setActivatedAt(now);

        TeamBlueprintRecord record = new TeamBlueprintRecord()
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setName(name != null && !name.isBlank() ? name : teamId)
            .setDescription(description)
            .setScenario(scenario)
            .setScenarioId(scenarioId)
            .setActiveVersion(1)
            .setVersions(List.of(v1))
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    /** 创建DraftVersion。 */
    public TeamBlueprintVersion createDraftVersion(String workspaceId, String teamId, String changeSummary,
                                                   List<AgentBlueprintRecord> agents,
                                                   List<String> promptAssetRefs,
                                                   String operatingManual,
                                                   Map<String, Object> metadata) {
        TeamBlueprintRecord record = requireTeamBlueprint(workspaceId, teamId);
        validatePromptAssetRefs(workspaceId, promptAssetRefs);
        int nextVersion = record.getVersions().stream().mapToInt(TeamBlueprintVersion::getVersion).max().orElse(0) + 1;
        TeamBlueprintVersion version = new TeamBlueprintVersion()
            .setVersion(nextVersion)
            .setStatus("DRAFT")
            .setChangeSummary(changeSummary != null && !changeSummary.isBlank() ? changeSummary : "Draft update")
            .setAgents(agents)
            .setPromptAssetRefs(promptAssetRefs)
            .setOperatingManual(operatingManual)
            .setMetadata(metadata)
            .setCreatedAt(Instant.now());
        record.getVersions().add(version);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return version;
    }

    /**
     * 激活指定版本 — 将该版本置为 ACTIVE，原 ACTIVE 版本置为 INACTIVE。
     *
     * @param workspaceId   工作空间 ID
     * @param teamId        团队 ID
     * @param versionNumber 要激活的版本号
     * @return 更新后的团队蓝图记录
     * @throws TeamBlueprintVersionNotFoundException 版本不存在时抛出
     */
    public TeamBlueprintRecord activateVersion(String workspaceId, String teamId, int versionNumber) {
        TeamBlueprintRecord record = requireTeamBlueprint(workspaceId, teamId);
        TeamBlueprintVersion target = record.getVersions().stream()
            .filter(version -> version.getVersion() == versionNumber)
            .findFirst()
            .orElseThrow(() -> new TeamBlueprintVersionNotFoundException(workspaceId, teamId, versionNumber));

        Instant now = Instant.now();
        for (TeamBlueprintVersion version : record.getVersions()) {
            if (version.getVersion() == versionNumber) {
                version.setStatus("ACTIVE");
                version.setActivatedAt(now);
            } else if ("ACTIVE".equals(version.getStatus())) {
                version.setStatus("INACTIVE");
            }
        }
        record.setActiveVersion(target.getVersion());
        record.setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    /** 列出工作空间下的所有团队蓝图。 */
    public List<TeamBlueprintRecord> listTeamBlueprints(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.list(workspaceId);
    }

    /**
     * 按 ID 查询团队蓝图。
     *
     * @param workspaceId 工作空间 ID
     * @param teamId      团队 ID
     * @return 团队蓝图记录（可能为空）
     */
    public Optional<TeamBlueprintRecord> getTeamBlueprint(String workspaceId, String teamId) {
        return repository.findById(workspaceId, teamId);
    }

    /** 获取团队蓝图，不存在时抛出异常。 */
    public TeamBlueprintRecord requireTeamBlueprint(String workspaceId, String teamId) {
        workspaceService.requireWorkspace(workspaceId);
        return getTeamBlueprint(workspaceId, teamId)
            .orElseThrow(() -> new TeamBlueprintNotFoundException(workspaceId, teamId));
    }

    /** 校验 Prompt 资产引用列表 — 确保引用的资产（及指定版本）存在。 */
    private void validatePromptAssetRefs(String workspaceId, List<String> promptAssetRefs) {
        if (promptAssetRefs == null || promptAssetRefs.isEmpty()) {
            return;
        }
        for (String ref : promptAssetRefs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            PromptAssetRef promptRef = parsePromptAssetRef(ref);
            if (promptRef == null) {
                throw new IllegalArgumentException("Unsupported prompt asset ref: " + ref + ". Expected format: prompt://{assetId} or prompt://{assetId}#v{version}");
            }
            if (promptRef.version() != null) {
                promptAssetService.requireVersion(workspaceId, promptRef.assetId(), promptRef.version());
            } else {
                promptAssetService.requirePromptAsset(workspaceId, promptRef.assetId());
            }
        }
    }

    /**
     * 解析 Prompt 资产引用字符串。
     * <p>支持格式：<code>prompt://{assetId}</code> 或 <code>prompt://{assetId}#v{version}</code></p>
     *
     * @param ref 引用字符串
     * @return 解析结果（格式不合法时返回 null）
     */
    private static PromptAssetRef parsePromptAssetRef(String ref) {
        String prefix = "prompt://";
        if (!ref.startsWith(prefix)) {
            return null;
        }
        String value = ref.substring(prefix.length()).trim();
        if (value.isBlank() || value.contains("/")) {
            return null;
        }
        String assetId = value;
        Integer version = null;
        int versionSeparator = value.indexOf("#v");
        if (versionSeparator >= 0) {
            assetId = value.substring(0, versionSeparator);
            String versionText = value.substring(versionSeparator + 2);
            if (assetId.isBlank() || versionText.isBlank() || versionText.contains("#")) {
                return null;
            }
            try {
                version = Integer.parseInt(versionText);
            } catch (NumberFormatException e) {
                return null;
            }
            if (version <= 0) {
                return null;
            }
        } else if (value.contains("#")) {
            return null;
        }
        return new PromptAssetRef(assetId, version);
    }

    /** Prompt 资产引用解析结果 — 资产 ID 和可选版本号。 */
    private record PromptAssetRef(String assetId, Integer version) {}

    /** 校验 ID 格式 — 2-64 位，仅允许字母、数字、点、下划线、横线。 */
    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    /** 团队蓝图已存在异常 — 创建时重复调用抛出。 */
    public static class TeamBlueprintAlreadyExistsException extends RuntimeException {
        public TeamBlueprintAlreadyExistsException(String workspaceId, String teamId) { super("Team blueprint already exists: " + workspaceId + "/" + teamId); }
    }

    /** 团队蓝图不存在异常 — 用于统一 404 语义。 */
    public static class TeamBlueprintNotFoundException extends RuntimeException {
        public TeamBlueprintNotFoundException(String workspaceId, String teamId) { super("Team blueprint not found: " + workspaceId + "/" + teamId); }
    }

    /** 团队蓝图版本不存在异常 — 激活或查询不存在的版本时抛出。 */
    public static class TeamBlueprintVersionNotFoundException extends RuntimeException {
        public TeamBlueprintVersionNotFoundException(String workspaceId, String teamId, int version) { super("Team blueprint version not found: " + workspaceId + "/" + teamId + "#" + version); }
    }
}
