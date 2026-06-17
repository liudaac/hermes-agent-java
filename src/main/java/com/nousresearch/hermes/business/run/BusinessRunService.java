package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.config.Constants;
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

    public BusinessRunService(WorkspaceService workspaceService) {
        this(new FileBusinessRunRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService);
    }

    public BusinessRunService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileBusinessRunRepository(workspacesRoot), workspaceService);
    }

    public BusinessRunService(FileBusinessRunRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    public BusinessRunRecord createRun(String workspaceId, String teamId, String scenario, String scenarioId, String taskTitle,
                                       String taskInput, String resultSummary, String conclusionReason,
                                       String systemAction, String riskJudgement, String nextSuggestion,
                                       String status, String technicalTraceRef, List<BusinessRunStep> steps,
                                       Map<String, Object> metrics, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        Instant now = Instant.now();
        BusinessRunRecord record = new BusinessRunRecord()
            .setRunId("run-" + UUID.randomUUID().toString().substring(0, 10))
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
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
            .setMetrics(metrics)
            .setMetadata(normalizeMetadataSource(metadata, technicalTraceRef))
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
            case COMPLETED, FAILED, NEEDS_APPROVAL, RUNNING -> normalized;
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

    public static class BusinessRunNotFoundException extends RuntimeException {
        public BusinessRunNotFoundException(String workspaceId, String runId) { super("Business run not found: " + workspaceId + "/" + runId); }
    }
}
