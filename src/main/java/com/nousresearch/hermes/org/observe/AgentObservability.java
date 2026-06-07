package com.nousresearch.hermes.org.observe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Central observability hub for all agents in the organization.
 *
 * <p>Collects traces, detects anomalies, and surfaces insights
 * about agent behavior across the organization:</p>
 * <ul>
 *   <li>Real-time agent status dashboard</li>
 *   <li>Anomaly detection (cost spikes, error storms, latency outliers)</li>
 *   <li>Trend analysis and behavioral drift detection</li>
 *   <li>Decision audit trail for compliance</li>
 * </ul>
 */
public class AgentObservability {
    private static final Logger logger = LoggerFactory.getLogger(AgentObservability.class);

    /** Active traces keyed by trace ID. */
    private final ConcurrentHashMap<String, AgentTrace> activeTraces = new ConcurrentHashMap<>();

    /** Completed traces for analysis (circular buffer). */
    private final ConcurrentLinkedDeque<AgentTrace> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 1000;

    /** Agent status dashboard. */
    private final ConcurrentHashMap<String, AgentStatus> agentStatuses = new ConcurrentHashMap<>();

    /** Anomaly log. */
    private final ConcurrentLinkedDeque<AnomalyEvent> anomalies = new ConcurrentLinkedDeque<>();
    private static final int MAX_ANOMALIES = 200;

    // Thresholds for anomaly detection
    private double costSpikeThreshold = 3.0;     // 3x normal cost
    private double errorRateThreshold = 0.3;      // 30% error rate
    private double latencyThresholdMs = 60_000;   // 60 seconds

    // ---- trace management ----

    /** Start a new trace for an agent task. */
    public AgentTrace startTrace(String agentId, String sessionId, String task) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        AgentTrace trace = new AgentTrace(traceId, agentId, sessionId, task);
        activeTraces.put(traceId, trace);
        return trace;
    }

    /** Complete a trace and move it to history. */
    public void completeTrace(AgentTrace trace) {
        activeTraces.remove(trace.getTraceId());
        history.addFirst(trace);
        while (history.size() > MAX_HISTORY) history.pollLast();

        // Update agent status
        AgentStatus status = agentStatuses.computeIfAbsent(trace.getAgentId(), AgentStatus::new);
        status.recordCompletion(
            Duration.between(trace.getStartTime(),
                trace.getEndTime() != null ? trace.getEndTime() : Instant.now()),
            trace.getTotalTokens(),
            trace.getErrorCount(),
            trace.getEstimatedCost()
        );

        // Check for anomalies
        checkAnomalies(trace, status);
    }

    /** Get an active trace by ID. */
    public Optional<AgentTrace> getTrace(String traceId) {
        return Optional.ofNullable(activeTraces.get(traceId));
    }

    /** Get recent traces for an agent. */
    public List<AgentTrace> getRecentTraces(String agentId, int limit) {
        return history.stream()
            .filter(t -> t.getAgentId().equals(agentId))
            .limit(limit).toList();
    }

    // ---- anomaly detection ----

    private void checkAnomalies(AgentTrace trace, AgentStatus status) {
        double avgCost = status.getAverageCost();
        if (avgCost > 0 && trace.getEstimatedCost() > avgCost * costSpikeThreshold) {
            AnomalyEvent e = new AnomalyEvent(
                AnomalyEvent.Type.COST_SPIKE,
                trace.getAgentId(),
                String.format("Cost spike: $%.4f (avg: $%.4f, %dx normal)",
                    trace.getEstimatedCost(), avgCost, (int)(trace.getEstimatedCost() / avgCost)),
                Instant.now()
            );
            anomalies.addFirst(e);
            logger.warn("Anomaly: {}", e);
        }

        int totalSteps = trace.stepCount();
        if (totalSteps > 0 && (double) trace.getErrorCount() / totalSteps > errorRateThreshold) {
            AnomalyEvent e = new AnomalyEvent(
                AnomalyEvent.Type.ERROR_STORM,
                trace.getAgentId(),
                String.format("High error rate: %d/%d (%.0f%%)",
                    trace.getErrorCount(), totalSteps, 100.0 * trace.getErrorCount() / totalSteps),
                Instant.now()
            );
            anomalies.addFirst(e);
        }

        Duration dur = Duration.between(trace.getStartTime(),
            trace.getEndTime() != null ? trace.getEndTime() : Instant.now());
        if (dur.toMillis() > latencyThresholdMs) {
            AnomalyEvent e = new AnomalyEvent(
                AnomalyEvent.Type.HIGH_LATENCY,
                trace.getAgentId(),
                String.format("Slow response: %.1fs", dur.toMillis() / 1000.0),
                Instant.now()
            );
            anomalies.addFirst(e);
        }

        while (anomalies.size() > MAX_ANOMALIES) anomalies.pollLast();
    }

    /** Recent anomalies. */
    public List<AnomalyEvent> getRecentAnomalies(int limit) {
        return anomalies.stream().limit(limit).toList();
    }

    /** Clear anomalies. */
    public void clearAnomalies() { anomalies.clear(); }

    // ---- agent status ----

    /** Get status for an agent. */
    public AgentStatus getStatus(String agentId) {
        return agentStatuses.get(agentId);
    }

    /** All agent statuses. */
    public Map<String, AgentStatus> getAllStatuses() {
        return Collections.unmodifiableMap(agentStatuses);
    }

    // ---- trend analysis ----

    /** Detect behavioral drift: compare recent N traces vs baseline. */
    public DriftReport detectDrift(String agentId, int recentWindow, int baselineWindow) {
        List<AgentTrace> recent = history.stream()
            .filter(t -> t.getAgentId().equals(agentId)).limit(recentWindow).toList();
        List<AgentTrace> baseline = history.stream()
            .filter(t -> t.getAgentId().equals(agentId))
            .skip(recentWindow).limit(baselineWindow).toList();

        if (recent.isEmpty() || baseline.isEmpty()) return new DriftReport(agentId, false, "Insufficient data");

        double recentAvgCost = recent.stream().mapToDouble(AgentTrace::getEstimatedCost).average().orElse(0);
        double baselineAvgCost = baseline.stream().mapToDouble(AgentTrace::getEstimatedCost).average().orElse(0);
        double recentAvgErr = recent.stream().mapToInt(AgentTrace::getErrorCount).average().orElse(0);
        double baselineAvgErr = baseline.stream().mapToInt(AgentTrace::getErrorCount).average().orElse(0);

        List<String> changes = new ArrayList<>();

        if (baselineAvgCost > 0 && recentAvgCost > baselineAvgCost * 1.5) {
            changes.add(String.format("Cost increased %.0f%%", (recentAvgCost / baselineAvgCost - 1) * 100));
        }
        if ((int)baselineAvgErr == 0 && (int)recentAvgErr > 0) {
            changes.add("Error rate has appeared from zero");
        } else if (baselineAvgErr > 0 && recentAvgErr > baselineAvgErr * 2) {
            changes.add(String.format("Error rate increased %.0f%%", (recentAvgErr / baselineAvgErr - 1) * 100));
        }

        return changes.isEmpty()
            ? new DriftReport(agentId, false, "No significant drift detected")
            : new DriftReport(agentId, true, String.join("; ", changes));
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("active_traces", activeTraces.size());
        s.put("total_traces", history.size());
        s.put("agents_tracked", agentStatuses.size());
        s.put("recent_anomalies", getRecentAnomalies(5).stream()
            .map(a -> a.agentId() + ": " + a.message()).toList());

        Map<String, String> statusSummary = new LinkedHashMap<>();
        for (var entry : agentStatuses.entrySet()) {
            statusSummary.put(entry.getKey(), entry.getValue().getSummary());
        }
        s.put("agent_status", statusSummary);
        return s;
    }

    /** Threshold configuration. */
    public void setThresholds(double costSpike, double errorRate, long latencyMs) {
        this.costSpikeThreshold = costSpike;
        this.errorRateThreshold = errorRate;
        this.latencyThresholdMs = latencyMs;
    }

    // ---- inner types ----

    public record AnomalyEvent(Type type, String agentId, String message, Instant time) {
        public enum Type { COST_SPIKE, ERROR_STORM, HIGH_LATENCY, MODEL_DEGRADATION, BEHAVIOR_CHANGE }
    }

    public record DriftReport(String agentId, boolean detected, String details) {}

    /** Live status for a single agent. */
    public static class AgentStatus {
        private final String agentId;
        private volatile int totalTasks;
        private volatile int erroredTasks;
        private volatile long totalTokens;
        private volatile double totalCost;
        private volatile long totalLatencyMs;
        private volatile Instant lastActivity;
        private volatile String currentTask;

        AgentStatus(String agentId) { this.agentId = agentId; }

        synchronized void recordCompletion(Duration duration, long tokens, int errors, double cost) {
            totalTasks++;
            if (errors > 0) erroredTasks++;
            totalTokens += tokens;
            totalCost += cost;
            totalLatencyMs += duration.toMillis();
            lastActivity = Instant.now();
        }

        public void setCurrentTask(String task) { this.currentTask = task; lastActivity = Instant.now(); }

        public double getErrorRate() { return totalTasks > 0 ? (double) erroredTasks / totalTasks : 0; }
        public double getAverageCost() { return totalTasks > 0 ? totalCost / totalTasks : 0; }
        public double getAverageLatencyMs() { return totalTasks > 0 ? (double) totalLatencyMs / totalTasks : 0; }

        public String getSummary() {
            return String.format("tasks=%d err=%.0f%% avgLat=%.0fms cost=$%.4f",
                totalTasks, getErrorRate() * 100, getAverageLatencyMs(), totalCost);
        }

        public String getAgentId() { return agentId; }
        public int getTotalTasks() { return totalTasks; }
        public int getErroredTasks() { return erroredTasks; }
        public long getTotalTokens() { return totalTokens; }
        public double getTotalCost() { return totalCost; }
        public Instant getLastActivity() { return lastActivity; }
        public String getCurrentTask() { return currentTask; }
    }
}
