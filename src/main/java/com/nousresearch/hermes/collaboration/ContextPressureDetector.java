package com.nousresearch.hermes.collaboration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects context-pressure risk from explicit orchestration signals. */
public final class ContextPressureDetector {
    private ContextPressureDetector() {}

    public static ContextPressureReport detect(List<?> contextSignals) {
        if (contextSignals == null || contextSignals.isEmpty()) {
            return ContextPressureReport.none();
        }

        Set<String> normalized = new LinkedHashSet<>();
        boolean compacted = false;
        boolean criticalPath = false;
        boolean nearLimit = false;
        boolean longRunning = false;
        boolean highComplexity = false;
        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        for (Object raw : contextSignals) {
            String signal = ContextPressureReport.normalizeSignal(raw);
            if (signal.isBlank()) continue;
            normalized.add(signal);

            if (signal.contains("compact") || signal.contains("truncat") || signal.contains("summarized_context")) {
                compacted = true;
                score += 0.45;
                reasons.add("Context has been compacted/truncated; main-agent state may be lossy");
            } else if (signal.contains("critical") || signal.contains("production") || signal.contains("release") || signal.contains("high_stakes")) {
                criticalPath = true;
                score += 0.35;
                reasons.add("Task is on a critical/high-stakes path");
            } else if (signal.contains("near_limit") || signal.contains("context_limit") || signal.contains("token_pressure") || signal.contains("low_context")) {
                nearLimit = true;
                score += 0.40;
                reasons.add("Context window pressure is elevated");
            } else if (signal.contains("long_running") || signal.contains("multi_step") || signal.contains("extended")) {
                longRunning = true;
                score += 0.20;
                reasons.add("Task is long-running or multi-step");
            } else if (signal.contains("complex") || signal.contains("many_files") || signal.contains("large_diff")) {
                highComplexity = true;
                score += 0.20;
                reasons.add("Task complexity increases main-agent coordination risk");
            } else {
                score += 0.05;
            }
        }

        if (compacted && criticalPath) {
            score += 0.20;
            reasons.add("Compacted context combined with critical path merits a fresh delegated context");
        }
        if (nearLimit && (criticalPath || highComplexity || longRunning)) {
            score += 0.15;
            reasons.add("Near-limit context combined with task risk merits delegation consideration");
        }

        double bounded = Math.min(1.0, Math.max(0.0, score));
        return new ContextPressureReport(
            List.copyOf(normalized),
            bounded,
            ContextPressureReport.levelFor(bounded),
            compacted,
            criticalPath,
            nearLimit,
            longRunning,
            highComplexity,
            dedupe(reasons)
        );
    }

    public static ContextPressureReport detectFromArgs(Map<String, Object> args) {
        return detect(extractSignals(args));
    }

    @SuppressWarnings("unchecked")
    public static List<?> extractSignals(Map<String, Object> args) {
        if (args == null) return List.of();
        Object explicit = args.get("context_signals");
        if (explicit == null) explicit = args.get("contextSignals");
        if (explicit instanceof List<?> list) return list;
        if (explicit instanceof String s && !s.isBlank()) return List.of(s.split("[,\\s]+"));

        Object meta = args.get("metadata");
        if (meta instanceof Map<?, ?> m) {
            Object value = m.get("context_signals");
            if (value == null) value = m.get("contextSignals");
            if (value instanceof List<?> list) return list;
            if (value instanceof String s && !s.isBlank()) return List.of(s.split("[,\\s]+"));
        }
        return List.of();
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
