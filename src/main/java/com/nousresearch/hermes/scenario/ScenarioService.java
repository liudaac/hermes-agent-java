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
import java.util.Set;

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
    private com.nousresearch.hermes.canary.CanaryReleaseService canaryReleaseService;

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
        // Also wire policy into the team runtime so agent tool permissions are enforced
        this.teamBlueprintRuntime.setPolicyService(policyService);
    }

    /** Wire active memory service for knowledge recall. */
    public void setActiveMemoryService(ActiveMemoryService activeMemoryService) {
        this.activeMemoryService = activeMemoryService;
    }

    /** Wire canary release service for traffic-based version routing + metrics. */
    public void setCanaryReleaseService(com.nousresearch.hermes.canary.CanaryReleaseService canaryReleaseService) {
        this.canaryReleaseService = canaryReleaseService;
        this.teamBlueprintRuntime.setCanaryReleaseService(canaryReleaseService);
    }

    /** Expose the underlying team blueprint runtime (for wiring tool-approval coordinator etc). */
    public TeamBlueprintRuntime getTeamBlueprintRuntime() {
        return this.teamBlueprintRuntime;
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

            // Canary support: also spin up the canary version's runtime if active
            int canaryVersion = teamBlueprintRuntime.resolveVersionForRequest(
                workspaceId, entryTeamId, scenarioId + ":" + System.nanoTime());
            var team = teamBlueprintService.requireTeamBlueprint(workspaceId, entryTeamId);
            if (canaryVersion != team.getActiveVersion()) {
                teamBlueprintRuntime.ensureTeamRuntimeForVersion(workspaceId, entryTeamId, canaryVersion);
            }
        }

        // Approval gate: check if any agent's approval rules require human review
        if (!skipApprovalCheck && policyService != null && businessApprovalService != null) {
            var check = policyService.checkApprovalRequired(workspaceId, scenario.getEntryTeamId(), "execute", userInput);
            if (check.approvalNeeded()) {
                // Create the approval record with scenario + run linkage
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
                    Map.of("source", "auto", "trigger", check.reason(), "scenarioId", scenarioId)
                );
                approval.setScenarioId(scenarioId);

                // Create a NEEDS_APPROVAL run record so the execution is tracked
                BusinessRunRecord pendingRun = runService.createRun(
                    workspaceId, scenario.getEntryTeamId(), scenario.getName(), scenarioId,
                    scenario.getName() + " — 待审批",
                    userInput,
                    "审批通过后自动开始执行。",
                    "触发审批规则：" + check.matchedRule(),
                    "等待人工审批",
                    "中风险 — 触发了 " + check.matchedRule() + " 审批规则",
                    "审批通过后开始执行；如被拒绝则取消本次运行。",
                    BusinessRunService.NEEDS_APPROVAL,
                    null, List.of(),
                    0L, 0.0,
                    Map.of(),
                    Map.of("source", BusinessRunService.SOURCE_MANUAL, "approvalId", approval.getApprovalId(), "approvalReason", check.reason())
                );
                pendingRun.setApprovalId(approval.getApprovalId());
                approval.setRunId(pendingRun.getRunId());

                // Re-save to persist the cross-references
                businessApprovalService.updateApproval(approval);
                runService.updateRun(pendingRun);

                throw new ApprovalRequiredException(approval.getApprovalId(), check.reason(), pendingRun.getRunId());
            }
        }

        IntentOrchestrator.IntentRun intentRun = scenarioIntentAdapter.execute(scenario, userInput);
        BusinessRunProjectionAdapter projectionAdapter = new BusinessRunProjectionAdapter();
        BusinessRunRecord projection = projectionAdapter.fromIntentRun(
            workspaceId, scenarioId, scenario.getName(), intentRun);

        // Canary: tag the run with which version handled it
        if (entryTeamId != null && !entryTeamId.isBlank()) {
            int routedVersion = teamBlueprintRuntime.resolveVersionForRequest(
                workspaceId, entryTeamId, scenarioId + ":" + intentRun.runId);
            projection.setTeamVersion(String.valueOf(routedVersion));
            Map<String, Object> meta = new LinkedHashMap<>(projection.getMetadata() != null ? projection.getMetadata() : Map.of());
            meta.put("teamVersion", String.valueOf(routedVersion));
            // Mark canary if a canary release is active and this run was routed differently
            var team = teamBlueprintService.requireTeamBlueprint(workspaceId, entryTeamId);
            if (routedVersion != team.getActiveVersion()) {
                meta.put("canary", true);
                meta.put("canaryRoutedVersion", routedVersion);
                meta.put("baselineVersion", team.getActiveVersion());
            }
            projection.setMetadata(meta);
        }

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
                        // Update existing steps that just completed, and add new completed steps
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
                        // Also update the persisted run steps (overwrite with latest state)
                        updateRunStepsFromIntent(runService, workspaceId, runId, intentRun);
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
                        // Update persisted run steps
                        updateRunStepsFromIntent(runService, workspaceId, runId, intentRun);
                        lastFailureCount = currentFailures;
                    }
                }

                // Run finished — final step update + final status
                updateRunStepsFromIntent(runService, workspaceId, runId, intentRun);

                String finalStatus = switch (intentRun.status) {
                    case COMPLETED -> BusinessRunService.COMPLETED;
                    case PARTIAL, FAILED -> BusinessRunService.FAILED;
                    default -> BusinessRunService.FAILED;
                };
                String finalMessage = "Run " + finalStatus.toLowerCase() +
                    " — " + intentRun.successes.size() + " succeeded, " +
                    intentRun.failures.size() + " failed";

                // Update result summary as well
                var finishedRun = runService.requireRun(workspaceId, runId);
                finishedRun.setResultSummary(finalMessage);
                if (intentRun.successes.size() > 0) {
                    String firstResult = intentRun.successes.values().iterator().next();
                    if (firstResult != null && !firstResult.isBlank()) {
                        finishedRun.setResultSummary(firstResult.length() > 500
                            ? firstResult.substring(0, 500) + "..."
                            : firstResult);
                    }
                }
                finishedRun.setConclusionReason("Execution completed with " +
                    intentRun.successes.size() + " successes and " +
                    intentRun.failures.size() + " failures");
                finishedRun.setSystemAction("Run finished — review results and decide next steps");
                runService.updateRun(finishedRun);

                runService.updateRunStatus(workspaceId, runId, finalStatus, finalMessage);

                // Canary metrics: record outcome
                if (canaryReleaseService != null && finishedRun.getTeamId() != null && finishedRun.getTeamVersion() != null) {
                    try {
                        int version = Integer.parseInt(finishedRun.getTeamVersion());
                        long durationMs = finishedRun.getMetrics() != null && finishedRun.getMetrics().get("durationMs") != null
                            ? ((Number) finishedRun.getMetrics().get("durationMs")).longValue()
                            : 0;
                        canaryReleaseService.recordRunOutcome(
                            workspaceId, finishedRun.getTeamId(), version,
                            finalStatus, durationMs, finishedRun.getEstimatedCost());
                    } catch (Exception canaryEx) {
                        logger.debug("Failed to record canary metrics for run {}: {}", runId, canaryEx.getMessage());
                    }
                }

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

    /**
     * Update the persisted run's steps from the current IntentRun state.
     * Updates matching stepIds in-place (for attempt-N steps) and adds new ones.
     * Preserves non-attempt steps (assignments, traces, etc.) from the projection.
     */
    private void updateRunStepsFromIntent(BusinessRunService runService, String workspaceId,
                                           String runId, IntentOrchestrator.IntentRun intentRun) {
        try {
            var run = runService.requireRun(workspaceId, runId);
            List<BusinessRunStep> existingSteps = new ArrayList<>(run.getSteps());

            // Build a map of attempt steps by stepId for quick lookup
            Map<String, Integer> attemptIndex = new java.util.HashMap<>();
            for (int i = 0; i < existingSteps.size(); i++) {
                String sid = existingSteps.get(i).getStepId();
                if (sid != null && sid.startsWith("attempt-")) {
                    attemptIndex.put(sid, i);
                }
            }

            Set<String> completedSubtasks = intentRun.successes.keySet();

            // Update or add attempt steps
            for (int i = 0; i < intentRun.attempts.size(); i++) {
                var attempt = intentRun.attempts.get(i);
                String stepId = "attempt-" + (i + 1);
                String status;
                String summary;
                if (completedSubtasks.contains(attempt.subtask())) {
                    status = "COMPLETED";
                    summary = intentRun.successes.get(attempt.subtask());
                } else if (intentRun.failures.containsKey(attempt.subtask())) {
                    status = "FAILED";
                    summary = intentRun.failures.get(attempt.subtask());
                } else {
                    status = "RUNNING";
                    summary = attempt.agentId() + " is processing";
                }

                BusinessRunStep step = new BusinessRunStep()
                    .setStepId(stepId)
                    .setTitle(attempt.subtask() != null ? attempt.subtask() : "Task " + (i + 1))
                    .setSummary(summary)
                    .setActor(attempt.roleName() != null && !attempt.roleName().isBlank()
                        ? attempt.roleName() : attempt.agentId())
                    .setStatus(status);

                Integer idx = attemptIndex.get(stepId);
                if (idx != null) {
                    // Update existing step in place
                    existingSteps.set(idx, step);
                } else {
                    // Add new step
                    existingSteps.add(step);
                }
            }

            run.setSteps(existingSteps);
            run.setMetrics(Map.of(
                "subtasksTotal", intentRun.attempts.size(),
                "succeeded", intentRun.successes.size(),
                "failed", intentRun.failures.size(),
                "attempts", intentRun.attempts.size(),
                "durationMs", System.currentTimeMillis() - (run.getCreatedAt() != null
                    ? run.getCreatedAt().toEpochMilli() : System.currentTimeMillis())
            ));
            runService.updateRun(run);
        } catch (Exception e) {
            logger.warn("Failed to update run steps for {}: {}", runId, e.getMessage());
        }
    }

    public static class ApprovalRequiredException extends RuntimeException {
        private final String approvalId;
        private final String runId;
        public ApprovalRequiredException(String approvalId, String reason) {
            super("Approval required: " + approvalId + " — " + reason);
            this.approvalId = approvalId;
            this.runId = null;
        }
        public ApprovalRequiredException(String approvalId, String reason, String runId) {
            super("Approval required: " + approvalId + " — " + reason);
            this.approvalId = approvalId;
            this.runId = runId;
        }
        public String getApprovalId() { return approvalId; }
        public String getRunId() { return runId; }
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
