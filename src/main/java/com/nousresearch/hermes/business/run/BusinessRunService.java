package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 业务运行服务 — 管理面向 B 端用户的完整业务执行生命周期。
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建运行记录（手动创建、试运行、从 IntentRun 投影）</li>
 *   <li>更新运行状态（完成、失败、需审批）</li>
 *   <li>持久化到文件系统（JSON 格式）</li>
 *   <li>发布状态变更事件（供 SSE 实时推送）</li>
 * </ul>
 * <p>所有运行记录按 workspace 隔离，存储在 <code>~/.hermes/business/workspaces/{workspaceId}/runs/</code>。</p>
 */
public class BusinessRunService {
    // ---- 运行状态常量 ----
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String NEEDS_APPROVAL = "NEEDS_APPROVAL";
    public static final String RUNNING = "RUNNING";
    public static final String TRIAL = "TRIAL";

    /** 元数据来源枚举 — 用于追溯运行记录的创建方式 */
    public static final String SOURCE_MANUAL = "manual";              // 用户手动创建
    public static final String SOURCE_DEMO = "demo";                  // 演示数据
    public static final String SOURCE_SMOKE = "smoke";                // 冒烟测试
    public static final String SOURCE_FOUNDATION_INTENT_RUN = "foundation:intent-run"; // 底层意图运行投影
    public static final String SOURCE_FOUNDATION_AGENT_TRACE = "foundation:agent-trace"; // Agent 追踪投影
    private static final Set<String> KNOWN_SOURCES = Set.of(
        SOURCE_MANUAL, SOURCE_DEMO, SOURCE_SMOKE,
        SOURCE_FOUNDATION_INTENT_RUN, SOURCE_FOUNDATION_AGENT_TRACE
    );

    private final FileBusinessRunRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;
    private final ScenarioService scenarioService;
    /** 运行事件总线 — 用于向 SSE 等下游消费者推送状态变更 */
    private final RunEventBus eventBus = new RunEventBus();

    /**
     * 默认构造函数 — 使用 ~/.hermes/business/workspaces 作为持久化根目录。
     *
     * @param workspaceService     工作空间服务，用于校验 workspace 存在性
     * @param teamBlueprintService 团队蓝图服务，用于解析团队版本
     * @param scenarioService      场景服务，用于校验场景及获取协作模式
     */
    public BusinessRunService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, ScenarioService scenarioService) {
        this(new FileBusinessRunRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService, scenarioService);
    }

    /**
     * 指定持久化根目录的构造函数。
     *
     * @param workspacesRoot       运行记录存储的根目录
     * @param workspaceService     工作空间服务
     * @param teamBlueprintService 团队蓝图服务
     * @param scenarioService      场景服务
     */
    public BusinessRunService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, ScenarioService scenarioService) {
        this(new FileBusinessRunRepository(workspacesRoot), workspaceService, teamBlueprintService, scenarioService);
    }

    /**
     * 完整构造函数，便于测试注入 Repository。
     *
     * @param repository           运行记录持久化仓库
     * @param workspaceService     工作空间服务
     * @param teamBlueprintService 团队蓝图服务
     * @param scenarioService      场景服务
     */
    public BusinessRunService(FileBusinessRunRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, ScenarioService scenarioService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
        this.scenarioService = scenarioService;
    }

    /**
     * 创建运行记录（向后兼容版本，不含成本字段）。
     * <p>内部委托到含 tokensUsed / estimatedCost 的完整版本，默认成本为 0。</p>
     *
     * @see #createRun(String, String, String, String, String, String, String, String, String, String, String, String, String, List, long, double, Map, Map)
     */
    public BusinessRunRecord createRun(String workspaceId, String teamId, String scenario, String scenarioId, String taskTitle,
                                       String taskInput, String resultSummary, String conclusionReason,
                                       String systemAction, String riskJudgement, String nextSuggestion,
                                       String status, String technicalTraceRef, List<BusinessRunStep> steps,
                                       Map<String, Object> metrics, Map<String, Object> metadata) {
        return createRun(workspaceId, teamId, scenario, scenarioId, taskTitle, taskInput, resultSummary, conclusionReason,
            systemAction, riskJudgement, nextSuggestion, status, technicalTraceRef, steps, 0L, 0.0, metrics, metadata);
    }

    /**
     * 创建运行记录 — 核心业务入口。
     * <p>会自动校验 workspace、团队蓝图、场景存在性；解析团队版本和协作模式；
     * 标准化步骤和元数据来源；最终持久化并返回记录。</p>
     *
     * @param workspaceId        工作空间 ID
     * @param teamId             团队 ID（可为空）
     * @param scenario           场景名称（可为空）
     * @param scenarioId         场景 ID（可为空，不为空时会校验存在性）
     * @param taskTitle          任务标题
     * @param taskInput          任务输入内容
     * @param resultSummary      结果摘要
     * @param conclusionReason   结论原因
     * @param systemAction       系统建议动作
     * @param riskJudgement      风险评估
     * @param nextSuggestion     下一步建议
     * @param status             运行状态（为空时默认 COMPLETED）
     * @param technicalTraceRef  技术追踪引用（如 intent:// 或 trace:// 开头）
     * @param steps              运行步骤列表
     * @param tokensUsed         消耗的 Token 数量
     * @param estimatedCost      预估成本
     * @param metrics            业务指标
     * @param metadata           元数据（会自动注入 source 字段）
     * @return 创建后的运行记录
     */
    public BusinessRunRecord createRun(String workspaceId, String teamId, String scenario, String scenarioId, String taskTitle,
                                       String taskInput, String resultSummary, String conclusionReason,
                                       String systemAction, String riskJudgement, String nextSuggestion,
                                       String status, String technicalTraceRef, List<BusinessRunStep> steps,
                                       long tokensUsed, double estimatedCost,
                                       Map<String, Object> metrics, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        String teamVersion = null;
        if (teamId != null && !teamId.isBlank()) {
            // Allow caller to override team version via metadata.teamVersion (e.g. canary routing)
            String overrideVersion = metadata != null && metadata.get("teamVersion") != null
                ? String.valueOf(metadata.get("teamVersion"))
                : null;
            teamVersion = overrideVersion != null
                ? overrideVersion
                : String.valueOf(teamBlueprintService.requireTeamBlueprint(workspaceId, teamId).getActiveVersion());
        }
        if (scenarioId != null && !scenarioId.isBlank()) {
            scenarioService.requireScenario(workspaceId, scenarioId);
        }
        // Resolve collaboration pattern and SLA from scenario if available
        String collaborationPattern = null;
        String slaName = null;
        if (scenarioId != null && !scenarioId.isBlank()) {
            var scenarioOpt = scenarioService.getScenario(workspaceId, scenarioId);
            if (scenarioOpt.isPresent()) {
                var sc = scenarioOpt.get();
                collaborationPattern = sc.getCollaborationPattern() != null ? sc.getCollaborationPattern().name() : null;
                slaName = sc.getSlaName();
            }
        }
        Instant now = Instant.now();
        BusinessRunRecord record = new BusinessRunRecord()
            .setRunId("run-" + UUID.randomUUID().toString().substring(0, 10))
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setTeamVersion(teamVersion)
            .setScenario(scenario)
            .setScenarioId(scenarioId)
            .setTaskTitle(defaultText(taskTitle, "业务任务"))
            .setTaskInput(taskInput)
            .setResultSummary(defaultText(resultSummary, "任务已记录，等待进一步处理。"))
            .setConclusionReason(conclusionReason)
            .setSystemAction(systemAction)
            .setRiskJudgement(riskJudgement)
            .setNextSuggestion(nextSuggestion)
            .setStatus(normalizeStatus(status))
            .setTechnicalTraceRef(technicalTraceRef)
            .setCollaborationPattern(collaborationPattern)
            .setSlaName(slaName)
            .setSlaStatus("healthy")
            .setSteps(normalizeSteps(steps))
            .setTokensUsed(tokensUsed)
            .setEstimatedCost(estimatedCost)
            .setMetrics(metrics)
            .setMetadata(normalizeMetadataSource(metadata, technicalTraceRef))
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    /**
     * Create a trial run for a scenario. Validates the scenario and its entry team,
     * records the active team version, and produces a business story with Execute-Verify-Report skeleton.
     */
    public BusinessRunRecord createTrialRun(String workspaceId, String scenarioId, String taskInput) {
        var scenario = scenarioService.requireScenario(workspaceId, scenarioId);
        String teamId = scenario.getEntryTeamId();
        String teamVersion = null;
        if (teamId != null && !teamId.isBlank()) {
            teamVersion = String.valueOf(teamBlueprintService.requireTeamBlueprint(workspaceId, teamId).getActiveVersion());
        }
        Instant now = Instant.now();
        List<BusinessRunStep> steps = List.of(
            new BusinessRunStep().setStepId("step-1").setTitle("执行").setSummary("接收任务输入并解析").setActor(teamId != null ? teamId : "system").setStatus("COMPLETED").setTimestamp(now),
            new BusinessRunStep().setStepId("step-2").setTitle("验证").setSummary("对照场景成功标准进行初步校验").setActor("validator").setStatus("COMPLETED").setTimestamp(now),
            new BusinessRunStep().setStepId("step-3").setTitle("汇报").setSummary("生成试运行报告").setActor("reporter").setStatus("COMPLETED").setTimestamp(now)
        );
        BusinessRunRecord record = new BusinessRunRecord()
            .setRunId("run-" + UUID.randomUUID().toString().substring(0, 10))
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setTeamVersion(teamVersion)
            .setScenario(scenario.getName())
            .setScenarioId(scenarioId)
            .setCollaborationPattern(scenario.getCollaborationPattern() != null ? scenario.getCollaborationPattern().name() : null)
            .setSlaName(scenario.getSlaName())
            .setSlaStatus("healthy")
            .setTaskTitle(scenario.getName() + " 试运行")
            .setTaskInput(taskInput)
            .setResultSummary("试运行完成。任务已按场景配置进入执行-验证-汇报流程。")
            .setConclusionReason("试运行模式下未调用真实 Agent Runtime，仅验证场景-团队绑定关系。")
            .setSystemAction("记录试运行结果并等待业务方确认")
            .setRiskJudgement("低风险 — 试运行未产生外部副作用")
            .setNextSuggestion("检查团队配置和审批规则后，可转为正式运行或调整蓝图版本。")
            .setStatus(TRIAL)
            .setSteps(steps)
            .setMetadata(Map.of("source", SOURCE_MANUAL, "trial", true))
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    /**
     * 列出运行记录（按 workspace + team + status 过滤）。
     *
     * @param workspaceId 工作空间 ID（为空时跨所有 workspace 查询）
     * @param teamId      团队 ID（可为空）
     * @param status      状态过滤（ALL 或空表示全部）
     * @param limit       最大返回条数
     * @return 运行记录列表
     */
    public List<BusinessRunRecord> listRuns(String workspaceId, String teamId, String status, int limit) {
        return listRuns(workspaceId, teamId, null, status, limit);
    }

    /**
     * 列出运行记录（支持按场景过滤）。
     *
     * @param workspaceId 工作空间 ID（为空时跨所有 workspace 查询）
     * @param teamId      团队 ID（可为空）
     * @param scenarioId  场景 ID（可为空）
     * @param status      状态过滤（ALL 或空表示全部）
     * @param limit       最大返回条数
     * @return 运行记录列表
     */
    public List<BusinessRunRecord> listRuns(String workspaceId, String teamId, String scenarioId, String status, int limit) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceService.requireWorkspace(workspaceId);
            return repository.list(workspaceId, teamId, scenarioId, normalizeStatusFilter(status), limit);
        }
        List<String> workspaceIds = workspaceService.listWorkspaces().stream().map(WorkspaceRecord::getWorkspaceId).toList();
        return repository.listAll(workspaceIds, teamId, scenarioId, normalizeStatusFilter(status), limit);
    }

    /**
     * 按 ID 获取运行记录，不存在时抛出异常。
     *
     * @param workspaceId 工作空间 ID
     * @param runId       运行记录 ID
     * @return 运行记录
     * @throws BusinessRunNotFoundException 记录不存在时抛出
     */
    public BusinessRunRecord requireRun(String workspaceId, String runId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.findById(workspaceId, runId)
            .orElseThrow(() -> new BusinessRunNotFoundException(workspaceId, runId));
    }

    /** Update/save a run record directly (for cross-reference updates). */
    public BusinessRunRecord updateRun(BusinessRunRecord record) {
        if (record == null || record.getRunId() == null) {
            throw new IllegalArgumentException("Run record and runId are required");
        }
        workspaceService.requireWorkspace(record.getWorkspaceId());
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
    }

    /** 标准化步骤列表 — 自动补全 stepId、timestamp 和 status。 */
    private static List<BusinessRunStep> normalizeSteps(List<BusinessRunStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        for (int i = 0; i < steps.size(); i++) {
            BusinessRunStep step = steps.get(i);
            if (step.getStepId() == null || step.getStepId().isBlank()) {
                step.setStepId("step-" + (i + 1));
            }
            if (step.getTimestamp() == null) {
                step.setTimestamp(now);
            }
            if (step.getStatus() == null || step.getStatus().isBlank()) {
                step.setStatus("COMPLETED");
            }
        }
        return steps;
    }

    /** 将输入状态标准化为已知状态常量，未知值回退到 COMPLETED。 */
    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return COMPLETED;
        }
        String normalized = status.trim().toUpperCase();
        return switch (normalized) {
            case COMPLETED, FAILED, NEEDS_APPROVAL, RUNNING, TRIAL -> normalized;
            default -> COMPLETED;
        };
    }

    /** 将前端传入的状态过滤值标准化；ALL 或空值表示不过滤。 */
    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return status.trim().toUpperCase();
    }

    /** 当值为空时返回兜底文本。 */
    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Tag every BusinessRunRecord with an explicit metadata.source so that downstream
     * consumers (UI, audit) never confuse foundation projections with manual/demo entries.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>If the caller provided a known source value, keep it.</li>
     *   <li>Otherwise, infer foundation-* sources from the technicalTraceRef shape.</li>
     *   <li>Otherwise default to "manual" so manually created entries are never silently
     *       presented as foundation truth.</li>
     * </ul>
     */
    static Map<String, Object> normalizeMetadataSource(Map<String, Object> metadata, String technicalTraceRef) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (metadata != null) {
            normalized.putAll(metadata);
        }
        Object existing = normalized.get("source");
        String existingSource = existing != null ? existing.toString() : null;
        if (existingSource != null && KNOWN_SOURCES.contains(existingSource)) {
            return normalized;
        }
        String inferred = inferSource(technicalTraceRef);
        if (existingSource != null && !KNOWN_SOURCES.contains(existingSource)) {
            normalized.put("originalSource", existingSource);
        }
        normalized.put("source", inferred);
        return normalized;
    }

    /** 根据 technicalTraceRef 的格式推断元数据来源。 */
    private static String inferSource(String technicalTraceRef) {
        if (technicalTraceRef == null || technicalTraceRef.isBlank()) {
            return SOURCE_MANUAL;
        }
        String ref = technicalTraceRef.toLowerCase();
        if (ref.startsWith("intent://")) {
            return SOURCE_FOUNDATION_INTENT_RUN;
        }
        if (ref.startsWith("trace://")) {
            return SOURCE_FOUNDATION_AGENT_TRACE;
        }
        return SOURCE_MANUAL;
    }

    // ===== Event Bus =====

    /** 获取运行事件总线实例，用于外部订阅状态变更。 */
    public RunEventBus getEventBus() {
        return eventBus;
    }

    /** Publish a run event and optionally update the persisted run record. */
    public void publishEvent(RunEventBus.RunEvent event) {
        eventBus.publish(event);
    }

    /** Update a run record and publish an update event. */
    public BusinessRunRecord updateRunStatus(String workspaceId, String runId, String newStatus, String message) {
        BusinessRunRecord run = requireRun(workspaceId, runId);
        run.setStatus(newStatus);
        run.setUpdatedAt(Instant.now());
        repository.save(run);
        RunEventBus.EventType eventType = switch (newStatus) {
            case COMPLETED -> RunEventBus.EventType.RUN_COMPLETED;
            case FAILED -> RunEventBus.EventType.RUN_FAILED;
            case NEEDS_APPROVAL -> RunEventBus.EventType.RUN_NEEDS_APPROVAL;
            default -> RunEventBus.EventType.STEP_UPDATED;
        };
        eventBus.publish(new RunEventBus.RunEvent(runId, workspaceId, eventType, message,
            Map.of("status", newStatus)));
        return run;
    }

    /** Add/update a step in the run and publish an event. */
    public void addRunStep(String workspaceId, String runId, BusinessRunStep step) {
        BusinessRunRecord run = requireRun(workspaceId, runId);
        List<BusinessRunStep> steps = new java.util.ArrayList<>(run.getSteps());
        steps.add(step);
        run.setSteps(steps);
        run.setUpdatedAt(Instant.now());
        repository.save(run);
        eventBus.publish(new RunEventBus.RunEvent(runId, workspaceId,
            "COMPLETED".equals(step.getStatus()) ? RunEventBus.EventType.STEP_COMPLETED
            : "FAILED".equals(step.getStatus()) ? RunEventBus.EventType.STEP_FAILED
            : "RUNNING".equals(step.getStatus()) ? RunEventBus.EventType.STEP_STARTED
            : RunEventBus.EventType.STEP_UPDATED,
            step.getTitle(), Map.of("step", step.toMap())));
    }

    /** 运行记录不存在异常 — 用于统一 404 语义。 */
    public static class BusinessRunNotFoundException extends RuntimeException {
        public BusinessRunNotFoundException(String workspaceId, String runId) { super("Business run not found: " + workspaceId + "/" + runId); }
    }
}
