package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
public class IntentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(IntentOrchestrator.class);
    private static final AtomicLong TASK_ID_GEN = new AtomicLong();

    private final TenantContext tenantContext;
    private final TaskOrchestrator taskOrchestrator;
    private final ConcurrentHashMap<String, IntentRun> runs = new ConcurrentHashMap<>();

    public IntentOrchestrator(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
        this.taskOrchestrator = tenantContext.getTaskOrchestrator();
    }

    /**
     * Decompose a task and find the best teammates for each subtask.
     * Returns an IntentPlan describing who would do what (without executing).
     */
    public IntentPlan plan(String intent) {
        tenantContext.initCollaboration();
        var roles = tenantContext.listAgentRoles();

        if (roles.isEmpty()) {
            return new IntentPlan(intent, List.of(), "no teammates available");
        }

        // Heuristic decomposition: split on conjunctions and key verbs
        List<String> subtasks = decompose(intent);

        // For each subtask, find the best agent
        List<SubtaskAssignment> assignments = new ArrayList<>();
        for (String subtask : subtasks) {
            SubtaskAssignment best = findBestMatch(subtask, roles, tenantContext);
            assignments.add(best);
        }

        return new IntentPlan(intent, assignments, "planned");
    }

    /**
     * Execute an intent plan. Returns immediately with a run ID.
     * Use {@link #getRun(String)} to poll for status.
     */
    public IntentRun execute(String intent) {
        IntentPlan plan = plan(intent);
        String runId = "run_" + TASK_ID_GEN.incrementAndGet();
        IntentRun run = new IntentRun(runId, intent, plan.assignments());
        runs.put(runId, run);

        // Execute each assignment in sequence (simplicity over parallelism for now)
        Thread t = new Thread(() -> {
            for (SubtaskAssignment a : plan.assignments()) {
                run.setStatus(RunStatus.RUNNING);
                run.setCurrentSubtask(a.subtask());
                if (a.agentId() == null) {
                    run.recordFailure(a.subtask(), "No matching teammate");
                    continue;
                }
                try {
                    String result = delegateOne(a);
                    run.recordSuccess(a.subtask(), result);
                } catch (Exception e) {
                    // Failure: try to reassign once
                    logger.warn("Subtask '{}' failed for {}: {} — attempting reassignment",
                        a.subtask(), a.agentId(), e.getMessage());
                    var retry = findAlternative(a, a.agentId());
                    if (retry != null) {
                        try {
                            String result = delegateOne(retry);
                            run.recordSuccess(retry.subtask(), result + " (reassigned from " + a.agentId() + ")");
                        } catch (Exception e2) {
                            run.recordFailure(retry.subtask(), "Reassignment failed: " + e2.getMessage());
                        }
                    } else {
                        run.recordFailure(a.subtask(), "No alternative teammate available");
                    }
                }
            }
            run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
        }, "intent-orchestrator-" + runId);
        t.setDaemon(true);
        t.start();

        return run;
    }

    private String delegateOne(SubtaskAssignment a) throws Exception {
        if (a.agentId() == null) {
            throw new RuntimeException("No agent assigned");
        }
        var bus = tenantContext.getTenantBus();
        if (!bus.isRegistered(a.agentId())) {
            throw new RuntimeException("Agent " + a.agentId() + " not on bus");
        }
        var msg = AgentMessage.builder(tenantContext.getTenantId(), a.agentId(), AgentMessage.Type.REQUEST)
            .action("intent_subtask")
            .payload(Map.of(
                "subtask", a.subtask(),
                "score", a.score(),
                "matched_skills", a.matchedSkills()
            ))
            .timeoutMs(60_000L)
            .build();
        var reply = bus.sendAndWait(msg, 60_000L);
        return reply.getResultText();
    }

    public IntentRun getRun(String runId) {
        return runs.get(runId);
    }

    public List<IntentRun> listRuns() {
        return new ArrayList<>(runs.values());
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
    static SubtaskAssignment findBestMatch(String subtask, Map<String, AgentRole> roles) {
        return findBestMatch(subtask, roles, null);
    }

    /**
     * Find the best teammate for a subtask using organization-aware capability scoring.
     */
    static SubtaskAssignment findBestMatch(String subtask, Map<String, AgentRole> roles, TenantContext ctx) {
        CapabilityScorer.CapabilityScore best = null;

        for (var entry : roles.entrySet()) {
            var score = CapabilityScorer.score(subtask, entry.getKey(), entry.getValue(), ctx);
            if (best == null || score.total() > best.total()) {
                best = score;
            }
        }

        if (best == null || best.total() < 0.1) {
            return new SubtaskAssignment(subtask, null, null, 0.0, List.of());
        }
        return new SubtaskAssignment(subtask, best.agentId(), best.roleName(), best.total(), best.matchedSkills());
    }

    /**
     * Find an alternative agent (excluding the original).
     */
    private SubtaskAssignment findAlternative(SubtaskAssignment original, String excludeAgentId) {
        var roles = tenantContext.listAgentRoles();
        Map<String, AgentRole> filtered = new java.util.HashMap<>(roles);
        filtered.remove(excludeAgentId);
        if (filtered.isEmpty()) return null;
        return findBestMatch(original.subtask(), filtered, tenantContext);
    }

    // ======== Data Classes ========

    public enum RunStatus { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED }

    public record SubtaskAssignment(
        String subtask,
        String agentId,
        String roleName,
        double score,
        List<String> matchedSkills
    ) {}

    public record IntentPlan(
        String intent,
        List<SubtaskAssignment> assignments,
        String status
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", intent);
            m.put("status", status);
            m.put("subtasks", assignments.stream().map(a -> Map.of(
                "subtask", a.subtask(),
                "agent", a.agentId() != null ? a.agentId() : "(unassigned)",
                "role", a.roleName() != null ? a.roleName() : "",
                "score", a.score(),
                "matched_skills", a.matchedSkills()
            )).toList());
            return m;
        }
    }

    public static class IntentRun {
        public final String runId;
        public final String intent;
        public final List<SubtaskAssignment> assignments;
        public final Map<String, String> successes = new ConcurrentHashMap<>();
        public final Map<String, String> failures = new ConcurrentHashMap<>();
        public volatile RunStatus status = RunStatus.PENDING;
        public volatile String currentSubtask = null;
        public final long startedAt = System.currentTimeMillis();
        public volatile long completedAt = 0;

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments) {
            this.runId = runId;
            this.intent = intent;
            this.assignments = List.copyOf(assignments);
        }

        public synchronized void recordSuccess(String subtask, String result) {
            successes.put(subtask, result);
        }

        public synchronized void recordFailure(String subtask, String error) {
            failures.put(subtask, error);
        }

        public void setStatus(RunStatus s) {
            this.status = s;
            if (s == RunStatus.COMPLETED || s == RunStatus.PARTIAL || s == RunStatus.FAILED) {
                this.completedAt = System.currentTimeMillis();
            }
        }

        public void setCurrentSubtask(String s) { this.currentSubtask = s; }

        public Map<String, String> successes() { return Map.copyOf(successes); }
        public Map<String, String> failures() { return Map.copyOf(failures); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", runId);
            m.put("intent", intent);
            m.put("status", status.name());
            m.put("current_subtask", currentSubtask);
            m.put("subtasks_total", assignments.size());
            m.put("succeeded", successes.size());
            m.put("failed", failures.size());
            m.put("successes", successes);
            m.put("failures", failures);
            m.put("started_at", startedAt);
            m.put("completed_at", completedAt);
            return m;
        }
    }
}
