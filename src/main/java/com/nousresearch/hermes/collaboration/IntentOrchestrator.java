package com.nousresearch.hermes.collaboration;

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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String RUNS_FILE = "intent-runs.json";
    private static final int DEFAULT_MAX_PERSISTED_RUNS = 1000;

    private final TenantContext tenantContext;
    private final TaskOrchestrator taskOrchestrator;
    private final ConcurrentHashMap<String, IntentRun> runs = new ConcurrentHashMap<>();

    public IntentOrchestrator(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
        this.taskOrchestrator = tenantContext.getTaskOrchestrator();
        loadRuns();
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
        return startRun(intent, plan.assignments(), null, "execute");
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
        return startRun(original.intent, failedAssignments, runId, "replay_failed");
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
            AgentRole role = tenantContext.getAgentRole(targetAgentId);
            if (role == null) {
                throw new IllegalArgumentException("Unknown target agent role: " + targetAgentId);
            }
            var score = CapabilityScorer.score(targetSubtask, targetAgentId, role, tenantContext);
            target = new SubtaskAssignment(targetSubtask, targetAgentId, role.getRoleName(), score.total(), score.matchedSkills(), score.components());
        } else {
            target = findAlternative(base, base.agentId());
            if (target == null || target.agentId() == null) {
                throw new IllegalStateException("No alternative teammate available for subtask: " + targetSubtask);
            }
        }
        return startRun(original.intent, List.of(target), runId, "reroute");
    }

    private IntentRun startRun(String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction) {
        String runId = "run_" + TASK_ID_GEN.incrementAndGet();
        IntentRun run = new IntentRun(runId, intent, assignments, parentRunId, controlAction);
        runs.put(runId, run);
        saveRuns();

        // Execute each assignment in sequence (simplicity over parallelism for now)
        Thread t = new Thread(() -> {
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
                    recordExecutionFailure(run, a, e, false, null);

                    // Failure: try to reassign once
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
            }
            run.setStatus(run.failures().isEmpty() ? RunStatus.COMPLETED : RunStatus.PARTIAL);
            saveRuns();
        }, "intent-orchestrator-" + runId);
        t.setDaemon(true);
        t.start();

        return run;
    }

    private String delegateOne(IntentRun run, SubtaskAssignment a, boolean reassigned, String reassignedFrom) throws Exception {
        if (a.agentId() == null) {
            throw new RuntimeException("No agent assigned");
        }

        long started = System.currentTimeMillis();
        AgentTrace trace = tenantContext.getObservability().startTrace(a.agentId(), run.runId, a.subtask())
            .meta("intent", run.intent)
            .meta("score", a.score())
            .meta("matched_skills", a.matchedSkills())
            .meta("reassigned", reassigned);
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

        var msg = AgentMessage.builder(tenantContext.getTenantId(), a.agentId(), AgentMessage.Type.REQUEST)
            .action("intent_subtask")
            .payload(Map.of(
                "run_id", run.runId,
                "subtask", a.subtask(),
                "score", a.score(),
                "matched_skills", a.matchedSkills(),
                "reassigned", reassigned,
                "reassigned_from", reassignedFrom != null ? reassignedFrom : ""
            ))
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
        return new SubtaskAssignment(subtask, best.agentId(), best.roleName(), best.total(), best.matchedSkills(), best.components());
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

    public enum RunStatus { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED, INTERRUPTED }

    public record SubtaskAssignment(
        String subtask,
        String agentId,
        String roleName,
        double score,
        List<String> matchedSkills,
        Map<String, Double> scoreComponents
    ) {
        public SubtaskAssignment(String subtask, String agentId, String roleName, double score, List<String> matchedSkills) {
            this(subtask, agentId, roleName, score, matchedSkills, Map.of());
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
            return new SubtaskAssignment(
                String.valueOf(m.getOrDefault("subtask", "")),
                agentId,
                String.valueOf(m.getOrDefault("role", "")),
                m.get("score") instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(m.getOrDefault("score", "0"))),
                skills,
                components
            );
        }


        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subtask", subtask);
            m.put("agent", agentId != null ? agentId : "(unassigned)");
            m.put("role", roleName != null ? roleName : "");
            m.put("score", score);
            m.put("matched_skills", matchedSkills != null ? matchedSkills : List.of());
            m.put("score_components", scoreComponents != null ? scoreComponents : Map.of());
            return m;
        }
    }

    public record IntentPlan(
        String intent,
        List<SubtaskAssignment> assignments,
        String status
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", intent);
            m.put("status", status);
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
        long timestamp
    ) {
        static IntentAttempt success(SubtaskAssignment a, boolean reassigned, String reassignedFrom, String traceId, long latencyMs) {
            return new IntentAttempt(a.subtask(), a.agentId(), a.roleName(), a.score(), reassigned, reassignedFrom, traceId, true, null, latencyMs, System.currentTimeMillis());
        }

        static IntentAttempt failure(SubtaskAssignment a, boolean reassigned, String reassignedFrom, String traceId, long latencyMs, String error) {
            return new IntentAttempt(a.subtask(), a.agentId(), a.roleName(), a.score(), reassigned, reassignedFrom, traceId, false, error, latencyMs, System.currentTimeMillis());
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
                longValue(m.get("timestamp"))
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
            return m;
        }
    }

    public static class IntentRun {
        public final String runId;
        public final String intent;
        public final List<SubtaskAssignment> assignments;
        public final String parentRunId;
        public final String controlAction;
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
            this(runId, intent, assignments, parentRunId, controlAction, System.currentTimeMillis(), 0L, RunStatus.PENDING, null);
        }

        public IntentRun(String runId, String intent, List<SubtaskAssignment> assignments, String parentRunId, String controlAction, long startedAt, long completedAt, RunStatus status, String currentSubtask) {
            this.runId = runId;
            this.intent = intent;
            this.assignments = List.copyOf(assignments);
            this.parentRunId = parentRunId;
            this.controlAction = controlAction != null ? controlAction : "execute";
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
                currentSubtask
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
    }
}
