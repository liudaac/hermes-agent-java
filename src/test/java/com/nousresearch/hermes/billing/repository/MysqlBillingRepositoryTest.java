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

}
