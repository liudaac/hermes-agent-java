package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.org.observe.AgentTrace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects foundation execution truth into a Business Portal run story.
 *
 * <p>This adapter does not execute work and does not become a trace store. It
 * translates IntentRun / AgentTrace data into business-readable records, while
 * keeping the foundation run/trace identifiers in metadata.</p>
 */
public class BusinessRunProjectionAdapter {

    public BusinessRunRecord fromIntentRun(String workspaceId, String scenarioId, String scenarioName,
                                           ScenarioOrchestrator.IntentRun run) {
        return fromIntentRun(workspaceId, scenarioId, scenarioName, run, List.of());
    }

    public BusinessRunRecord fromIntentRun(String workspaceId, String scenarioId, String scenarioName,
                                           ScenarioOrchestrator.IntentRun run, List<AgentTrace> traces) {
        Objects.requireNonNull(run, "run");
        Instant createdAt = instant(run.startedAt);
        Instant updatedAt = run.completedAt > 0 ? instant(run.completedAt) : Instant.now();
        List<BusinessRunStep> steps = new ArrayList<>();
        steps.addAll(assignmentSteps(run));
        steps.addAll(attemptSteps(run));
        steps.addAll(traceSteps(traces));
        if (steps.isEmpty()) {
            steps.add(new BusinessRunStep()
                .setStepId("intent-plan")
                .setTitle("Intent accepted by Hermes foundation")
                .setSummary(run.intent)
                .setActor("ScenarioOrchestrator")
                .setEvidence("intent://" + run.runId)
                .setStatus(mapStatus(run.status))
                .setTimestamp(createdAt)
                .setMetadata(Map.of("source", "foundation:intent-run")));
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("subtasksTotal", run.assignments().size());
        metrics.put("succeeded", run.successes().size());
        metrics.put("failed", run.failures().size());
        metrics.put("attempts", run.attempts().size());
        metrics.put("durationMs", run.completedAt > 0 ? Math.max(0, run.completedAt - run.startedAt) : 0);
        if (traces != null && !traces.isEmpty()) {
            metrics.put("traceCount", traces.size());
            metrics.put("traceErrorCount", traces.stream().mapToInt(AgentTrace::getErrorCount).sum());
            metrics.put("traceTokens", traces.stream().mapToLong(AgentTrace::getTotalTokens).sum());
            metrics.put("traceEstimatedCost", traces.stream().mapToDouble(AgentTrace::getEstimatedCost).sum());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "foundation:intent-run");
        metadata.put("intentRunId", run.runId);
        metadata.put("controlAction", run.controlAction);
        metadata.put("preferredTeamName", run.preferredTeamName);
        metadata.put("parentRunId", run.parentRunId);
        metadata.put("currentSubtask", run.currentSubtask);
        metadata.put("foundationStatus", run.status.name());
        metadata.put("collaborationPattern", run.collaborationPattern != null ? run.collaborationPattern.name() : "SEQUENTIAL");
        if (traces != null && !traces.isEmpty()) {
            metadata.put("traceIds", traces.stream().map(AgentTrace::getTraceId).toList());
        }

        return new BusinessRunRecord()
            .setRunId("business-" + run.runId)
            .setWorkspaceId(workspaceId)
            .setTeamId(run.preferredTeamId)
            .setScenario(scenarioName)
            .setScenarioId(scenarioId)
            .setCollaborationPattern(run.collaborationPattern != null ? run.collaborationPattern.name() : "SEQUENTIAL")
            .setTaskTitle(titleFromIntent(run.intent))
            .setTaskInput(run.intent)
            .setResultSummary(resultSummary(run))
            .setConclusionReason(conclusionReason(run))
            .setSystemAction(systemAction(run))
            .setRiskJudgement(riskJudgement(run))
            .setNextSuggestion(nextSuggestion(run))
            .setStatus(mapStatus(run.status))
            .setTechnicalTraceRef("intent://" + run.runId)
            .setSteps(steps)
            .setTokensUsed(traces != null ? traces.stream().mapToLong(AgentTrace::getTotalTokens).sum() : 0L)
            .setEstimatedCost(traces != null ? traces.stream().mapToDouble(AgentTrace::getEstimatedCost).sum() : 0.0)
            .setMetrics(metrics)
            .setMetadata(metadata)
            .setCreatedAt(createdAt)
            .setUpdatedAt(updatedAt);
    }

    /** Optionally persist a projected record through the existing business run service. */
    public BusinessRunRecord persistProjection(BusinessRunService service, BusinessRunRecord projection) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(projection, "projection");
        return service.createRun(
            projection.getWorkspaceId(),
            projection.getTeamId(),
            projection.getScenario(),
            projection.getScenarioId(),
            projection.getTaskTitle(),
            projection.getTaskInput(),
            projection.getResultSummary(),
            projection.getConclusionReason(),
            projection.getSystemAction(),
            projection.getRiskJudgement(),
            projection.getNextSuggestion(),
            projection.getStatus(),
            projection.getTechnicalTraceRef(),
            projection.getSteps(),
            projection.getTokensUsed(),
            projection.getEstimatedCost(),
            projection.getMetrics(),
            withProjectionMetadata(projection.getMetadata(), projection.getRunId())
        );
    }

    private List<BusinessRunStep> assignmentSteps(ScenarioOrchestrator.IntentRun run) {
        List<BusinessRunStep> steps = new ArrayList<>();
        int index = 1;
        for (ScenarioOrchestrator.SubtaskAssignment assignment : run.assignments()) {
            steps.add(new BusinessRunStep()
                .setStepId("assignment-" + index++)
                .setTitle("Assigned subtask")
                .setSummary(assignment.subtask())
                .setActor(nonBlank(assignment.agentId(), "unassigned"))
                .setAgentId(assignment.agentId())
                .setScore(assignment.score())
                .setMatchedSkills(assignment.matchedSkills() != null ? String.join(", ", assignment.matchedSkills()) : null)
                .setEvidence("role=" + nonBlank(assignment.roleName(), "unknown") + ", score=" + assignment.score())
                .setStatus(statusForAssignment(run, assignment.subtask()))
                .setTimestamp(instant(run.startedAt))
                .setMetadata(Map.of(
                    "source", "foundation:intent-assignment",
                    "teamId", nullable(assignment.teamId()),
                    "teamName", nullable(assignment.teamName()),
                    "matchedSkills", assignment.matchedSkills() != null ? assignment.matchedSkills() : List.of(),
                    "score", assignment.score()
                )));
        }
        return steps;
    }

    private List<BusinessRunStep> attemptSteps(ScenarioOrchestrator.IntentRun run) {
        List<BusinessRunStep> steps = new ArrayList<>();
        int index = 1;
        for (ScenarioOrchestrator.IntentAttempt attempt : run.attempts()) {
            steps.add(new BusinessRunStep()
                .setStepId("attempt-" + index++)
                .setTitle(attempt.success() ? "Foundation attempt succeeded" : "Foundation attempt failed")
                .setSummary(attempt.subtask())
                .setActor(nonBlank(attempt.agentId(), "unassigned"))
                .setAgentId(attempt.agentId())
                .setRetry(attempt.reassigned())
                .setRetryFrom(attempt.reassigned() ? attempt.reassignedFrom() : null)
                .setEvidence(attempt.success() ? "trace=" + nonBlank(attempt.traceId(), "none") : nonBlank(attempt.error(), "attempt failed"))
                .setStatus(attempt.success() ? BusinessRunService.COMPLETED : BusinessRunService.FAILED)
                .setTimestamp(instant(attempt.timestamp()))
                .setMetadata(Map.of(
                    "source", "foundation:intent-attempt",
                    "traceId", nullable(attempt.traceId()),
                    "roleName", nullable(attempt.roleName()),
                    "score", attempt.score(),
                    "latencyMs", attempt.latencyMs(),
                    "reassigned", attempt.reassigned()
                )));
        }
        return steps;
    }

    private List<BusinessRunStep> traceSteps(List<AgentTrace> traces) {
        if (traces == null || traces.isEmpty()) return List.of();
        List<BusinessRunStep> steps = new ArrayList<>();
        int index = 1;
        for (AgentTrace trace : traces) {
            steps.add(new BusinessRunStep()
                .setStepId("trace-" + index++)
                .setTitle("Agent trace summary")
                .setSummary(trace.getTaskDescription())
                .setActor(trace.getAgentId())
                .setEvidence("trace://" + trace.getTraceId())
                .setStatus(mapTraceStatus(trace.getStatus()))
                .setTimestamp(trace.getStartTime())
                .setMetadata(Map.of(
                    "source", "foundation:agent-trace",
                    "traceId", trace.getTraceId(),
                    "sessionId", trace.getSessionId(),
                    "stepCount", trace.stepCount(),
                    "errorCount", trace.getErrorCount(),
                    "tokens", trace.getTotalTokens(),
                    "estimatedCost", trace.getEstimatedCost()
                )));
        }
        return steps;
    }

    private String statusForAssignment(ScenarioOrchestrator.IntentRun run, String subtask) {
        if (run.failures().containsKey(subtask)) return BusinessRunService.FAILED;
        if (run.successes().containsKey(subtask)) return BusinessRunService.COMPLETED;
        return mapStatus(run.status);
    }

    private String mapStatus(ScenarioOrchestrator.RunStatus status) {
        if (status == null) return BusinessRunService.RUNNING;
        return switch (status) {
            case COMPLETED -> BusinessRunService.COMPLETED;
            case FAILED, PARTIAL, INTERRUPTED -> BusinessRunService.FAILED;
            case PENDING, RUNNING -> BusinessRunService.RUNNING;
        };
    }

    private String mapTraceStatus(AgentTrace.Status status) {
        if (status == null) return BusinessRunService.COMPLETED;
        return switch (status) {
            case SUCCESS -> BusinessRunService.COMPLETED;
            case FAILED, CANCELLED, TIMED_OUT, PARTIAL -> BusinessRunService.FAILED;
        };
    }

    private String resultSummary(ScenarioOrchestrator.IntentRun run) {
        return switch (run.status) {
            case COMPLETED -> "Hermes foundation completed the intent run with " + run.successes().size() + " successful subtask(s).";
            case FAILED -> "Hermes foundation failed the intent run with " + run.failures().size() + " failed subtask(s).";
            case PARTIAL -> "Hermes foundation partially completed the run; some subtasks need review.";
            case INTERRUPTED -> "Hermes foundation marked the run interrupted before completion.";
            case RUNNING, PENDING -> "Hermes foundation run is still in progress.";
        };
    }

    private String conclusionReason(ScenarioOrchestrator.IntentRun run) {
        if (!run.failures().isEmpty()) {
            return "Failures: " + run.failures();
        }
        if (!run.successes().isEmpty()) {
            return "Successes: " + run.successes();
        }
        return "Foundation run status: " + run.status;
    }

    private String systemAction(ScenarioOrchestrator.IntentRun run) {
        return switch (run.status) {
            case COMPLETED -> "No immediate system action required.";
            case FAILED, PARTIAL, INTERRUPTED -> "Review failed subtasks and consider replay or reroute through ScenarioOrchestrator.";
            case RUNNING, PENDING -> "Wait for foundation run completion.";
        };
    }

    private String riskJudgement(ScenarioOrchestrator.IntentRun run) {
        if (!run.failures().isEmpty()) return "Needs operator review because foundation failures are present.";
        if (run.delegationDecision != null && run.delegationDecision.recommended()) return "Delegation was recommended by foundation policy.";
        return "No additional business risk surfaced by projection.";
    }

    private String nextSuggestion(ScenarioOrchestrator.IntentRun run) {
        if (!run.failures().isEmpty()) return "Use ScenarioOrchestrator.replayFailures or reroute failed subtasks.";
        if (run.status == ScenarioOrchestrator.RunStatus.RUNNING || run.status == ScenarioOrchestrator.RunStatus.PENDING) return "Refresh after foundation run completes.";
        return "Use this projection for business review; keep foundation trace refs for audit.";
    }

    private String titleFromIntent(String intent) {
        if (intent == null || intent.isBlank()) return "Business intent run";
        String firstLine = intent.lines().findFirst().orElse(intent).trim();
        if (firstLine.length() > 80) return firstLine.substring(0, 77) + "...";
        return firstLine;
    }

    private Map<String, Object> withProjectionMetadata(Map<String, Object> metadata, String projectionRunId) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) copy.putAll(metadata);
        copy.put("projectionRunId", projectionRunId);
        copy.put("projectionPersistedVia", "BusinessRunService");
        return copy;
    }

    private static Instant instant(long epochMs) {
        return epochMs > 0 ? Instant.ofEpochMilli(epochMs) : Instant.now();
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static Object nullable(Object value) {
        return value != null ? value : "";
    }
}
