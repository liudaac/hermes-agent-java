package com.nousresearch.hermes.billing.repository;

/**
 * B8: Aggregated daily billing totals.
 *
 * @param tenantId       tenant identifier
 * @param totalRequests  number of API calls
 * @param inputTokens    total prompt tokens
 * @param outputTokens   total completion tokens
 * @param totalTokens    grand total tokens
 * @param estimatedCostUsd total estimated cost in USD
 */
public record TenantDailyTotals(
        String tenantId,
        long totalRequests,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        double estimatedCostUsd
) {
    /**
     * Create an empty totals record.
     */
    public static TenantDailyTotals empty(String tenantId) {
        return new TenantDailyTotals(tenantId, 0, 0, 0, 0, 0.0);
    }

    /**
     * Merge two totals (for aggregation).
     */
    public TenantDailyTotals merge(TenantDailyTotals other) {
        return new TenantDailyTotals(
            this.tenantId,
            this.totalRequests + other.totalRequests,
            this.inputTokens + other.inputTokens,
            this.outputTokens + other.outputTokens,
            this.totalTokens + other.totalTokens,
            this.estimatedCostUsd + other.estimatedCostUsd
        );
    }
}
