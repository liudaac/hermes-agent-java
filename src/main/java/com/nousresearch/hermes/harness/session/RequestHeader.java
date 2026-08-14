package com.nousresearch.hermes.harness.session;

import java.util.Map;

/**
 * Snapshot of model request configuration for a single step.
 * Persisted as a trace event in the SessionLog.
 */
public record RequestHeader(
    String provider,
    String model,
    String systemPrompt,
    int toolCount,
    Map<String, Object> params,
    String reason  // "initial", "change", "resume"
) {
    /**
     * Check if two headers differ in any significant way.
     */
    public boolean differsFrom(RequestHeader other) {
        if (other == null) return true;
        return !java.util.Objects.equals(provider, other.provider)
            || !java.util.Objects.equals(model, other.model)
            || !java.util.Objects.equals(systemPrompt, other.systemPrompt)
            || toolCount != other.toolCount
            || !java.util.Objects.equals(params, other.params);
    }

    public Map<String, Object> toEventData() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("provider", provider);
        data.put("model", model);
        if (systemPrompt != null) {
            data.put("systemPromptLength", systemPrompt.length());
        }
        data.put("toolCount", toolCount);
        data.put("params", params);
        data.put("reason", reason);
        return data;
    }
}
