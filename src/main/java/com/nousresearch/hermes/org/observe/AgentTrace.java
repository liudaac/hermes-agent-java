package com.nousresearch.hermes.org.observe;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent reasoning trace — captures every decision point in an agent's
 * execution for debugging, audit, and compliance.
 *
 * <p>A trace is a linked list of {@link Step}s, each representing one
 * decision cycle: tool selection → tool input → tool output → reasoning
 * about next step.</p>
 */
public class AgentTrace {

    public enum StepType { THINKING, TOOL_CALL, TOOL_RESULT, DECISION, ERROR, HUMAN_HANDOFF }
    public enum Status { SUCCESS, FAILED, CANCELLED, TIMED_OUT, PARTIAL }

    private final String traceId;
    private final String agentId;
    private final String sessionId;
    private final String taskDescription;
    private final Instant startTime;
    private Instant endTime;
    private Status status = Status.SUCCESS;
    private final ConcurrentLinkedDeque<Step> steps = new ConcurrentLinkedDeque<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private int errorCount;
    private long totalTokens;
    private double estimatedCost;
    private String errorSummary;

    public AgentTrace(String traceId, String agentId, String sessionId, String taskDescription) {
        this.traceId = traceId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.taskDescription = taskDescription;
        this.startTime = Instant.now();
    }

    /** Record a step in the trace. */
    public AgentTrace step(Step step) {
        steps.add(step);
        if (step.type() == StepType.ERROR) { errorCount++; errorSummary = step.content(); }
        if (step.tokens() > 0) totalTokens += step.tokens();
        if (step.cost() > 0) estimatedCost += step.cost();
        return this;
    }

    public AgentTrace meta(String key, Object value) { metadata.put(key, value); return this; }
    public AgentTrace end(Status s) { this.endTime = Instant.now(); this.status = s; return this; }

    /** Reconstruct a human-readable timeline from the trace. */
    public String toTimeline() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Trace: ").append(traceId).append("\n");
        sb.append("Agent: ").append(agentId).append(" | Session: ").append(sessionId).append("\n");
        sb.append("Task: ").append(taskDescription).append("\n");
        sb.append("Status: ").append(status).append(" | Duration: ")
          .append(Duration.between(startTime, endTime != null ? endTime : Instant.now()).toSeconds()).append("s\n");
        sb.append("Tokens: ").append(totalTokens).append(" | Cost: $").append(String.format("%.4f", estimatedCost)).append("\n\n");

        int stepNum = 1;
        for (Step s : steps) {
            String icon = switch (s.type()) {
                case THINKING -> "💭";
                case TOOL_CALL -> "🔧";
                case TOOL_RESULT -> "📋";
                case DECISION -> "⚡";
                case ERROR -> "❌";
                case HUMAN_HANDOFF -> "👤";
            };
            sb.append(stepNum++).append(". ").append(icon).append(" [").append(s.type()).append("] ");
            sb.append(s.content());
            if (s.confidence() > 0) sb.append(" (confidence: ").append(String.format("%.0f%%", s.confidence() * 100)).append(")");
            sb.append("\n");
        }
        if (errorCount > 0) sb.append("\n⚠️ Errors: ").append(errorCount);
        return sb.toString();
    }

    /** Decision forensics: answer "why did the agent choose X instead of Y?" */
    public String forensics() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Decision Forensics: ").append(traceId).append("\n\n");
        sb.append("## Summary\n");
        sb.append("- Agent: ").append(agentId).append("\n");
        sb.append("- Task: ").append(taskDescription).append("\n");
        sb.append("- Total steps: ").append(steps.size()).append("\n");
        sb.append("- Errors: ").append(errorCount).append("\n");
        sb.append("- Tokens used: ").append(totalTokens).append("\n");
        sb.append("- Cost: $").append(String.format("%.4f", estimatedCost)).append("\n\n");

        sb.append("## Decision Chain\n");
        int stepNum = 1;
        Decision prevDecision = null;
        for (Step s : steps) {
            if (s.type() == StepType.DECISION || s.type() == StepType.TOOL_CALL) {
                Decision d = new Decision(stepNum++, s.type().name(), s.content(),
                    s.alternatives(), s.confidence(), s.durationMs());
                sb.append(d.format(prevDecision));
                prevDecision = d;
            }
        }

        if (errorCount > 0) {
            sb.append("\n## Errors Encountered\n");
            steps.stream().filter(s -> s.type() == StepType.ERROR)
                .forEach(s -> sb.append("- ").append(s.content()).append("\n"));
        }
        return sb.toString();
    }

    // ---- getters ----
    public String getTraceId() { return traceId; }
    public String getAgentId() { return agentId; }
    public String getSessionId() { return sessionId; }
    public String getTaskDescription() { return taskDescription; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public Status getStatus() { return status; }
    public int getErrorCount() { return errorCount; }
    public long getTotalTokens() { return totalTokens; }
    public double getEstimatedCost() { return estimatedCost; }
    public int stepCount() { return steps.size(); }
    public List<Step> getSteps() { return List.copyOf(steps); }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }

    /** A single step in an agent's reasoning trace. */
    public record Step(
        StepType type,
        String content,          // What the agent was thinking or what action it took
        double confidence,       // 0.0-1.0 how confident the agent was
        long durationMs,         // How long this step took
        long tokens,             // Tokens consumed
        double cost,             // Estimated cost
        List<String> alternatives, // What other options were considered
        String toolName,         // For TOOL_CALL steps
        String toolInput,        // For TOOL_CALL steps
        String toolOutput        // For TOOL_RESULT steps
    ) {
        public static Step thinking(String content, double confidence) {
            return new Step(StepType.THINKING, content, confidence, 0, 0, 0, List.of(), null, null, null);
        }
        public static Step toolCall(String tool, String input, List<String> alternatives, double confidence, long tokens, double cost) {
            return new Step(StepType.TOOL_CALL, "Called " + tool, confidence, 0, tokens, cost, alternatives, tool, input, null);
        }
        public static Step toolResult(String tool, String output, long durationMs) {
            return new Step(StepType.TOOL_RESULT, output, 0, durationMs, 0, 0, List.of(), tool, null, output);
        }
        public static Step decision(String content, double confidence, List<String> alternatives) {
            return new Step(StepType.DECISION, content, confidence, 0, 0, 0, alternatives, null, null, null);
        }
        public static Step error(String message) {
            return new Step(StepType.ERROR, message, 0, 0, 0, 0, List.of(), null, null, null);
        }
        public static Step handoff(String reason) {
            return new Step(StepType.HUMAN_HANDOFF, reason, 0, 0, 0, 0, List.of(), null, null, null);
        }
    }

    /** Internal representation of a decision point. */
    private record Decision(int number, String type, String action, List<String> alternatives, double confidence, long durationMs) {
        String format(Decision prev) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  #%d [%s] %s%n", number, type, action));
            if (confidence > 0) sb.append(String.format("    Confidence: %.0f%%%n", confidence * 100));
            if (durationMs > 0) sb.append(String.format("    Duration: %dms%n", durationMs));
            if (!alternatives.isEmpty()) {
                sb.append("    Alternatives considered:\n");
                for (String alt : alternatives) sb.append("      - ").append(alt).append("\n");
            }
            sb.append("\n");
            return sb.toString();
        }
    }
}