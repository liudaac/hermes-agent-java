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

}
