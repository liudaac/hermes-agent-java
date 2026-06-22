package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.collaboration.pattern.CollaborationPattern;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.observe.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intent-driven task orchestrator.
 *
 * <p>Given a complex task description, this orchestrator:
 * <ol>
 *   <li>Decomposes the task into candidate subtasks (by keyword + role hints)</li>
 *   <li>Matches each subtask to the best teammate based on skills</li>
 *   <li>Routes the subtask via TenantBus</li>
 *   <li>Tracks progress and reassigns on failure</li>
 * </ol>
 *
 * <p>This is the "self-organization" layer: instead of a human configuring
 * a workflow, the agent figures out who to involve based on the intent
 * and the available teammates' skills.</p>
 */
public class ScenarioOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioOrchestrator.class);
    private static final AtomicLong TASK_ID_GEN = new AtomicLong();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String RUNS_FILE = "intent-runs.json";
    private static final int DEFAULT_MAX_PERSISTED_RUNS = 1000;

    private final TenantContext tenantContext;
    private final TaskOrchestrator taskOrchestrator;
    private final DelegatedTaskStore delegatedTaskStore;
    private final ConcurrentHashMap<String, IntentRun> runs = new ConcurrentHashMap<>();
    private IntentDecomposer intentDecomposer;

    public ScenarioOrchestrator(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
        this.taskOrchestrator = tenantContext.getTaskOrchestrator();
        this.delegatedTaskStore = tenantContext.getDelegatedTaskStore();
        loadRuns();
    }

    public void setIntentDecomposer(IntentDecomposer decomposer) {
        this.intentDecomposer = decomposer;
    }

    /**
     * Decompose a task and find the best teammates for each subtask.
     * Returns an IntentPlan describing who would do what (without executing).
     */
    public IntentPlan plan(String intent) {
        return plan(intent, null);
    }

    /**
     * Decompose and plan an intent with an optional preferred team context.
     */
    public IntentPlan plan(String intent, String preferredTeamId) {
        return plan(intent, preferredTeamId, false, List.of());
    }

    /**
     * Decompose and plan an intent with optional context-aware delegation advice.
     */
    public IntentPlan plan(String intent, String preferredTeamId, boolean allowDelegation, List<?> contextSignals) {
        return plan(intent, preferredTeamId, allowDelegation, contextSignals, true);
    }

    private IntentPlan plan(String intent, String preferredTeamId, boolean allowDelegation, List<?> contextSignals, boolean createDelegatedTask) {
        return plan(intent, preferredTeamId, allowDelegation, contextSignals, createDelegatedTask, CollaborationPattern.SEQUENTIAL);
    }

    private IntentPlan plan(String intent, String preferredTeamId, boolean allowDelegation, List<?> contextSignals, boolean createDelegatedTask, CollaborationPattern pattern) {
        tenantContext.initCollaboration();
        var roles = tenantContext.listAgentRoles();
        String normalizedTeamId = normalizeTeamId(preferredTeamId);
        boolean delegationInputsProvided = allowDelegation || (contextSignals != null && !contextSignals.isEmpty());
        DelegationDecision delegation = delegationInputsProvided
            ? DelegationPolicy.evaluate(allowDelegation, ContextPressureDetector.detect(contextSignals), tenantContext, normalizedTeamId)
            : null;
        DelegatedTask delegatedTask = createDelegatedTask ? createDelegatedTaskIfRecommended(intent, null, delegation) : null;

        if (roles.isEmpty()) {
            return new IntentPlan(intent, List.of(), "no teammates available", normalizedTeamId, teamName(normalizedTeamId), delegation, delegatedTask, pattern);
        }

        // Use IntentDecomposer if available, else heuristic fallback
        List<SubtaskAssignment> assignments;
        if (intentDecomposer != null) {
            var plans = intentDecomposer.decompose(intent, pattern, roles);
            assignments = new ArrayList<>();
            for (var plan : plans) {
                SubtaskAssignment best = plan.agentHint() != null && !plan.agentHint().isBlank()
                    ? findBestMatch(plan.subtask(), roles, tenantContext, normalizedTeamId, plan.agentHint())
                    : findBestMatch(plan.subtask(), roles, tenantContext, normalizedTeamId);
                assignments.add(best.withDelegation(delegation, delegatedTask));
            }
        } else {
            // Heuristic decomposition: split on conjunctions and key verbs
            List<String> subtasks = decompose(intent);
            assignments = new ArrayList<>();
            for (String subtask : subtasks) {
                SubtaskAssignment best = findBestMatch(subtask, roles, tenantContext, normalizedTeamId);
                assignments.add(best.withDelegation(delegation, delegatedTask));
            }
        }

        return new IntentPlan(intent, assignments, "planned", normalizedTeamId, teamName(normalizedTeamId), delegation, delegatedTask, pattern);
    }

    /**
     * Execute an intent plan. Returns immediately with a run ID.
     * Use {@link #getRun(String)} to poll for status.
     */
    public IntentRun execute(String intent) {
        return execute(intent, null);
    }

    /** Execute an intent using an optional preferred team context. */
    public IntentRun execute(String intent, String preferredTeamId) {
        IntentPlan plan = plan(intent, preferredTeamId);
        return startRun(intent, plan.assignments(), null, "execute", plan.preferredTeamId(), plan.preferredTeamName(), plan.delegationDecision(), plan.collaborationPattern());
    }

    /** Execute an intent with optional context-aware delegation advice. */
    public IntentRun execute(String intent, String preferredTeamId, boolean allowDelegation, List<?> contextSignals) {
        IntentPlan plan = plan(intent, preferredTeamId, allowDelegation, contextSignals, false);
        return startRun(intent, plan.assignments(), null, "execute", plan.preferredTeamId(), plan.preferredTeamName(), plan.delegationDecision(), plan.collaborationPattern());
    }

    /** Execute with explicit collaboration pattern. */
    public IntentRun execute(String intent, String preferredTeamId, CollaborationPattern pattern) {
        IntentPlan plan = plan(intent, preferredTeamId, false, List.of(), false, pattern);
        return startRun(intent, plan.assignments(), null, "execute", plan.preferredTeamId(), plan.preferredTeamName(), plan.delegationDecision(), plan.collaborationPattern());
    }

    /** Replay only the failed subtasks from a previous run. */
    public IntentRun replayFailures(String runId) {
        IntentRun original = getRun(runId);
        if (original == null) {
            throw new IllegalArgumentException("Unknown intent run: " + runId);
        }
        List<SubtaskAssignment> failedAssignments = original.assignments().stream()
            .filter(a -> original.failures().containsKey(a.subtask()))
            .toList();
        if (failedAssignments.isEmpty()) {
            throw new IllegalStateException("Intent run has no failed subtasks to replay: " + runId);
        }
        return startRun(original.intent, failedAssignments, runId, "replay_failed", original.preferredTeamId, original.preferredTeamName, original.delegationDecision, original.collaborationPattern);
    }

    /** Reroute a subtask from a previous run to a specific target agent. */
    public IntentRun reroute(String runId, String subtask, String targetAgentId) {
        IntentRun original = getRun(runId);
        if (original == null) {
            throw new IllegalArgumentException("Unknown intent run: " + runId);
        }
        String targetSubtask = subtask != null && !subtask.isBlank()
            ? subtask
            : original.failures().keySet().stream().findFirst().orElse(original.currentSubtask);
        if (targetSubtask == null || targetSubtask.isBlank()) {
            throw new IllegalArgumentException("Subtask is required for reroute");
        }

        SubtaskAssignment base = original.assignments().stream()
            .filter(a -> a.subtask().equals(targetSubtask))
            .findFirst()
            .orElse(new SubtaskAssignment(targetSubtask, null, null, 0.0, List.of()));

        SubtaskAssignment target;
        if (targetAgentId != null && !targetAgentId.isBlank()) {
            AgentRuntimeProfile role = tenantContext.getAgentRole(targetAgentId);
            if (role == null) {
                throw new IllegalArgumentException("Unknown target agent role: " + targetAgentId);
            }
            var score = CapabilityScorer.score(targetSubtask, targetAgentId, role, tenantContext, original.preferredTeamId);
            TeamRuntime team = teamForAgent(targetAgentId, original.preferredTeamId);
            target = new SubtaskAssignment(targetSubtask, targetAgentId, role.getRoleName(), score.total(), score.matchedSkills(), score.components(), teamId(team), teamName(team));
        } else {
            target = findAlternative(base, base.agentId());
            if (target == null || target.agentId() == null) {
                throw new IllegalStateException("No alternative teammate available for subtask: " + targetSubtask);
            }
        }
        return startRun(original.intent, List.of(target.withDelegation(original.delegationDecision)), runId, "reroute", original.preferredTeamId, original.preferredTeamName, original.delegationDecision, original.collaborationPattern);
    }

    private IntentRun startRun(String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction) {
        return startRun(intent, assignments, parentRunId, controlAction, null, null, null, CollaborationPattern.SEQUENTIAL);
    }

    private IntentRun startRun(String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName) {
        return startRun(intent, assignments, parentRunId, controlAction, preferredTeamId, preferredTeamName, null, CollaborationPattern.SEQUENTIAL);
    }

    private IntentRun startRun(String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision) {
        return startRun(intent, assignments, parentRunId, controlAction, preferredTeamId, preferredTeamName, delegationDecision, CollaborationPattern.SEQUENTIAL);
    }

    private IntentRun startRun(String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision, CollaborationPattern pattern) {
        String runId = "run_" + TASK_ID_GEN.incrementAndGet();
        DelegatedTask delegatedTask = createDelegatedTaskIfRecommended(intent, runId, delegationDecision);
        List<SubtaskAssignment> runAssignments = attachDelegatedTask(assignments, delegatedTask);
        IntentRun run = new IntentRun(runId, intent, runAssignments, parentRunId, controlAction, preferredTeamId, preferredTeamName, delegationDecision, delegatedTask, pattern);
        runs.put(runId, run);
        saveRuns();

        // Execute based on collaboration pattern
        Thread t = new Thread(() -> executeByPattern(run, runAssignments, pattern), "intent-orchestrator-" + runId);
        t.setDaemon(true);
        t.start();

        return run;
    }

    /**
     * 根据协作模式分发执行策略。
     * 每种模式对应一种线程调度和结果聚合方式。
     */
    private void executeByPattern(IntentRun run, List<SubtaskAssignment> assignments, CollaborationPattern pattern) {
        switch (pattern) {
            case PARALLEL -> executeParallel(run, assignments);
            case REVIEW -> executeReview(run, assignments);
            case COMPETITIVE -> executeCompetitive(run, assignments);
            case MASTER_WORKER -> executeMasterWorker(run, assignments);
            case PIPELINE -> executePipeline(run, assignments);
            default -> executeSequential(run, assignments);
        }
        saveRuns();
    }

    /** 顺序执行：A → B → C，每步等待前一步完成。最稳定但最慢。 */
    private void executeSequential(IntentRun run, List<SubtaskAssignment> assignments) {
        for (SubtaskAssignment a : assignments) {
            run.setStatus(RunStatus.RUNNING);
            run.setCurrentSubtask(a.subtask());
            if (a.agentId() == null) {
                run.recordFailure(a.subtask(), "No matching teammate");
                continue;
            }
            try {
                String result = delegateOne(run, a, false, null);
                run.recordSuccess(a.subtask(), result);
            } catch (Exception e) {
                handleExecutionFailure(run, a, e, false, null);
            }
        }
        run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
    }

    /** 并行执行：所有子任务同时启动，CountDownLatch 等待全部完成。适合无依赖的查询类任务。 */
    private void executeParallel(IntentRun run, List<SubtaskAssignment> assignments) {
        run.setStatus(RunStatus.RUNNING);
        CountDownLatch latch = new CountDownLatch(assignments.size());
        for (SubtaskAssignment a : assignments) {
            if (a.agentId() == null) {
                run.recordFailure(a.subtask(), "No matching teammate");
                latch.countDown();
                continue;
            }
            Thread worker = new Thread(() -> {
                try {
                    String result = delegateOne(run, a, false, null);
                    run.recordSuccess(a.subtask(), result);
                } catch (Exception e) {
                    handleExecutionFailure(run, a, e, false, null);
                } finally {
                    latch.countDown();
                }
            }, "parallel-" + run.runId + "-" + a.subtask().hashCode());
            worker.setDaemon(true);
            worker.start();
        }
        try {
            latch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
    }

    /** 评审模式：第一个 Agent 生成 → 第二个 Agent 复核 → 人工确认。
     * 适合高风险任务（如退款审批、大额订单处理）。
     */
    private void executeReview(IntentRun run, List<SubtaskAssignment> assignments) {
        if (assignments.isEmpty()) {
            run.setStatus(RunStatus.COMPLETED);
            return;
        }
        // Step 1: Primary agent generates output
        SubtaskAssignment primary = assignments.get(0);
        run.setStatus(RunStatus.RUNNING);
        run.setCurrentSubtask(primary.subtask());
        String primaryResult;
        try {
            primaryResult = delegateOne(run, primary, false, null);
            run.recordSuccess(primary.subtask(), primaryResult);
        } catch (Exception e) {
            handleExecutionFailure(run, primary, e, false, null);
            run.setStatus(RunStatus.FAILED);
            return;
        }

        // Step 2: Reviewer agent reviews (if available)
        if (assignments.size() > 1) {
            SubtaskAssignment reviewer = assignments.get(1);
            run.setCurrentSubtask(reviewer.subtask() + " (review)");
            try {
                var reviewPayload = Map.<String, Object>of(
                    "review_target", primary.subtask(),
                    "review_content", primaryResult
                );
                String reviewResult = delegateOne(run, reviewer, false, null, reviewPayload);
                run.recordSuccess(reviewer.subtask(), reviewResult);
            } catch (Exception e) {
                handleExecutionFailure(run, reviewer, e, false, null);
            }
        }
        run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
    }

    /** 竞争模式：多个 Agent 同时解决同一问题，按 capability score 择优。
     * 适合创意生成、方案对比等场景。
     */
    private void executeCompetitive(IntentRun run, List<SubtaskAssignment> assignments) {
        run.setStatus(RunStatus.RUNNING);
        // All agents solve the same problem; best result wins
        Map<String, String> results = new ConcurrentHashMap<>();
        Map<String, Double> scores = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(assignments.size());

        for (SubtaskAssignment a : assignments) {
            if (a.agentId() == null) {
                latch.countDown();
                continue;
            }
            Thread competitor = new Thread(() -> {
                try {
                    String result = delegateOne(run, a, false, null);
                    results.put(a.agentId(), result);
                    scores.put(a.agentId(), a.score());
                    run.recordSuccess(a.subtask() + " [" + a.agentId() + "]", result);
                } catch (Exception e) {
                    run.recordFailure(a.subtask() + " [" + a.agentId() + "]", e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, "competitive-" + run.runId + "-" + a.agentId());
            competitor.setDaemon(true);
            competitor.start();
        }

        try {
            latch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Select best result by capability score
        String bestAgent = scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
        if (bestAgent != null && results.containsKey(bestAgent)) {
            run.recordSuccess("BEST_RESULT", results.get(bestAgent));
        }
        run.setStatus(run.failures().size() < assignments.size() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
    }

    /** 主从模式：第一个 Agent（Master）规划 → 其余 Agent（Worker）并行执行。
     * 适合复杂任务分解和并行子任务执行。
     */
    private void executeMasterWorker(IntentRun run, List<SubtaskAssignment> assignments) {
        if (assignments.isEmpty()) {
            run.setStatus(RunStatus.COMPLETED);
            return;
        }
        // Master (first agent) decomposes
        SubtaskAssignment master = assignments.get(0);
        run.setStatus(RunStatus.RUNNING);
        run.setCurrentSubtask(master.subtask() + " (master planning)");
        String planResult;
        try {
            planResult = delegateOne(run, master, false, null);
            run.recordSuccess(master.subtask(), planResult);
        } catch (Exception e) {
            handleExecutionFailure(run, master, e, false, null);
            run.setStatus(RunStatus.FAILED);
            return;
        }

        // Workers execute in parallel (remaining agents)
        List<SubtaskAssignment> workers = assignments.subList(1, assignments.size());
        if (workers.isEmpty()) {
            run.setStatus(RunStatus.COMPLETED);
            return;
        }

        CountDownLatch workerLatch = new CountDownLatch(workers.size());
        for (SubtaskAssignment w : workers) {
            Thread workerThread = new Thread(() -> {
                try {
                    var workerPayload = Map.<String, Object>of("master_plan", planResult);
                    String result = delegateOne(run, w, false, null, workerPayload);
                    run.recordSuccess(w.subtask(), result);
                } catch (Exception e) {
                    handleExecutionFailure(run, w, e, false, null);
                } finally {
                    workerLatch.countDown();
                }
            }, "worker-" + run.runId + "-" + w.agentId());
            workerThread.setDaemon(true);
            workerThread.start();
        }
        try {
            workerLatch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
    }

    /** 流水线：数据流经 Agent 链，每一步的输出作为下一步的输入。
     * 适合数据转换链（清洗→提取→预测→格式化）。
     */
    private void executePipeline(IntentRun run, List<SubtaskAssignment> assignments) {
        Object previousOutput = null;
        for (SubtaskAssignment a : assignments) {
            run.setStatus(RunStatus.RUNNING);
            run.setCurrentSubtask(a.subtask());
            if (a.agentId() == null) {
                run.recordFailure(a.subtask(), "No matching teammate");
                break;
            }
            try {
                var payload = new java.util.HashMap<String, Object>();
                if (previousOutput != null) {
                    payload.put("pipeline_input", previousOutput);
                }
                String result = delegateOne(run, a, false, null, payload);
                run.recordSuccess(a.subtask(), result);
                previousOutput = result;
            } catch (Exception e) {
                handleExecutionFailure(run, a, e, false, null);
                break;
            }
        }
        run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
    }

    private void handleExecutionFailure(IntentRun run, SubtaskAssignment a, Exception e, boolean reassigned, String reassignedFrom) {
        recordExecutionFailure(run, a, e, reassigned, reassignedFrom);
        logger.warn("Subtask '{}' failed for {}: {} — attempting reassignment",
            a.subtask(), a.agentId(), e.getMessage());
        var retry = findAlternative(a, a.agentId());
        if (retry != null && retry.agentId() != null) {
            try {
                String result = delegateOne(run, retry, true, a.agentId());
                run.recordSuccess(retry.subtask(), result + " (reassigned from " + a.agentId() + ")");
            } catch (Exception e2) {
                recordExecutionFailure(run, retry, e2, true, a.agentId());
                run.recordFailure(retry.subtask(), "Reassignment failed: " + e2.getMessage());
            }
        } else {
            run.recordFailure(a.subtask(), "No alternative teammate available");
        }
    }


    private DelegatedTask createDelegatedTaskIfRecommended(String intent, String runId, DelegationDecision decision) {
        if (decision == null || !decision.recommended()) return null;
        DelegatedTaskEnvelope envelope = DelegatedTaskEnvelope.of(intent, runId, decision);
        return delegatedTaskStore.createPending(envelope);
    }

    private static List<SubtaskAssignment> attachDelegatedTask(List<SubtaskAssignment> assignments, DelegatedTask task) {
        if (task == null || assignments == null) return assignments != null ? assignments : List.of();
        return assignments.stream().map(a -> a.withDelegatedTask(task)).toList();
    }

    private String delegateOne(IntentRun run, SubtaskAssignment a, boolean reassigned, String reassignedFrom) throws Exception {
        return delegateOne(run, a, reassigned, reassignedFrom, Map.of());
    }

    private String delegateOne(IntentRun run, SubtaskAssignment a, boolean reassigned, String reassignedFrom, Map<String, Object> extraPayload) throws Exception {
        if (a.agentId() == null) {
            throw new RuntimeException("No agent assigned");
        }

        long started = System.currentTimeMillis();
        AgentTrace trace = tenantContext.getObservability().startTrace(a.agentId(), run.runId, a.subtask())
            .meta("intent", run.intent)
            .meta("score", a.score())
            .meta("matched_skills", a.matchedSkills())
            .meta("reassigned", reassigned);
        if (a.teamId() != null) trace.meta("team_id", a.teamId());
        if (a.teamName() != null) trace.meta("team_name", a.teamName());
        if (run.preferredTeamId != null) trace.meta("preferred_team_id", run.preferredTeamId);
        if (run.delegationDecision != null && run.delegationDecision.recommended()) {
            trace.meta("delegation_recommended", true);
            trace.meta("delegation_reason", run.delegationDecision.reason());
            trace.meta("context_pressure", run.delegationDecision.contextPressure().toMap());
            trace.meta("suggested_team_id", run.delegationDecision.suggestedTeamId());
            trace.meta("suggested_profile", run.delegationDecision.suggestedProfile());
        }
        if (reassignedFrom != null) trace.meta("reassigned_from", reassignedFrom);

        var bus = tenantContext.getTenantBus();
        if (!bus.isRegistered(a.agentId())) {
            RuntimeException e = new RuntimeException("Agent " + a.agentId() + " not on bus");
            trace.step(AgentTrace.Step.error(e.getMessage()));
            trace.end(AgentTrace.Status.FAILED);
            tenantContext.getObservability().completeTrace(trace);
            run.recordAttempt(IntentAttempt.failure(a, reassigned, reassignedFrom, trace.getTraceId(), System.currentTimeMillis() - started, e.getMessage()));
            throw e;
        }

        var payload = new java.util.HashMap<String, Object>(Map.of(
            "run_id", run.runId,
            "subtask", a.subtask(),
            "score", a.score(),
            "matched_skills", a.matchedSkills(),
            "team_id", a.teamId() != null ? a.teamId() : "",
            "team_name", a.teamName() != null ? a.teamName() : "",
            "preferred_team_id", run.preferredTeamId != null ? run.preferredTeamId : "",
            "reassigned", reassigned,
            "reassigned_from", reassignedFrom != null ? reassignedFrom : ""
        ));
        payload.putAll(extraPayload);

        var msg = AgentMessage.builder(tenantContext.getTenantId(), a.agentId(), AgentMessage.Type.REQUEST)
            .action("intent_subtask")
            .payload(payload)
            .timeoutMs(60_000L)
            .build();

        trace.step(AgentTrace.Step.decision(
            "Assigned subtask to " + a.agentId() + " (score=" + String.format("%.2f", a.score()) + ")",
            Math.min(0.99, Math.max(0.05, a.score() / 5.0)),
            reassignedFrom != null ? List.of("original=" + reassignedFrom) : List.of()
        ));

        try {
            var reply = bus.sendAndWait(msg, 60_000L);
            String result = reply.getResultText() != null ? reply.getResultText() : "";
            trace.step(AgentTrace.Step.toolResult("tenant_bus", result, System.currentTimeMillis() - started));
            trace.end(AgentTrace.Status.SUCCESS);
            tenantContext.getObservability().completeTrace(trace);
            tenantContext.getEvolutionEngine().recordSuccess(a.agentId(), successPattern(a.subtask()),
                "Intent subtask succeeded in run " + run.runId);
            run.recordAttempt(IntentAttempt.success(a, reassigned, reassignedFrom, trace.getTraceId(), System.currentTimeMillis() - started));
            return result;
        } catch (Exception e) {
            trace.step(AgentTrace.Step.error(e.getMessage()));
            trace.end(classifyTraceStatus(e));
            tenantContext.getObservability().completeTrace(trace);
            run.recordAttempt(IntentAttempt.failure(a, reassigned, reassignedFrom, trace.getTraceId(), System.currentTimeMillis() - started, e.getMessage()));
            throw e;
        }
    }

    private void recordExecutionFailure(IntentRun run, SubtaskAssignment a, Exception e, boolean reassigned, String reassignedFrom) {
        if (a.agentId() == null) return;
        try {
            var failure = new FailureCase.Builder(a.agentId(), a.subtask(), e.getMessage() != null ? e.getMessage() : e.toString())
                .expectedOutcome("Complete intent subtask for run " + run.runId)
                .rootCause(classifyRootCause(e))
                .severity(reassigned ? FailureCase.Severity.HIGH : FailureCase.Severity.MEDIUM)
                .lesson("Intent subtask failed; prefer healthier or more specialized agents for similar work")
                .contextHint("intent", run.intent)
                .contextHint("role", a.roleName() != null ? a.roleName() : "")
                .contextHint("team_id", a.teamId() != null ? a.teamId() : "")
                .contextHint("reassigned", String.valueOf(reassigned));
            if (reassignedFrom != null) failure.contextHint("reassigned_from", reassignedFrom);
            tenantContext.getEvolutionEngine().recordFailure(failure.build());
        } catch (Exception ignored) {
            // Learning feedback must never break orchestration.
        }
    }

    private static FailureCase.RootCause classifyRootCause(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (e instanceof TenantBus.TimeoutException || msg.contains("timeout") || msg.contains("within")) {
            return FailureCase.RootCause.TIMEOUT;
        }
        if (msg.contains("not on bus") || msg.contains("receiver not found") || msg.contains("no handler")) {
            return FailureCase.RootCause.EXTERNAL_FAILURE;
        }
        if (msg.contains("permission") || msg.contains("denied")) {
            return FailureCase.RootCause.PERMISSION_DENIED;
        }
        return FailureCase.RootCause.UNKNOWN;
    }

    private static AgentTrace.Status classifyTraceStatus(Exception e) {
        return classifyRootCause(e) == FailureCase.RootCause.TIMEOUT
            ? AgentTrace.Status.TIMED_OUT
            : AgentTrace.Status.FAILED;
    }

    private static String successPattern(String subtask) {
        if (subtask == null || subtask.isBlank()) return "intent-subtask";
        String normalized = subtask.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    /** 获取运行状态。 */
    public IntentRun getRun(String runId) {
        return runs.get(runId);
    }

    public List<IntentRun> listRuns() {
        return runs.values().stream()
            .sorted(Comparator.comparingLong((IntentRun r) -> r.startedAt).reversed())
            .toList();
    }

    public List<IntentRun> listRuns(int limit, int offset) {
        List<IntentRun> all = listRuns();
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? all.size() : limit;
        if (safeOffset >= all.size()) return List.of();
        return all.subList(safeOffset, Math.min(all.size(), safeOffset + safeLimit));
    }

    public synchronized void saveRuns() {
        try {
            Path path = runsPath();
            Files.createDirectories(path.getParent());
            pruneRuns();
            List<Map<String, Object>> rows = listRuns().stream().map(IntentRun::toMap).toList();
            JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows);
        } catch (Exception e) {
            logger.warn("Failed to save intent runs for tenant {}: {}", tenantContext.getTenantId(), e.getMessage());
        }
    }

    private void loadRuns() {
        Path path = runsPath();
        if (!Files.exists(path)) return;
        try {
            List<Map<String, Object>> rows = JSON_MAPPER.readValue(path.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            long maxId = 0;
            for (Map<String, Object> row : rows) {
                IntentRun run = IntentRun.fromMap(row);
                runs.put(run.runId, run);
                maxId = Math.max(maxId, parseRunNumber(run.runId));
            }
            pruneRuns();
            final long loadedMaxId = maxId;
            TASK_ID_GEN.updateAndGet(v -> Math.max(v, loadedMaxId));
            logger.info("Tenant {}: loaded {} persisted intent runs", tenantContext.getTenantId(), runs.size());
        } catch (Exception e) {
            logger.warn("Failed to load intent runs for tenant {}: {}", tenantContext.getTenantId(), e.getMessage());
        }
    }


    private void pruneRuns() {
        int maxRuns = maxPersistedRuns();
        if (maxRuns <= 0 || runs.size() <= maxRuns) return;
        Set<String> keep = listRuns().stream()
            .limit(maxRuns)
            .map(r -> r.runId)
            .collect(java.util.stream.Collectors.toSet());
        runs.keySet().removeIf(id -> !keep.contains(id));
    }

    private static int maxPersistedRuns() {
        return Integer.getInteger("hermes.intent.runs.max", DEFAULT_MAX_PERSISTED_RUNS);
    }

    private Path runsPath() {
        return tenantContext.getTenantDir().resolve("state").resolve(RUNS_FILE);
    }

    private static long parseRunNumber(String runId) {
        if (runId == null) return 0;
        try { return Long.parseLong(runId.replaceFirst("^run_", "")); }
        catch (Exception ignored) { return 0; }
    }

    // ======== Decomposition ========

    /**
     * Heuristic task decomposition: split on common delimiters and
     * identify discrete subtasks.
     */
    static List<String> decompose(String intent) {
        if (intent == null || intent.isBlank()) return List.of();
        // Split on common separators
        String[] parts = intent.split("[,;]\\s*(?:and\\s+)?|\\s+then\\s+|\\s+and\\s+(?=also|then)");
        List<String> subtasks = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                subtasks.add(trimmed);
            }
        }
        // If decomposition produced nothing useful, use the whole intent as one subtask
        if (subtasks.isEmpty()) {
            subtasks.add(intent);
        }
        return subtasks;
    }

    // ======== Agent Matching ========

    /**
     * Find the best teammate for a subtask by scoring skill/role overlap.
     * Legacy static API used by tests and callers that do not need tenant-aware
     * availability/reliability/evolution signals.
     */
    static SubtaskAssignment findBestMatch(String subtask, Map<String, AgentRuntimeProfile> roles) {
        return findBestMatch(subtask, roles, null);
    }

    /**
     * Find the best teammate for a subtask using organization-aware capability scoring.
     */
    static SubtaskAssignment findBestMatch(String subtask, Map<String, AgentRuntimeProfile> roles, TenantContext ctx) {
        return findBestMatch(subtask, roles, ctx, null);
    }

    /**
     * Find the best teammate for a subtask using organization-aware capability scoring.
     */
    static SubtaskAssignment findBestMatch(String subtask, Map<String, AgentRuntimeProfile> roles, TenantContext ctx, String preferredTeamId) {
        return findBestMatch(subtask, roles, ctx, preferredTeamId, null);
    }

    /**
     * Find the best teammate for a subtask with an optional agent hint.
     * If hint matches an available agent, it is strongly preferred.
     */
    static SubtaskAssignment findBestMatch(String subtask, Map<String, AgentRuntimeProfile> roles, TenantContext ctx, String preferredTeamId, String agentHint) {
        // If hint directly matches an agent, boost its score significantly
        if (agentHint != null && !agentHint.isBlank() && roles.containsKey(agentHint)) {
            var hintedRole = roles.get(agentHint);
            var score = CapabilityScorer.score(subtask, agentHint, hintedRole, ctx, preferredTeamId);
            // Boost hinted agent by 2.0 to ensure it wins unless severely mismatched
            double boostedScore = score.total() + 2.0;
            TeamRuntime team = teamForAgent(ctx, agentHint, preferredTeamId);
            return new SubtaskAssignment(subtask, agentHint, score.roleName(), boostedScore, score.matchedSkills(), score.components(), teamId(team), teamName(team));
        }

        CapabilityScorer.CapabilityScore best = null;
        for (var entry : roles.entrySet()) {
            var score = CapabilityScorer.score(subtask, entry.getKey(), entry.getValue(), ctx, preferredTeamId);
            if (best == null || score.total() > best.total()) {
                best = score;
            }
        }

        if (best == null || best.total() < 0.1) {
            return new SubtaskAssignment(subtask, null, null, 0.0, List.of());
        }
        TeamRuntime team = teamForAgent(ctx, best.agentId(), preferredTeamId);
        return new SubtaskAssignment(subtask, best.agentId(), best.roleName(), best.total(), best.matchedSkills(), best.components(), teamId(team), teamName(team));
    }

    /**
     * Find an alternative agent (excluding the original).
     */
    private SubtaskAssignment findAlternative(SubtaskAssignment original, String excludeAgentId) {
        var roles = tenantContext.listAgentRoles();
        Map<String, AgentRuntimeProfile> filtered = new java.util.HashMap<>(roles);
        filtered.remove(excludeAgentId);
        if (filtered.isEmpty()) return null;
        return findBestMatch(original.subtask(), filtered, tenantContext, original.teamId());
    }


    private String normalizeTeamId(String preferredTeamId) {
        if (preferredTeamId == null || preferredTeamId.isBlank()) return null;
        return tenantContext.getTeamManager().getTeam(preferredTeamId) != null ? preferredTeamId : preferredTeamId;
    }

    private String teamName(String teamId) {
        TeamRuntime team = teamId == null ? null : tenantContext.getTeamManager().getTeam(teamId);
        return teamName(team);
    }

    private TeamRuntime teamForAgent(String agentId, String preferredTeamId) {
        return teamForAgent(tenantContext, agentId, preferredTeamId);
    }

    private static TeamRuntime teamForAgent(TenantContext ctx, String agentId, String preferredTeamId) {
        if (ctx == null || agentId == null) return null;
        try {
            if (preferredTeamId != null && !preferredTeamId.isBlank()) {
                TeamRuntime preferred = ctx.getTeamManager().getTeam(preferredTeamId);
                if (preferred != null && preferred.hasMember(agentId)) return preferred;
            }
            var teams = ctx.getTeamManager().getTeamsForAgent(agentId);
            return teams.isEmpty() ? null : teams.get(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String teamId(TeamRuntime team) { return team != null ? team.getTeamId() : null; }
    private static String teamName(TeamRuntime team) { return team != null ? team.getName() : null; }

    // ======== Data Classes ========

    public enum RunStatus { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED, INTERRUPTED }

    public record SubtaskAssignment(
        String subtask,
        String agentId,
        String roleName,
        double score,
        List<String> matchedSkills,
        Map<String, Double> scoreComponents,
        String teamId,
        String teamName,
        DelegationDecision delegationDecision,
        DelegatedTask delegatedTask
    ) {
        public SubtaskAssignment(String subtask, String agentId, String roleName, double score, List<String> matchedSkills) {
            this(subtask, agentId, roleName, score, matchedSkills, Map.of());
        }

        public SubtaskAssignment(String subtask, String agentId, String roleName, double score, List<String> matchedSkills, Map<String, Double> scoreComponents) {
            this(subtask, agentId, roleName, score, matchedSkills, scoreComponents, null, null);
        }

        public SubtaskAssignment(String subtask, String agentId, String roleName, double score, List<String> matchedSkills, Map<String, Double> scoreComponents, String teamId, String teamName) {
            this(subtask, agentId, roleName, score, matchedSkills, scoreComponents, teamId, teamName, null, null);
        }

        public SubtaskAssignment withDelegation(DelegationDecision decision) {
            return new SubtaskAssignment(subtask, agentId, roleName, score, matchedSkills, scoreComponents, teamId, teamName, decision, null);
        }

        public SubtaskAssignment withDelegation(DelegationDecision decision, DelegatedTask task) {
            return new SubtaskAssignment(subtask, agentId, roleName, score, matchedSkills, scoreComponents, teamId, teamName, decision, task);
        }

        public SubtaskAssignment withDelegatedTask(DelegatedTask task) {
            return new SubtaskAssignment(subtask, agentId, roleName, score, matchedSkills, scoreComponents, teamId, teamName, delegationDecision, task);
        }

        /** Create a copy with additional payload metadata (for pipeline/master-worker data passing). */
        public SubtaskAssignment withPayload(Map<String, Object> payload) {
            Map<String, Object> merged = new LinkedHashMap<>(toMap());
            merged.putAll(payload);
            return this; // Payload is passed via the message, not stored in the record
        }

        @SuppressWarnings("unchecked")
        public static SubtaskAssignment fromMap(Map<String, Object> m) {
            Map<String, Double> components = new LinkedHashMap<>();
            Object rawComponents = m.get("score_components");
            if (rawComponents instanceof Map<?, ?> cm) {
                cm.forEach((k, v) -> components.put(String.valueOf(k), v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v))));
            }
            List<String> skills = ((List<Object>) m.getOrDefault("matched_skills", List.of())).stream().map(String::valueOf).toList();
            Object agent = m.get("agent");
            String agentId = agent == null || "(unassigned)".equals(String.valueOf(agent)) ? null : String.valueOf(agent);
            DelegationDecision delegation = delegationFromMap(m);
            return new SubtaskAssignment(
                String.valueOf(m.getOrDefault("subtask", "")),
                agentId,
                String.valueOf(m.getOrDefault("role", "")),
                m.get("score") instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(m.getOrDefault("score", "0"))),
                skills,
                components,
                stringOrNull(m.get("team_id")),
                stringOrNull(m.get("team_name")),
                delegation,
                null
            );
        }

        @SuppressWarnings("unchecked")
        public static DelegationDecision delegationFromMap(Map<String, Object> m) {
            if (!Boolean.parseBoolean(String.valueOf(m.getOrDefault("delegation_recommended", "false")))
                && !m.containsKey("context_pressure")) return null;
            ContextPressureReport report = ContextPressureReport.none();
            Object cp = m.get("context_pressure");
            if (cp instanceof Map<?, ?> cm) {
                Object rawSignals = cm.get("signals");
                Object rawReasons = cm.get("reasons");
                List<String> signals = rawSignals instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
                List<String> reasons = rawReasons instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
                report = new ContextPressureReport(
                    signals,
                    doubleValue(cm.get("score")),
                    cm.get("level") != null ? String.valueOf(cm.get("level")) : ContextPressureReport.levelFor(doubleValue(cm.get("score"))),
                    Boolean.parseBoolean(String.valueOf(cm.get("compacted"))),
                    Boolean.parseBoolean(String.valueOf(cm.get("critical_path"))),
                    Boolean.parseBoolean(String.valueOf(cm.get("near_limit"))),
                    Boolean.parseBoolean(String.valueOf(cm.get("long_running"))),
                    Boolean.parseBoolean(String.valueOf(cm.get("high_complexity"))),
                    reasons
                );
            }
            return new DelegationDecision(
                Boolean.parseBoolean(String.valueOf(m.getOrDefault("delegation_recommended", "false"))),
                String.valueOf(m.getOrDefault("delegation_reason", "")),
                report,
                stringOrNull(m.get("suggested_team_id")),
                stringOrNull(m.get("suggested_profile"))
            );
        }

        private static double doubleValue(Object value) {
            if (value == null) return 0.0;
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        }

        private static String stringOrNull(Object value) {
            if (value == null) return null;
            String s = String.valueOf(value);
            return s.isBlank() || "null".equals(s) ? null : s;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subtask", subtask);
            m.put("agent", agentId != null ? agentId : "(unassigned)");
            m.put("role", roleName != null ? roleName : "");
            m.put("score", score);
            m.put("matched_skills", matchedSkills != null ? matchedSkills : List.of());
            m.put("score_components", scoreComponents != null ? scoreComponents : Map.of());
            m.put("team_id", teamId);
            m.put("team_name", teamName);
            putDelegation(m, delegationDecision, delegatedTask);
            return m;
        }
    }

    private static void putDelegation(Map<String, Object> m, DelegationDecision decision) {
        putDelegation(m, decision, null);
    }

    private static void putDelegation(Map<String, Object> m, DelegationDecision decision, DelegatedTask delegatedTask) {
        if (decision == null) return;
        DelegationDecision d = decision;
        m.put("delegation_recommended", d.recommended());
        m.put("delegation_reason", d.reason());
        m.put("context_pressure", d.contextPressure() != null ? d.contextPressure().toMap() : ContextPressureReport.none().toMap());
        m.put("suggested_team_id", d.suggestedTeamId());
        m.put("suggested_profile", d.suggestedProfile());
        if (delegatedTask != null) {
            m.put("delegated_task_id", delegatedTask.taskId());
            m.put("delegated_task_status", delegatedTask.status().name());
        }
        if (d.recommended()) {
            DelegatedTaskEnvelope envelope = delegatedTask != null
                ? delegatedTask.envelope()
                : DelegatedTaskEnvelope.of(String.valueOf(m.getOrDefault("subtask", "")), null, d);
            m.put("delegated_task_envelope", envelope.toMap());
        }
    }

    public record IntentPlan(
        String intent,
        List<SubtaskAssignment> assignments,
        String status,
        String preferredTeamId,
        String preferredTeamName,
        DelegationDecision delegationDecision,
        DelegatedTask delegatedTask,
        CollaborationPattern collaborationPattern
    ) {
        public IntentPlan(String intent, List<SubtaskAssignment> assignments, String status) {
            this(intent, assignments, status, null, null, null, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentPlan(String intent, List<SubtaskAssignment> assignments, String status, String preferredTeamId, String preferredTeamName) {
            this(intent, assignments, status, preferredTeamId, preferredTeamName, null, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentPlan(String intent, List<SubtaskAssignment> assignments, String status, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision, DelegatedTask delegatedTask) {
            this(intent, assignments, status, preferredTeamId, preferredTeamName, delegationDecision, delegatedTask, CollaborationPattern.SEQUENTIAL);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", intent);
            m.put("status", status);
            m.put("preferred_team_id", preferredTeamId);
            m.put("preferred_team_name", preferredTeamName);
            m.put("collaboration_pattern", collaborationPattern != null ? collaborationPattern.name() : "SEQUENTIAL");
            putDelegation(m, delegationDecision, delegatedTask);
            m.put("subtasks", assignments.stream().map(SubtaskAssignment::toMap).toList());
            return m;
        }
    }


    public record IntentAttempt(
        String subtask,
        String agentId,
        String roleName,
        double score,
        boolean reassigned,
        String reassignedFrom,
        String traceId,
        boolean success,
        String error,
        long latencyMs,
        long timestamp,
        String teamId,
        String teamName
    ) {
        static IntentAttempt success(SubtaskAssignment a, boolean reassigned, String reassignedFrom, String traceId, long latencyMs) {
            return new IntentAttempt(a.subtask(), a.agentId(), a.roleName(), a.score(), reassigned, reassignedFrom, traceId, true, null, latencyMs, System.currentTimeMillis(), a.teamId(), a.teamName());
        }

        static IntentAttempt failure(SubtaskAssignment a, boolean reassigned, String reassignedFrom, String traceId, long latencyMs, String error) {
            return new IntentAttempt(a.subtask(), a.agentId(), a.roleName(), a.score(), reassigned, reassignedFrom, traceId, false, error, latencyMs, System.currentTimeMillis(), a.teamId(), a.teamName());
        }
        public static IntentAttempt fromMap(Map<String, Object> m) {
            return new IntentAttempt(
                String.valueOf(m.getOrDefault("subtask", "")),
                stringOrNull(m.get("agent")),
                stringOrNull(m.get("role")),
                doubleValue(m.get("score")),
                Boolean.parseBoolean(String.valueOf(m.getOrDefault("reassigned", "false"))),
                stringOrNull(m.get("reassigned_from")),
                stringOrNull(m.get("trace_id")),
                Boolean.parseBoolean(String.valueOf(m.getOrDefault("success", "false"))),
                stringOrNull(m.get("error")),
                longValue(m.get("latency_ms")),
                longValue(m.get("timestamp")),
                stringOrNull(m.get("team_id")),
                stringOrNull(m.get("team_name"))
            );
        }

        private static String stringOrNull(Object value) {
            if (value == null) return null;
            String s = String.valueOf(value);
            return "null".equals(s) ? null : s;
        }

        private static long longValue(Object value) {
            if (value == null) return 0L;
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        }

        private static double doubleValue(Object value) {
            if (value == null) return 0.0;
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        }


        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subtask", subtask);
            m.put("agent", agentId);
            m.put("role", roleName);
            m.put("score", score);
            m.put("reassigned", reassigned);
            m.put("reassigned_from", reassignedFrom);
            m.put("trace_id", traceId);
            m.put("success", success);
            m.put("error", error);
            m.put("latency_ms", latencyMs);
            m.put("timestamp", timestamp);
            m.put("team_id", teamId);
            m.put("team_name", teamName);
            return m;
        }
    }

    private static DelegationDecision firstDelegation(List<SubtaskAssignment> assignments) {
        if (assignments == null) return null;
        return assignments.stream()
            .map(SubtaskAssignment::delegationDecision)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private static DelegatedTask firstDelegatedTask(List<SubtaskAssignment> assignments) {
        if (assignments == null) return null;
        return assignments.stream()
            .map(SubtaskAssignment::delegatedTask)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    public static class IntentRun {
        public final String runId;
        public final String intent;
        public final List<SubtaskAssignment> assignments;
        public final String parentRunId;
        public final String controlAction;
        public final String preferredTeamId;
        public final String preferredTeamName;
        public final DelegationDecision delegationDecision;
        public final DelegatedTask delegatedTask;
        public final CollaborationPattern collaborationPattern;
        public final Map<String, String> successes = new ConcurrentHashMap<>();
        public final Map<String, String> failures = new ConcurrentHashMap<>();
        public final List<IntentAttempt> attempts = Collections.synchronizedList(new ArrayList<>());
        public volatile RunStatus status = RunStatus.PENDING;
        public volatile String currentSubtask = null;
        public final long startedAt;
        public volatile long completedAt = 0;

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments) {
            this(runId, intent, assignments, null, "execute");
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction) {
            this(runId, intent, assignments, parentRunId, controlAction, null, null, null, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName) {
            this(runId, intent, assignments, parentRunId, controlAction, preferredTeamId, preferredTeamName, null, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision) {
            this(runId, intent, assignments, parentRunId, controlAction, preferredTeamId, preferredTeamName, delegationDecision, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision, DelegatedTask delegatedTask) {
            this(runId, intent, assignments, parentRunId, controlAction, preferredTeamId, preferredTeamName, delegationDecision, delegatedTask, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision, DelegatedTask delegatedTask, CollaborationPattern collaborationPattern) {
            this(runId, intent, assignments, parentRunId, controlAction, System.currentTimeMillis(), 0L, RunStatus.PENDING, null, preferredTeamId, preferredTeamName, delegationDecision, delegatedTask, collaborationPattern);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, long startedAt, long completedAt, RunStatus status, String currentSubtask) {
            this(runId, intent, assignments, parentRunId, controlAction, startedAt, completedAt, status, currentSubtask, null, null, null, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, long startedAt, long completedAt, RunStatus status, String currentSubtask, String preferredTeamId, String preferredTeamName) {
            this(runId, intent, assignments, parentRunId, controlAction, startedAt, completedAt, status, currentSubtask, preferredTeamId, preferredTeamName, null, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, long startedAt, long completedAt, RunStatus status, String currentSubtask, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision) {
            this(runId, intent, assignments, parentRunId, controlAction, startedAt, completedAt, status, currentSubtask, preferredTeamId, preferredTeamName, delegationDecision, null, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, long startedAt, long completedAt, RunStatus status, String currentSubtask, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision, DelegatedTask delegatedTask) {
            this(runId, intent, assignments, parentRunId, controlAction, startedAt, completedAt, status, currentSubtask, preferredTeamId, preferredTeamName, delegationDecision, delegatedTask, CollaborationPattern.SEQUENTIAL);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, long startedAt, long completedAt, RunStatus status, String currentSubtask, String preferredTeamId, String preferredTeamName, DelegationDecision delegationDecision, DelegatedTask delegatedTask, CollaborationPattern collaborationPattern) {
            this.runId = runId;
            this.intent = intent;
            this.assignments = List.copyOf(assignments);
            this.parentRunId = parentRunId;
            this.controlAction = controlAction != null ? controlAction : "execute";
            this.preferredTeamId = preferredTeamId;
            this.preferredTeamName = preferredTeamName;
            this.delegationDecision = delegationDecision != null ? delegationDecision : firstDelegation(assignments);
            this.delegatedTask = delegatedTask != null ? delegatedTask : firstDelegatedTask(assignments);
            this.collaborationPattern = collaborationPattern != null ? collaborationPattern : CollaborationPattern.SEQUENTIAL;
            this.startedAt = startedAt > 0 ? startedAt : System.currentTimeMillis();
            this.completedAt = completedAt;
            this.status = status != null ? status : RunStatus.PENDING;
            this.currentSubtask = currentSubtask;
        }

        public synchronized void recordSuccess(String subtask, String result) {
            successes.put(subtask, result);
        }

        public synchronized void recordFailure(String subtask, String error) {
            failures.put(subtask, error);
        }

        public void recordAttempt(IntentAttempt attempt) {
            attempts.add(attempt);
        }

        public void setStatus(RunStatus s) {
            this.status = s;
            if (s == RunStatus.COMPLETED || s == RunStatus.PARTIAL || s == RunStatus.FAILED) {
                this.completedAt = System.currentTimeMillis();
            }
        }

        public void setCurrentSubtask(String s) { this.currentSubtask = s; }

        public List<SubtaskAssignment> assignments() { return assignments; }
        public Map<String, String> successes() { return Map.copyOf(successes); }
        public Map<String, String> failures() { return Map.copyOf(failures); }
        public List<IntentAttempt> attempts() {
            synchronized (attempts) { return List.copyOf(attempts); }
        }

        @SuppressWarnings("unchecked")
        public static IntentRun fromMap(Map<String, Object> m) {
            List<SubtaskAssignment> assignments = ((List<Object>) m.getOrDefault("assignments", List.of())).stream()
                .map(x -> SubtaskAssignment.fromMap((Map<String, Object>) x))
                .toList();
            RunStatus status = RunStatus.PENDING;
            try { status = RunStatus.valueOf(String.valueOf(m.getOrDefault("status", "PENDING"))); } catch (Exception ignored) {}
            String currentSubtask = stringOrNull(m.get("current_subtask"));
            boolean interrupted = status == RunStatus.PENDING || status == RunStatus.RUNNING;
            IntentRun run = new IntentRun(
                String.valueOf(m.get("run_id")),
                String.valueOf(m.getOrDefault("intent", "")),
                assignments,
                stringOrNull(m.get("parent_run_id")),
                String.valueOf(m.getOrDefault("control_action", "execute")),
                longValue(m.get("started_at")),
                interrupted ? System.currentTimeMillis() : longValue(m.get("completed_at")),
                interrupted ? RunStatus.INTERRUPTED : status,
                currentSubtask,
                stringOrNull(m.get("preferred_team_id")),
                stringOrNull(m.get("preferred_team_name")),
                SubtaskAssignment.delegationFromMap(m),
                null,
                parsePattern(m.get("collaboration_pattern"))
            );
            Object successes = m.get("successes");
            if (successes instanceof Map<?, ?> sm) sm.forEach((k, v) -> run.successes.put(String.valueOf(k), String.valueOf(v)));
            Object failures = m.get("failures");
            if (failures instanceof Map<?, ?> fm) fm.forEach((k, v) -> run.failures.put(String.valueOf(k), String.valueOf(v)));
            for (Object x : (List<Object>) m.getOrDefault("attempts", List.of())) {
                run.attempts.add(IntentAttempt.fromMap((Map<String, Object>) x));
            }
            if (interrupted) {
                String subtask = currentSubtask;
                if ((subtask == null || subtask.isBlank()) && !assignments.isEmpty()) subtask = assignments.get(0).subtask();
                if (subtask == null || subtask.isBlank()) subtask = "run";
                run.failures.putIfAbsent(subtask, "Interrupted by process restart before completion");
            }
            return run;
        }

        private static String stringOrNull(Object value) {
            if (value == null) return null;
            String s = String.valueOf(value);
            return "null".equals(s) ? null : s;
        }

        private static long longValue(Object value) {
            if (value == null) return 0L;
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", runId);
            m.put("intent", intent);
            m.put("parent_run_id", parentRunId);
            m.put("control_action", controlAction);
            m.put("preferred_team_id", preferredTeamId);
            m.put("preferred_team_name", preferredTeamName);
            m.put("collaboration_pattern", collaborationPattern != null ? collaborationPattern.name() : "SEQUENTIAL");
            putDelegation(m, delegationDecision, delegatedTask);
            m.put("status", status.name());
            m.put("current_subtask", currentSubtask);
            m.put("subtasks_total", assignments.size());
            m.put("assignments", assignments.stream().map(SubtaskAssignment::toMap).toList());
            m.put("succeeded", successes.size());
            m.put("failed", failures.size());
            m.put("successes", successes);
            m.put("failures", failures);
            m.put("attempts", attempts().stream().map(IntentAttempt::toMap).toList());
            m.put("started_at", startedAt);
            m.put("completed_at", completedAt);
            return m;
        }

        private static CollaborationPattern parsePattern(Object value) {
            if (value == null) return CollaborationPattern.SEQUENTIAL;
            try { return CollaborationPattern.valueOf(String.valueOf(value)); }
            catch (Exception e) { return CollaborationPattern.SEQUENTIAL; }
        }
    }
}
