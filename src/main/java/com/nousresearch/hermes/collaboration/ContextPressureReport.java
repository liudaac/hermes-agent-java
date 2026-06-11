package com.nousresearch.hermes.collaboration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structured summary of context pressure signals supplied by the main agent/runtime.
 *
 * <p>This is advisory only: it never executes delegation by itself. It gives the
 * orchestration layer a stable, serializable risk surface for deciding whether a
 * task should be handed to a fresh/sub-agent context.</p>
 */
public record ContextPressureReport(
    List<String> signals,
    double score,
    String level,
    boolean compacted,
    boolean criticalPath,
    boolean nearLimit,
    boolean longRunning,
    boolean highComplexity,
    List<String> reasons
) {
    public ContextPressureReport {
        signals = signals != null ? List.copyOf(signals) : List.of();
        reasons = reasons != null ? List.copyOf(reasons) : List.of();
        level = level != null ? level : levelFor(score);
    }

    public boolean hasPressure() {
        return score > 0.0 || !signals.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signals", signals);
        m.put("score", score);
        m.put("level", level);
        m.put("compacted", compacted);
        m.put("critical_path", criticalPath);
        m.put("near_limit", nearLimit);
        m.put("long_running", longRunning);
        m.put("high_complexity", highComplexity);
        m.put("reasons", reasons);
        return m;
    }

    public static ContextPressureReport none() {
        return new ContextPressureReport(List.of(), 0.0, "LOW", false, false, false, false, false, List.of());
    }

    public static String levelFor(double score) {
        if (score >= 0.75) return "CRITICAL";
        if (score >= 0.55) return "HIGH";
        if (score >= 0.25) return "MEDIUM";
        return "LOW";
    }

    static String normalizeSignal(Object raw) {
        if (raw == null) return "";
        return String.valueOf(raw)
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
    }
}
