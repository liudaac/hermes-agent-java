package com.nousresearch.hermes.billing.repository;

import com.nousresearch.hermes.billing.TenantUsageRecord;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C6: MysqlBillingRepository tests.
 * Uses H2 in MySQL compatibility mode as stand-in.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlBillingRepositoryTest {

    private static javax.sql.DataSource h2DataSource;
    private MysqlBillingRepository repo;

    @BeforeAll
    static void initDB() throws Exception {
        h2DataSource = org.h2.jdbcx.JdbcConnectionPool.create(
            "jdbc:h2:mem:test-billing;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS billing_record (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(64) NOT NULL,
                    model VARCHAR(128) NOT NULL,
                    provider VARCHAR(32) NOT NULL,
                    input_tokens BIGINT NOT NULL DEFAULT 0,
                    output_tokens BIGINT NOT NULL DEFAULT 0,
                    total_tokens BIGINT NOT NULL DEFAULT 0,
                    estimated_cost_usd DECIMAL(12,6) NOT NULL DEFAULT 0,
                    session_id VARCHAR(128) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """);
        }
    }

    @BeforeEach
    void setUp() {
        repo = new MysqlBillingRepository(h2DataSource);
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM billing_record");
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("append and find by date")
    void appendAndFind() {
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.018, "s1"));
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 300, 400, 0.036, "s2"));

        List<TenantUsageRecord> records = repo.findByTenantAndDate("t1", LocalDate.now());
        assertEquals(2, records.size());
    }

    @Test
    @Order(2)
    @DisplayName("findByTenantAndDate returns empty for no records")
    void findEmpty() {
        List<TenantUsageRecord> records = repo.findByTenantAndDate("unknown", LocalDate.now());
        assertTrue(records.isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("getDailyTotals aggregates correctly")
    void getDailyTotals() {
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.018, "s1"));
        repo.append(TenantUsageRecord.of("t1", "claude-3.5-sonnet", "anthropic", 500, 600, 0.015, "s2"));

        TenantDailyTotals totals = repo.getDailyTotals("t1", LocalDate.now());
        assertEquals(2, totals.totalRequests());
        assertEquals(600, totals.inputTokens());
        assertEquals(800, totals.outputTokens());
        assertEquals(1400, totals.totalTokens());
        assertTrue(totals.estimatedCostUsd() > 0);
    }

    @Test
    @Order(4)
    @DisplayName("getDailyTotals returns empty for no records")
    void getDailyTotalsEmpty() {
        TenantDailyTotals totals = repo.getDailyTotals("unknown", LocalDate.now());
        assertEquals(0, totals.totalRequests());
        assertEquals(0.0, totals.estimatedCostUsd());
    }

    @Test
    @Order(5)
    @DisplayName("getRangeTotals aggregates across days")
    void getRangeTotals() {
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.018, "s1"));
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 300, 400, 0.036, "s2"));

        TenantDailyTotals totals = repo.getRangeTotals("t1", LocalDate.now().minusDays(1), LocalDate.now());
        assertEquals(2, totals.totalRequests());
        assertEquals(1000, totals.totalTokens());
    }

    @Test
    @Order(6)
    @DisplayName("tenants are isolated")
    void tenantIsolation() {
        repo.append(TenantUsageRecord.of("tenant-A", "gpt-4o", "openai", 100, 200, 0.018, "s-a"));
        repo.append(TenantUsageRecord.of("tenant-B", "claude-3.5-sonnet", "anthropic", 300, 400, 0.015, "s-b"));

        List<TenantUsageRecord> aRecords = repo.findByTenantAndDate("tenant-A", LocalDate.now());
        List<TenantUsageRecord> bRecords = repo.findByTenantAndDate("tenant-B", LocalDate.now());

        assertEquals(1, aRecords.size());
        assertEquals(1, bRecords.size());
        assertEquals("gpt-4o", aRecords.get(0).model());
        assertEquals("claude-3.5-sonnet", bRecords.get(0).model());
    }

    @Test
    @Order(7)
    @DisplayName("append null record is safe")
    void appendNull() {
        repo.append(null);
        // Should not throw
    }

    @Test
    @Order(8)
    @DisplayName("findByTenantAndDateRange spans multiple days")
    void findByDateRange() {
        repo.append(TenantUsageRecord.of("t1", "gpt-4o", "openai", 100, 200, 0.018, "s1"));

        List<TenantUsageRecord> records = repo.findByTenantAndDateRange(
            "t1", LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertEquals(1, records.size());
    }
}
