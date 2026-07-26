package com.nousresearch.hermes.billing.repository;

import com.nousresearch.hermes.billing.TenantUsageRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B8: BillingRepository tests (JSONL implementation).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingRepositoryTest {

    @TempDir
    Path tempDir;

    private BillingRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JsonlBillingRepository(tempDir);
    }

    @Test
    @Order(1)
    @DisplayName("append writes to JSONL file")
    void append_writesFile() {
        TenantUsageRecord record = TenantUsageRecord.of(
            "t1", "gpt-4o", "openai", 100, 200, 0.0, "sess-1");
        repo.append(record);

        List<TenantUsageRecord> found = repo.findByTenantAndDate("t1", LocalDate.now());
        assertEquals(1, found.size());
        assertEquals("gpt-4o", found.get(0).model());
        assertEquals(300, found.get(0).totalTokens());
    }

    @Test
    @Order(2)
    @DisplayName("append multiple records to same file")
    void append_multiple() {
        for (int i = 0; i < 5; i++) {
            repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.0, "sess-" + i));
        }

        List<TenantUsageRecord> found = repo.findByTenantAndDate("t1", LocalDate.now());
        assertEquals(5, found.size());
    }

    @Test
    @Order(3)
    @DisplayName("findByTenantAndDate returns empty for no records")
    void findByTenantAndDate_empty() {
        List<TenantUsageRecord> found = repo.findByTenantAndDate("unknown", LocalDate.now());
        assertTrue(found.isEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("findByTenantAndDateRange spans multiple days")
    void findByDateRange_multipleDays() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // Write to both today and yesterday
        JsonlBillingRepository repo2 = new JsonlBillingRepository(tempDir);
        // Manually create a record for yesterday
        TenantUsageRecord yRecord = new TenantUsageRecord(
            "t1", yesterday, "gpt-4o", "openai", 50, 50, 100, 0.001,
            java.time.Instant.now().minusSeconds(86400), "sess-y");
        repo2.append(yRecord);

        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.0, "sess-t"));

        List<TenantUsageRecord> found = repo.findByTenantAndDateRange("t1", yesterday, today);
        assertEquals(2, found.size());
    }

    @Test
    @Order(5)
    @DisplayName("findByTenantAndDateRange returns sorted by timestamp")
    void findByDateRange_sorted() {
        LocalDate today = LocalDate.now();
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.0, "sess-2"));
        Thread.yield();
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 50, 50, 0.0, "sess-1"));

        List<TenantUsageRecord> found = repo.findByTenantAndDateRange("t1", today, today);
        assertEquals(2, found.size());
        assertTrue(found.get(0).timestamp().isBefore(found.get(1).timestamp())
            || found.get(0).timestamp().equals(found.get(1).timestamp()));
    }

    @Test
    @Order(6)
    @DisplayName("getDailyTotals aggregates correctly")
    void getDailyTotals() {
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.018, "s1"));
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 300, 400, 0.036, "s2"));
        repo.append(TenantUsageRecord.of("t1", "claude-3.5-sonnet", "anthropic", 500, 600, 0.015, "s3"));

        TenantDailyTotals totals = repo.getDailyTotals("t1", LocalDate.now());
        assertEquals(3, totals.totalRequests());
        assertEquals(900, totals.inputTokens());   // 100+300+500
        assertEquals(1200, totals.outputTokens()); // 200+400+600
        assertEquals(2100, totals.totalTokens());
        assertTrue(totals.estimatedCostUsd() > 0);
        assertEquals(0.069, totals.estimatedCostUsd(), 0.001);
    }

    @Test
    @Order(7)
    @DisplayName("getDailyTotals returns empty for no records")
    void getDailyTotals_empty() {
        TenantDailyTotals totals = repo.getDailyTotals("unknown", LocalDate.now());
        assertEquals(0, totals.totalRequests());
        assertEquals(0, totals.totalTokens());
        assertEquals(0.0, totals.estimatedCostUsd());
    }

    @Test
    @Order(8)
    @DisplayName("getRangeTotals aggregates across days")
    void getRangeTotals() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // Today's records
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.0, "s1"));

        // Yesterday's record (manually with different date)
        TenantUsageRecord yRecord = new TenantUsageRecord(
            "t1", yesterday, "gpt-4o", "openai", 50, 50, 100, 0.001,
            java.time.Instant.now().minusSeconds(86400), "s-y");
        repo.append(yRecord);

        TenantDailyTotals totals = repo.getRangeTotals("t1", yesterday, today);
        assertEquals(2, totals.totalRequests());
        assertEquals(400, totals.totalTokens());  // 300 + 100
    }

    @Test
    @Order(9)
    @DisplayName("tenants are isolated in billing")
    void tenantIsolation() {
        repo.append(TenantUsageRecord.of("tenant-A", "gpt-4o", "openai", 100, 200, 0.0, "s-a"));
        repo.append(TenantUsageRecord.of("tenant-B", "claude-3.5-sonnet", "anthropic", 300, 400, 0.0, "s-b"));

        List<TenantUsageRecord> aRecords = repo.findByTenantAndDate("tenant-A", LocalDate.now());
        List<TenantUsageRecord> bRecords = repo.findByTenantAndDate("tenant-B", LocalDate.now());

        assertEquals(1, aRecords.size());
        assertEquals(1, bRecords.size());
        assertEquals("gpt-4o", aRecords.get(0).model());
        assertEquals("claude-3.5-sonnet", bRecords.get(0).model());
    }

    @Test
    @Order(10)
    @DisplayName("TenantDailyTotals merge combines correctly")
    void totalsMerge() {
        TenantDailyTotals a = new TenantDailyTotals("t1", 2, 100, 200, 300, 0.01);
        TenantDailyTotals b = new TenantDailyTotals("t1", 3, 400, 500, 900, 0.02);

        TenantDailyTotals merged = a.merge(b);
        assertEquals(5, merged.totalRequests());
        assertEquals(500, merged.inputTokens());
        assertEquals(700, merged.outputTokens());
        assertEquals(1200, merged.totalTokens());
        assertEquals(0.03, merged.estimatedCostUsd(), 0.0001);
    }

    @Test
    @Order(11)
    @DisplayName("TenantDailyTotals empty creates zero values")
    void totalsEmpty() {
        TenantDailyTotals empty = TenantDailyTotals.empty("t1");
        assertEquals("t1", empty.tenantId());
        assertEquals(0, empty.totalRequests());
        assertEquals(0, empty.totalTokens());
    }

    @Test
    @Order(12)
    @DisplayName("append null record is safe")
    void append_null() {
        repo.append(null);  // should not throw
    }

    @Test
    @Order(13)
    @DisplayName("append record with null tenantId is safe")
    void append_nullTenant() {
        TenantUsageRecord record = new TenantUsageRecord(
            null, LocalDate.now(), "gpt-4o", "openai", 0, 0, 0, 0.0,
            java.time.Instant.now(), null);
        repo.append(record);  // should not throw
    }
}
