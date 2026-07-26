package com.nousresearch.hermes.billing.repository;

import com.nousresearch.hermes.billing.TenantUsageRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * B8: Billing repository abstraction for tenant usage records.
 *
 * <p>Decouples billing storage from JSONL files. Enables Postgres or other
 * database backends for billing data in cluster mode.</p>
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link JsonlBillingRepository} - appends to JSONL files (default, single-instance)</li>
 *   <li>PostgresBillingRepository - TODO (cluster mode)</li>
 * </ul>
 *
 * @author Hermes Team
 * @version B8
 */
public interface BillingRepository {

    /**
     * Append a single usage record.
     *
     * @param record the usage record to persist
     */
    void append(TenantUsageRecord record);

    /**
     * Retrieve all records for a tenant on a specific date.
     *
     * @param tenantId the tenant identifier
     * @param date     the date to query
     * @return list of records (may be empty)
     */
    List<TenantUsageRecord> findByTenantAndDate(String tenantId, LocalDate date);

    /**
     * Retrieve all records for a tenant within a date range (inclusive).
     *
     * @param tenantId  the tenant identifier
     * @param fromDate  start date (inclusive)
     * @param toDate    end date (inclusive)
     * @return list of records sorted by timestamp
     */
    List<TenantUsageRecord> findByTenantAndDateRange(String tenantId, LocalDate fromDate, LocalDate toDate);

    /**
     * Get aggregated totals for a tenant on a date.
     *
     * @param tenantId the tenant identifier
     * @param date     the date to query
     * @return aggregated totals (tokens, cost)
     */
    TenantDailyTotals getDailyTotals(String tenantId, LocalDate date);

    /**
     * Get aggregated totals for a tenant across a date range.
     *
     * @param tenantId  the tenant identifier
     * @param fromDate  start date (inclusive)
     * @param toDate    end date (inclusive)
     * @return aggregated totals
     */
    TenantDailyTotals getRangeTotals(String tenantId, LocalDate fromDate, LocalDate toDate);
}
