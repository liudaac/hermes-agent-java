package com.nousresearch.hermes.billing;

import java.time.Instant;
import java.time.LocalDate;

/**
 * B4: A single tenant usage record for billing.
 *
 * <p>One record per LLM API call, written as JSONL (one JSON object per line)
 * to {@code tenants/{tenantId}/state/billing/{date}.jsonl}.</p>
 *
 * @param tenantId       tenant identifier
 * @param date           date of the call (for file partitioning)
 * @param model          model name used (e.g. "gpt-4o")
 * @param provider       provider ID (e.g. "openai")
 * @param inputTokens    prompt tokens consumed
 * @param outputTokens   completion tokens consumed
 * @param totalTokens    total tokens (input + output)
 * @param estimatedCostUsd estimated cost in USD (from ModelPricingTable)
 * @param timestamp      precise call timestamp
 * @param sessionId      session/agent that made the call (for traceability)
 */
public record TenantUsageRecord(
        String tenantId,
        LocalDate date,
        String model,
        String provider,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        double estimatedCostUsd,
        Instant timestamp,
        String sessionId
) {

    /**
     * Create a record from raw call data.
     */
    public static TenantUsageRecord of(
            String tenantId, String model, String provider,
            long inputTokens, long outputTokens,
            double estimatedCostUsd, String sessionId) {
        long total = inputTokens + outputTokens;
        return new TenantUsageRecord(
                tenantId,
                LocalDate.now(),
                model,
                provider,
                inputTokens,
                outputTokens,
                total,
                estimatedCostUsd,
                Instant.now(),
                sessionId
        );
    }

    /**
     * Convert to a single-line JSON string for JSONL storage.
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"tenantId\":\"").append(escape(tenantId)).append('"');
        sb.append(",\"date\":\"").append(date).append('"');
        sb.append(",\"model\":\"").append(escape(model)).append('"');
        sb.append(",\"provider\":\"").append(escape(provider)).append('"');
        sb.append(",\"inputTokens\":").append(inputTokens);
        sb.append(",\"outputTokens\":").append(outputTokens);
        sb.append(",\"totalTokens\":").append(totalTokens);
        sb.append(",\"estimatedCostUsd\":").append(estimatedCostUsd);
        sb.append(",\"timestamp\":\"").append(timestamp).append('"');
        sb.append(",\"sessionId\":\"").append(escape(sessionId)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
