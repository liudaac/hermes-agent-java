package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.blueprint.TeamBlueprintRuntime;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.run.BusinessRunStep;
import com.nousresearch.hermes.business.run.RunEventBus;
import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.memory.ActiveMemoryService;
import com.nousresearch.hermes.policy.PolicyService;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Business service for scenarios. */
public class ScenarioService {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioService.class);
    private final FileScenarioRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;
    private final TeamBlueprintRuntime teamBlueprintRuntime;
    private ScenarioIntentAdapter scenarioIntentAdapter;
    private PolicyService policyService;
    private BusinessApprovalService businessApprovalService;
    private ActiveMemoryService activeMemoryService;

    public ScenarioService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileScenarioRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService);
    }

    public ScenarioService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileScenarioRepository(workspacesRoot), workspaceService, teamBlueprintService);
    }

    public ScenarioService(FileScenarioRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
        this.teamBlueprintRuntime = new TeamBlueprintRuntime(workspaceService, teamBlueprintService);
    }

    /** Wire the intent adapter after construction (breaks circular dependency with TenantManager). */
    public void setScenarioIntentAdapter(ScenarioIntentAdapter adapter) {
        this.scenarioIntentAdapter = adapter;
    }

    /** Wire policy and approval services for automatic approval gating. */
    public void setPolicyService(PolicyService policyService, BusinessApprovalService businessApprovalService) {
        this.policyService = policyService;
        this.businessApprovalService = businessApprovalService;
    }

    /** Wire active memory service for knowledge recall. */
    public void setActiveMemoryService(ActiveMemoryService activeMemoryService) {
        this.activeMemoryService = activeMemoryService;
    }

    public ScenarioRecord createScenario(String workspaceId, String scenarioId, String name, String description,
                                         String entryTeamId, List<String> successCriteria,
                                         List<String> approvalRules, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        validateId(scenarioId, "scenarioId");
        if (repository.exists(workspaceId, scenarioId)) {
            throw new ScenarioAlreadyExistsException(workspaceId, scenarioId);
        }
        if (entryTeamId != null && !entryTeamId.isBlank()) {
            teamBlueprintService.requireTeamBlueprint(workspaceId, entryTeamId);
        }
        Instant now = Instant.now();
        ScenarioRecord record = new ScenarioRecord()
            .setWorkspaceId(workspaceId)
            .setScenarioId(scenarioId)
            .setName(name != null && !name.isBlank() ? name : scenarioId)
            .setDescription(description)
            .setEntryTeamId(entryTeamId)
            .setStatus("ACTIVE")
            .setSuccessCriteria(successCriteria)
            .setApprovalRules(approvalRules)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public List<ScenarioRecord> listScenarios(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.list(workspaceId);
    }

    public Optional<ScenarioRecord> getScenario(String workspaceId, String scenarioId) {
        return repository.findById(workspaceId, scenarioId);
    }

    public ScenarioRecord requireScenario(String workspaceId, String scenarioId) {
        workspaceService.requireWorkspace(workspaceId);
        return getScenario(workspaceId, scenarioId).orElseThrow(() -> new ScenarioNotFoundException(workspaceId, scenarioId));
    }

    public BusinessRunRecord executeScenario(String workspaceId, String scenarioId, String userInput,
                                               BusinessRunService runService) {
        return executeScenario(workspaceId, scenarioId, userInput, runService, false);
    }

    /**
     * Execute a scenario through the IntentOrchestrator and project the result into a BusinessRunRecord.
     * Returns the business run story with real execution traces.
     *
     * <p>If policy service is wired and approval rules are triggered, creates an approval card
     * and throws {@link ApprovalRequiredException} instead of executing.</p>
     *
     * @param skipApprovalCheck if true, bypasses the approval gate (used for resume-execution after approval)
     */
    public BusinessRunRecord executeScenario(String workspaceId, String scenarioId, String userInput,
                                               BusinessRunService runService, boolean skipApprovalCheck) {
        if (scenarioIntentAdapter == null) {
            throw new IllegalStateException("ScenarioIntentAdapter not wired — cannot execute scenario");
        }
        ScenarioRecord scenario = requireScenario(workspaceId, scenarioId);

        // Ensure the team blueprint has running agent instances on the tenant bus
        String entryTeamId = scenario.getEntryTeamId();
        if (entryTeamId != null && !entryTeamId.isBlank()) {
            teamBlueprintRuntime.ensureTeamRuntime(workspaceId, entryTeamId);
        }

        // Approval gate: check if any agent's approval rules require human review
        if (!skipApprovalCheck && policyService != null && businessApprovalService != null) {
            var check = policyService.checkApprovalRequired(workspaceId, scenario.getEntryTeamId(), "execute", userInput);
            if (check.approvalNeeded()) {
                BusinessApprovalRecord approval = businessApprovalService.createApproval(
                    workspaceId,
                    scenario.getEntryTeamId(),
                    "Scenario execution requires approval",
                    "Action: execute scenario '" + scenario.getName() + "'\nInput: " + userInput,
                    "Please confirm this scenario execution is authorized.",
                    "Execution proceeds with full Agent Runtime.",
                    "Execution is blocked.",
                    "Review input for policy compliance.",
                    "MEDIUM",
                    Map.of("scenarioId", scenarioId, "agentId", check.agentId(), "matchedRule", check.matchedRule(), "userInput", userInput),
                    Map.of("source", "auto", "trigger", check.reason())
                );
                throw new ApprovalRequiredException(approval.getApprovalId(), check.reason());
            }
        }

        IntentOrchestrator.IntentRun intentRun = scenarioIntentAdapter.execute(scenario, userInput);
        BusinessRunProjectionAdapter projectionAdapter = new BusinessRunProjectionAdapter();
        BusinessRunRecord projection = projectionAdapter.fromIntentRun(
            workspaceId, scenarioId, scenario.getName(), intentRun);

        // Active Memory: recall relevant knowledge and attach to run metadata
        if (activeMemoryService != null) {
            var recalledMemories = activeMemoryService.recall(workspaceId, scenarioId, scenario.getSuccessCriteria(), userInput, 5);
            if (!recalledMemories.isEmpty()) {
                Map<String, Object> memoryMeta = new LinkedHashMap<>();
                memoryMeta.put("recalledCount", recalledMemories.size());
                memoryMeta.put("memories", recalledMemories.stream()
                    .map(m -> Map.of("memoryId", m.getMemoryId(), "title", m.getTitle(), "type", m.getType(), "content", m.getContent()))
                    .toList());
                Map<String, Object> mergedMeta = new LinkedHashMap<>(projection.getMetadata() != null ? projection.getMetadata() : Map.of());
                mergedMeta.put("activeMemory", memoryMeta);
                projection.setMetadata(mergedMeta);
            }
        }

        BusinessRunRecord persisted = projectionAdapter.persistProjection(runService, projection);

        // Stream: start a watcher that publishes incremental events as the run progresses
        startRunWatcher(workspaceId, persisted.getRunId(), intentRun, runService);

        return persisted;
    }

    /**
     * Watch an IntentRun in the background and publish business run events as it progresses.
     * Polls every 200ms until the run reaches a terminal state.
     */
    private void startRunWatcher(String workspaceId, String runId, IntentOrchestrator.IntentRun intentRun,
                                  BusinessRunService runService) {
        Thread t = new Thread(() -> {
            try {
                int lastAttemptCount = 0;
                int lastSuccessCount = 0;
                int lastFailureCount = 0;

                // Mark run as running
                runService.updateRunStatus(workspaceId, runId, BusinessRunService.RUNNING, "Run started");
                runService.getEventBus().publish(new RunEventBus.RunEvent(
                    runId, workspaceId, RunEventBus.EventType.RUN_STARTED,
                    "Run started", Map.of("intent", intentRun.intent)));

                while (intentRun.status != IntentOrchestrator.RunStatus.COMPLETED &&
                       intentRun.status != IntentOrchestrator.RunStatus.PARTIAL &&
                       intentRun.status != IntentOrchestrator.RunStatus.FAILED) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    // Check for new attempts (steps started)
                    int currentAttempts = intentRun.attempts.size();
                    if (currentAttempts > lastAttemptCount) {
                        for (int i = lastAttemptCount; i < currentAttempts; i++) {
                            var attempt = intentRun.attempts.get(i);
                            BusinessRunStep step = new BusinessRunStep()
                                .setStepId("attempt-" + (i + 1))
                                .setTitle(attempt.subtask() != null ? attempt.subtask() : "Task " + (i + 1))
                                .setSummary(attempt.agentId() + " is processing")
                                .setActor(attempt.roleName() != null && !attempt.roleName().isBlank() ? attempt.roleName() : attempt.agentId())
                                .setStatus("RUNNING");
                            runService.addRunStep(workspaceId, runId, step);
                        }
                        lastAttemptCount = currentAttempts;
                    }

                    // Check for new successes
                    int currentSuccesses = intentRun.successes.size();
                    if (currentSuccesses > lastSuccessCount) {
                        // Update steps that just completed
                        for (int i = 0; i < intentRun.attempts.size(); i++) {
                            var attempt = intentRun.attempts.get(i);
                            if (intentRun.successes.containsKey(attempt.subtask())) {
                                BusinessRunStep step = new BusinessRunStep()
                                    .setStepId("attempt-" + (i + 1))
                                    .setTitle(attempt.subtask() != null ? attempt.subtask() : "Task " + (i + 1))
                                    .setSummary(intentRun.successes.get(attempt.subtask()))
                                    .setActor(attempt.roleName() != null && !attempt.roleName().isBlank() ? attempt.roleName() : attempt.agentId())
                                    .setStatus("COMPLETED");
                                runService.getEventBus().publish(new RunEventBus.RunEvent(
                                    runId, workspaceId, RunEventBus.EventType.STEP_COMPLETED,
                                    attempt.subtask() + " completed", Map.of("step", step.toMap())));
                            }
                        }
                        lastSuccessCount = currentSuccesses;
                    }

                    // Check for new failures
                    int currentFailures = intentRun.failures.size();
                    if (currentFailures > lastFailureCount) {
                        for (var entry : intentRun.failures.entrySet()) {
                            BusinessRunStep step = new BusinessRunStep()
                                .setStepId("fail-" + entry.getKey())
                                .setTitle(entry.getKey())
                                .setSummary(entry.getValue())
                                .setStatus("FAILED");
                            runService.getEventBus().publish(new RunEventBus.RunEvent(
                                runId, workspaceId, RunEventBus.EventType.STEP_FAILED,
                                entry.getKey() + " failed", Map.of("step", step.toMap())));
                        }
                        lastFailureCount = currentFailures;
                    }
                }

                // Run finished — update final status
                String finalStatus = switch (intentRun.status) {
                    case COMPLETED -> BusinessRunService.COMPLETED;
                    case PARTIAL, FAILED -> BusinessRunService.FAILED;
                    default -> BusinessRunService.FAILED;
                };
                runService.updateRunStatus(workspaceId, runId, finalStatus,
                    "Run " + finalStatus.toLowerCase() +
                        " — " + intentRun.successes.size() + " succeeded, " +
                        intentRun.failures.size() + " failed");

            } catch (Exception e) {
                logger.warn("Run watcher error for {}: {}", runId, e.getMessage());
                try {
                    runService.updateRunStatus(workspaceId, runId, BusinessRunService.FAILED,
                        "Watcher error: " + e.getMessage());
                } catch (Exception ignored) {}
            }
        }, "run-watcher-" + runId.substring(0, Math.min(10, runId.length())));
        t.setDaemon(true);
        t.start();
    }

    public static class ApprovalRequiredException extends RuntimeException {
        private final String approvalId;
        public ApprovalRequiredException(String approvalId, String reason) {
            super("Approval required: " + approvalId + " — " + reason);
            this.approvalId = approvalId;
        }
        public String getApprovalId() { return approvalId; }
    }

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    public static class ScenarioAlreadyExistsException extends RuntimeException {
        public ScenarioAlreadyExistsException(String workspaceId, String scenarioId) { super("Scenario already exists: " + workspaceId + "/" + scenarioId); }
    }

    public static class ScenarioNotFoundException extends RuntimeException {
        public ScenarioNotFoundException(String workspaceId, String scenarioId) { super("Scenario not found: " + workspaceId + "/" + scenarioId); }
    }
}
