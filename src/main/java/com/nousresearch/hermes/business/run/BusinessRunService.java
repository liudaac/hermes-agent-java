package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business-facing run story service. */
public class BusinessRunService {
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String NEEDS_APPROVAL = "NEEDS_APPROVAL";
    public static final String RUNNING = "RUNNING";

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

    public BusinessRunRecord createRun(String workspaceId, String teamId, String scenario, String taskTitle,
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
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public List<BusinessRunRecord> listRuns(String workspaceId, String teamId, String status, int limit) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceService.requireWorkspace(workspaceId);
            return repository.list(workspaceId, teamId, normalizeStatusFilter(status), limit);
        }
        List<String> workspaceIds = workspaceService.listWorkspaces().stream().map(WorkspaceRecord::getWorkspaceId).toList();
        return repository.listAll(workspaceIds, teamId, normalizeStatusFilter(status), limit);
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

    public static class BusinessRunNotFoundException extends RuntimeException {
        public BusinessRunNotFoundException(String workspaceId, String runId) { super("Business run not found: " + workspaceId + "/" + runId); }
    }
}
