package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.org.eval.AgentEvaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only projection of foundation {@link AgentEvaluation.EvalResult} data into a
 * Business Portal "EvalRun" view.
 *
 * <p>Implementation follows the contract in
 * {@code docs/BUSINESS_PORTAL_FOUNDATION_EVAL_RUN_DESIGN.md}. The adapter is intentionally
 * non-mutating: it does not change foundation evaluation state, does not introduce a new
 * EvalSet business object, and does not create evolution proposals.</p>
 */
public class BusinessEvalRunProjectionAdapter {

    public BusinessEvalRunProjection fromEvalResult(String workspaceId, AgentEvaluation.EvalResult result) {
        Objects.requireNonNull(result, "result");
        Map<String, Object> scores = new LinkedHashMap<>();
        if (result.getScores() != null) {
            for (Map.Entry<AgentEvaluation.Dimension, Double> entry : result.getScores().entrySet()) {
                scores.put(entry.getKey().name(), entry.getValue());
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "foundation:agent-evaluation");
        metadata.put("evalId", result.getEvalId());
        metadata.put("agentVersion", result.getAgentVersion());
        metadata.put("foundationTimestamp", result.getTimestamp() != null ? result.getTimestamp().toString() : null);
        if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            metadata.put("evalMetadata", Map.copyOf(result.getMetadata()));
        }

        return new BusinessEvalRunProjection(
            workspaceId,
            "eval-run-" + nonBlank(result.getEvalId(), Long.toHexString(System.nanoTime())),
            result.getAgentId(),
            result.getAgentVersion(),
            taskDescription(result),
            scores,
            result.getCompositeScore(),
            result.isPassed(),
            result.getDuration() != null ? result.getDuration().toMillis() : 0,
            result.getToolCallsUsed(),
            result.getTokensUsed(),
            result.getEstimatedCost(),
            metadata
        );
    }

    public List<BusinessEvalRunProjection> fromEvalResults(String workspaceId, List<AgentEvaluation.EvalResult> results) {
        if (results == null || results.isEmpty()) return List.of();
        List<BusinessEvalRunProjection> projections = new ArrayList<>(results.size());
        for (AgentEvaluation.EvalResult result : results) {
            if (result == null) continue;
            projections.add(fromEvalResult(workspaceId, result));
        }
        return Collections.unmodifiableList(projections);
    }

    private static String taskDescription(AgentEvaluation.EvalResult result) {
        Object value = result.toMap().get("task");
        return value != null ? value.toString() : "";
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** Read-only Business Portal eval run projection. */
    public record BusinessEvalRunProjection(
        String workspaceId,
        String evalRunId,
        String agentId,
        String agentVersion,
        String task,
        Map<String, Object> scores,
        double compositeScore,
        boolean passed,
        long durationMs,
        int toolCalls,
        long tokens,
        double estimatedCost,
        Map<String, Object> metadata
    ) {
        public BusinessEvalRunProjection {
            scores = scores != null ? Map.copyOf(scores) : Map.of();
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workspaceId", workspaceId);
            map.put("evalRunId", evalRunId);
            map.put("agentId", agentId);
            map.put("agentVersion", agentVersion);
            map.put("task", task);
            map.put("scores", scores);
            map.put("compositeScore", compositeScore);
            map.put("passed", passed);
            map.put("durationMs", durationMs);
            map.put("toolCalls", toolCalls);
            map.put("tokens", tokens);
            map.put("estimatedCost", estimatedCost);
            map.put("metadata", metadata);
            map.put("generatedAt", Instant.now().toString());
            return map;
        }
    }
}
