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

/** Business-facing run story service. */
public class BusinessRunService {
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String NEEDS_APPROVAL = "NEEDS_APPROVAL";
    public static final String RUNNING = "RUNNING";
    public static final String TRIAL = "TRIAL";

    /** Allowed metadata.source values for BusinessRunRecord. */
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_DEMO = "demo";
    public static final String SOURCE_SMOKE = "smoke";
    public static final String SOURCE_FOUNDATION_INTENT_RUN = "foundation:intent-run";
    public static final String SOURCE_FOUNDATION_AGENT_TRACE = "foundation:agent-trace";
    private static final Set<String> KNOWN_SOURCES = Set.of(
        SOURCE_MANUAL,
        SOURCE_DEMO,
        SOURCE_SMOKE,
        SOURCE_FOUNDATION_INTENT_RUN,
        SOURCE_FOUNDATION_AGENT_TRACE
    );

    private final FileBusinessRunRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;
    private final ScenarioService scenarioService;
    private final RunEventBus eventBus = new RunEventBus();

    public BusinessRunService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, ScenarioService scenarioService) {
        this(new FileBusinessRunRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService, scenarioService);
    }

    public BusinessRunService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, ScenarioService scenarioService) {
        this(new FileBusinessRunRepository(workspacesRoot), workspaceService, teamBlueprintService, scenarioService);
    }

    public BusinessRunService(FileBusinessRunRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, ScenarioService scenarioService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
        this.scenarioService = scenarioService;
    }

    /** Backward-compatible createRun without cost fields. */
    public BusinessRunRecord createRun(String workspaceId, String teamId, String scenario, String scenarioId, String taskTitle,
                                       String taskInput, String resultSummary, String conclusionReason,
                                       String systemAction, String riskJudgement, String nextSuggestion,
                                       String status, String technicalTraceRef, List<BusinessRunStep> steps,
                                       Map<String, Object> metrics, Map<String, Object> metadata) {
        return createRun(workspaceId, teamId, scenario, scenarioId, taskTitle, taskInput, resultSummary, conclusionReason,
            systemAction, riskJudgement, nextSuggestion, status, technicalTraceRef, steps, 0L, 0.0, metrics, metadata);
    }

    public BusinessRunRecord createRun(String workspaceId, String teamId, String scenario, String scenarioId, String taskTitle,
                                       String taskInput, String resultSummary, String conclusionReason,
                                       String systemAction, String riskJudgement, String nextSuggestion,
                                       String status, String technicalTraceRef, List<BusinessRunStep> steps,
                                       long tokensUsed, double estimatedCost,
                                       Map<String, Object> metrics, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        String teamVersion = null;
        if (teamId != null && !teamId.isBlank()) {
            teamVersion = String.valueOf(teamBlueprintService.requireTeamBlueprint(workspaceId, teamId).getActiveVersion());
        }
        if (scenarioId != null && !scenarioId.isBlank()) {
            scenarioService.requireScenario(workspaceId, scenarioId);
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

    public List<BusinessRunRecord> listRuns(String workspaceId, String teamId, String status, int limit) {
        return listRuns(workspaceId, teamId, null, status, limit);
    }

    public List<BusinessRunRecord> listRuns(String workspaceId, String teamId, String scenarioId, String status, int limit) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceService.requireWorkspace(workspaceId);
            return repository.list(workspaceId, teamId, scenarioId, normalizeStatusFilter(status), limit);
        }
        List<String> workspaceIds = workspaceService.listWorkspaces().stream().map(WorkspaceRecord::getWorkspaceId).toList();
        return repository.listAll(workspaceIds, teamId, scenarioId, normalizeStatusFilter(status), limit);
    }

    public BusinessRunRecord requireRun(String workspaceId, String runId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.findById(workspaceId, runId)
            .orElseThrow(() -> new BusinessRunNotFoundException(workspaceId, runId));
    }

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

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return status.trim().toUpperCase();
    }

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

    public static class BusinessRunNotFoundException extends RuntimeException {
        public BusinessRunNotFoundException(String workspaceId, String runId) { super("Business run not found: " + workspaceId + "/" + runId); }
    }
}
