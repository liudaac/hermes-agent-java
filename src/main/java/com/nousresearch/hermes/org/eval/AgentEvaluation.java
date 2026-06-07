package com.nousresearch.hermes.org.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Evaluation benchmark for AI agents — measures capability
 * across defined dimensions with scoring and regression detection.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Task-based evaluations with expected outputs</li>
 *   <li>Multi-dimensional scoring (accuracy, latency, cost, safety)</li>
 *   <li>A/B comparison between agent versions</li>
 *   <li>Drift detection (performance regression from model updates)</li>
 *   <li>Historical result persistence for trend analysis</li>
 * </ul>
 */
public class AgentEvaluation {

    /** Scoring dimensions for agent output. */
    public enum Dimension {
        ACCURACY,       // Did the agent produce the correct result?
        COMPLETENESS,   // Did it cover all aspects of the task?
        EFFICIENCY,     // How many tool calls / iterations were used?
        LATENCY,        // How long did it take?
        SAFETY,         // Were there any safety violations?
        COST,           // What was the token / dollar cost?
        TOOL_CHOICE,    // Did it choose the right tools for the job?
        EXPLANATION     // Was the reasoning clear and correct?
    }

    /** Result of running a single evaluation case. */
    public static class EvalResult {
        private final String evalId;
        private final String agentId;
        private final String agentVersion;
        private final String taskDescription;
        private final Map<Dimension, Double> scores;
        private final Duration duration;
        private final int toolCallsUsed;
        private final long tokensUsed;
        private final double estimatedCost;
        private final boolean passed;
        private final String notes;
        private final Instant timestamp;
        private final Map<String, Object> metadata;

        private EvalResult(Builder builder) {
            this.evalId = UUID.randomUUID().toString();
            this.agentId = Objects.requireNonNull(builder.agentId);
            this.agentVersion = builder.agentVersion != null ? builder.agentVersion : "unknown";
            this.taskDescription = builder.taskDescription;
            this.scores = Collections.unmodifiableMap(new LinkedHashMap<>(builder.scores));
            this.duration = builder.duration;
            this.toolCallsUsed = builder.toolCallsUsed;
            this.tokensUsed = builder.tokensUsed;
            this.estimatedCost = builder.estimatedCost;
            this.passed = builder.passed;
            this.notes = builder.notes;
            this.timestamp = Instant.now();
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        }

        /** Weighted composite score (0.0-1.0). */
        public double getCompositeScore(Map<Dimension, Double> weights) {
            double total = 0, weightSum = 0;
            for (var entry : weights.entrySet()) {
                Double score = scores.get(entry.getKey());
                if (score != null) {
                    total += score * entry.getValue();
                    weightSum += entry.getValue();
                }
            }
            return weightSum > 0 ? total / weightSum : 0;
        }

        /** Default composite with equal weights. */
        public double getCompositeScore() {
            Map<Dimension, Double> equalWeights = new LinkedHashMap<>();
            for (Dimension d : Dimension.values()) equalWeights.put(d, 1.0);
            return getCompositeScore(equalWeights);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eval_id", evalId);
            m.put("agent_id", agentId);
            m.put("agent_version", agentVersion);
            m.put("task", taskDescription);
            m.put("scores", scores);
            m.put("composite", String.format("%.2f", getCompositeScore()));
            m.put("duration_ms", duration.toMillis());
            m.put("tool_calls", toolCallsUsed);
            m.put("tokens", tokensUsed);
            m.put("estimated_cost", String.format("$%.4f", estimatedCost));
            m.put("passed", passed);
            m.put("notes", notes);
            m.put("timestamp", timestamp.toString());
            return m;
        }

        public String getEvalId() { return evalId; }
        public String getAgentId() { return agentId; }
        public String getAgentVersion() { return agentVersion; }
        public Map<Dimension, Double> getScores() { return scores; }
        public Duration getDuration() { return duration; }
        public int getToolCallsUsed() { return toolCallsUsed; }
        public long getTokensUsed() { return tokensUsed; }
        public double getEstimatedCost() { return estimatedCost; }
        public boolean isPassed() { return passed; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, Object> getMetadata() { return metadata; }

        public static class Builder {
            private String agentId;
            private String agentVersion;
            private String taskDescription;
            private final Map<Dimension, Double> scores = new LinkedHashMap<>();
            private Duration duration = Duration.ZERO;
            private int toolCallsUsed;
            private long tokensUsed;
            private double estimatedCost;
            private boolean passed = true;
            private String notes;
            private final Map<String, Object> metadata = new LinkedHashMap<>();

            public Builder agentId(String id) { this.agentId = id; return this; }
            public Builder agentVersion(String v) { this.agentVersion = v; return this; }
            public Builder task(String desc) { this.taskDescription = desc; return this; }
            public Builder score(Dimension d, double v) { scores.put(d, v); return this; }
            public Builder duration(Duration d) { this.duration = d; return this; }
            public Builder toolCalls(int n) { this.toolCallsUsed = n; return this; }
            public Builder tokens(long n) { this.tokensUsed = n; return this; }
            public Builder cost(double c) { this.estimatedCost = c; return this; }
            public Builder passed(boolean p) { this.passed = p; return this; }
            public Builder notes(String n) { this.notes = n; return this; }
            public Builder metadata(String k, Object v) { metadata.put(k, v); return this; }
            public EvalResult build() { return new EvalResult(this); }
        }
    }

    /** A suite of evaluation tasks. */
    public static class EvalSuite {
        private final String name;
        private final String description;
        private final List<EvalTask> tasks;
        private final Map<Dimension, Double> weights;

        public EvalSuite(String name, String description, List<EvalTask> tasks, Map<Dimension, Double> weights) {
            this.name = name;
            this.description = description;
            this.tasks = List.copyOf(tasks);
            this.weights = Map.copyOf(weights);
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<EvalTask> getTasks() { return tasks; }
        public Map<Dimension, Double> getWeights() { return weights; }
    }

    /** A single evaluation task. */
    public record EvalTask(
        String id,
        String description,
        String input,
        String expectedOutput,
        Set<String> requiredTools,
        Map<String, Object> assertions
    ) {}

    // ---- A/B comparison -------

    /** Compare two agents' evaluation results across all dimensions. */
    public static Map<String, Object> compare(EvalResult a, EvalResult b, Map<Dimension, Double> weights) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("agent_a", a.getAgentId());
        diff.put("agent_b", b.getAgentId());
        diff.put("version_a", a.getAgentVersion());
        diff.put("version_b", b.getAgentVersion());

        double compositeA = a.getCompositeScore(weights);
        double compositeB = b.getCompositeScore(weights);
        diff.put("composite_a", String.format("%.2f", compositeA));
        diff.put("composite_b", String.format("%.2f", compositeB));
        diff.put("composite_delta", String.format("%+.2f", compositeB - compositeA));

        // Per-dimension comparison
        Map<String, Object> dimDiffs = new LinkedHashMap<>();
        for (Dimension d : Dimension.values()) {
            Double sa = a.getScores().get(d);
            Double sb = b.getScores().get(d);
            if (sa == null && sb == null) continue;
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("score_a", sa);
            dim.put("score_b", sb);
            dim.put("delta", sa != null && sb != null ? sb - sa : null);
            dimDiffs.put(d.name(), dim);
        }
        diff.put("dimensions", dimDiffs);

        // Cost comparison
        diff.put("cost_a", String.format("$%.4f", a.getEstimatedCost()));
        diff.put("cost_b", String.format("$%.4f", b.getEstimatedCost()));
        diff.put("cost_delta", String.format("$%+.4f", b.getEstimatedCost() - a.getEstimatedCost()));

        // Latency comparison
        diff.put("latency_a_ms", a.getDuration().toMillis());
        diff.put("latency_b_ms", b.getDuration().toMillis());
        diff.put("latency_delta_ms", b.getDuration().minus(a.getDuration()).toMillis());

        diff.put("winner", compositeB > compositeA ? "B (" + b.getAgentId() + ")" :
                compositeA > compositeB ? "A (" + a.getAgentId() + ")" : "TIE");

        return diff;
    }

    // ---- drift detection -------

    /** Detect significant regression compared to a baseline. */
    public static List<String> detectDrift(EvalResult baseline, EvalResult current, double threshold) {
        List<String> drifts = new ArrayList<>();
        double baselineComposite = baseline.getCompositeScore();
        double currentComposite = current.getCompositeScore();

        if (currentComposite < baselineComposite - threshold) {
            drifts.add(String.format("Composite score dropped from %.2f → %.2f (Δ = %.2f)",
                baselineComposite, currentComposite, currentComposite - baselineComposite));
        }

        for (Dimension d : Dimension.values()) {
            Double bs = baseline.getScores().get(d);
            Double cs = current.getScores().get(d);
            if (bs != null && cs != null && cs < bs - threshold) {
                drifts.add(String.format("%s degraded: %.2f → %.2f", d.name(), bs, cs));
            }
        }

        return drifts;
    }

    // ---- preset eval suites -------

    /** Default equal weights for all dimensions. */
    public static Map<Dimension, Double> defaultWeights() {
        Map<Dimension, Double> w = new LinkedHashMap<>();
        for (Dimension d : Dimension.values()) w.put(d, 1.0);
        return w;
    }

    /** Production-critical weights (safety + accuracy most important). */
    public static Map<Dimension, Double> productionWeights() {
        Map<Dimension, Double> w = new LinkedHashMap<>();
        w.put(Dimension.ACCURACY, 3.0);
        w.put(Dimension.SAFETY, 3.0);
        w.put(Dimension.COMPLETENESS, 2.0);
        w.put(Dimension.EFFICIENCY, 1.0);
        w.put(Dimension.LATENCY, 1.0);
        w.put(Dimension.COST, 1.0);
        w.put(Dimension.TOOL_CHOICE, 2.0);
        w.put(Dimension.EXPLANATION, 1.5);
        return w;
    }
}
