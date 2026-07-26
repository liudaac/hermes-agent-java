package com.nousresearch.hermes.billing;

import com.nousresearch.hermes.dashboard.pricing.ModelPricingTable;
import com.nousresearch.hermes.tenant.core.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * B4: Tenant billing service - writes usage records to JSONL files.
 *
 * <p>Records are appended to
 * {@code tenants/{tenantId}/state/billing/{date}.jsonl}
 * (one JSON object per line). This format is:</p>
 * <ul>
 *   <li>Append-only (no rewrite needed)</li>
 *   <li>Easy to stream/process with standard tools (jq, awk, Spark)</li>
 *   <li>Resilient to crashes (each line is a complete record)</li>
 * </ul>
 *
 * <p>This service is thread-safe per tenant. A shared {@link ModelPricingTable}
 * is used for cost estimation.</p>
 */
public class TenantBillingService {

    private static final Logger logger = LoggerFactory.getLogger(TenantBillingService.class);

    private final ModelPricingTable pricingTable;
    private final ConcurrentHashMap<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();

    /**
     * Create with default pricing table.
     */
    public TenantBillingService() {
        this(new ModelPricingTable());
    }

    /**
     * Create with custom pricing table (for testing or override file).
     */
    public TenantBillingService(ModelPricingTable pricingTable) {
        this.pricingTable = pricingTable;
    }

    /**
     * Record a single LLM API call.
     *
     * @param tenantContext the tenant that made the call
     * @param model         model name (e.g. "gpt-4o")
     * @param provider      provider ID (e.g. "openai")
     * @param inputTokens   prompt tokens
     * @param outputTokens  completion tokens
     * @param sessionId      session/agent ID (for traceability)
     */
    public void record(TenantContext tenantContext,
                       String model, String provider,
                       long inputTokens, long outputTokens,
                       String sessionId) {
        if (tenantContext == null || inputTokens < 0 || outputTokens < 0) {
            return;
        }

        double cost = pricingTable.estimateCost(model, inputTokens, outputTokens);
        TenantUsageRecord record = TenantUsageRecord.of(
                tenantContext.getTenantId(), model, provider,
                inputTokens, outputTokens, cost, sessionId);

        appendRecord(tenantContext, record);
    }

    /**
     * Estimate cost without recording (for previews/projections).
     */
    public double estimateCost(String model, long inputTokens, long outputTokens) {
        return pricingTable.estimateCost(model, inputTokens, outputTokens);
    }

    /**
     * Get the pricing table (for querying model prices).
     */
    public ModelPricingTable getPricingTable() {
        return pricingTable;
    }

    // ============ Internal ============

    private void appendRecord(TenantContext tenantContext, TenantUsageRecord record) {
        String tenantId = tenantContext.getTenantId();
        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());

        lock.lock();
        try {
            Path billingDir = tenantContext.getTenantDir().resolve("state").resolve("billing");
            Path file = billingDir.resolve(
                    record.date().format(DateTimeFormatter.ISO_DATE) + ".jsonl");

            Files.createDirectories(billingDir);
            String line = record.toJsonLine() + "\n";
            Files.writeString(file, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            logger.debug("Recorded usage for tenant={}: model={} tokens={} cost=${}",
                    tenantId, record.model(), record.totalTokens(),
                    String.format("%.6f", record.estimatedCostUsd()));

        } catch (IOException e) {
            // Billing failure should never break the agent
            logger.warn("Failed to write billing record for tenant={}: {}",
                    tenantId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }
}
