package com.nousresearch.hermes.platform.secret;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C5: MysqlSecretStore tests.
 * Uses H2 in-memory database as MySQL stand-in (same SQL syntax).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlSecretStoreTest {

    private static javax.sql.DataSource h2DataSource;
    private MysqlSecretStore store;

    @BeforeAll
    static void initDB() throws Exception {
        h2DataSource = createH2();
        // Create table
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tenant_api_key (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(64) NOT NULL,
                    provider VARCHAR(32) NOT NULL,
                    api_key VARCHAR(512) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, provider)
                )
                """);
        }
    }

    @BeforeEach
    void setUp() {
        store = new MysqlSecretStore(h2DataSource);
        // Clean up
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM tenant_api_key");
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("set and get API key")
    void setGet() {
        store.setSecret("t1", "OPENAI_API_KEY", "sk-xxx");
        assertEquals("sk-xxx", store.getSecret("t1", "OPENAI_API_KEY"));
    }

    @Test
    @Order(2)
    @DisplayName("get returns null for missing key")
    void getMissing() {
        assertNull(store.getSecret("t1", "ANTHROPIC_API_KEY"));
    }

    @Test
    @Order(3)
    @DisplayName("remove API key")
    void remove() {
        store.setSecret("t1", "OPENAI_API_KEY", "sk-xxx");
        assertTrue(store.removeSecret("t1", "OPENAI_API_KEY"));
        assertNull(store.getSecret("t1", "OPENAI_API_KEY"));
    }

    @Test
    @Order(4)
    @DisplayName("list secrets returns provider env keys")
    void list() {
        store.setSecret("t1", "OPENAI_API_KEY", "sk-1");
        store.setSecret("t1", "ANTHROPIC_API_KEY", "sk-2");
        var keys = store.listSecrets("t1");
        assertEquals(2, keys.size());
        assertTrue(keys.contains("OPENAI_API_KEY"));
        assertTrue(keys.contains("ANTHROPIC_API_KEY"));
    }

    @Test
    @Order(5)
    @DisplayName("hasSecret")
    void hasSecret() {
        store.setSecret("t1", "OPENAI_API_KEY", "sk-xxx");
        assertTrue(store.hasSecret("t1", "OPENAI_API_KEY"));
        assertFalse(store.hasSecret("t1", "ANTHROPIC_API_KEY"));
    }

    @Test
    @Order(6)
    @DisplayName("loadAll returns all keys")
    void loadAll() {
        store.setSecret("t1", "OPENAI_API_KEY", "sk-1");
        store.setSecret("t1", "ANTHROPIC_API_KEY", "sk-2");
        var all = store.loadAll("t1");
        assertEquals(2, all.size());
        assertEquals("sk-1", all.get("OPENAI_API_KEY"));
    }

    @Test
    @Order(7)
    @DisplayName("upsert: set same provider updates key")
    void upsert() {
        store.setSecret("t1", "OPENAI_API_KEY", "sk-old");
        store.setSecret("t1", "OPENAI_API_KEY", "sk-new");
        assertEquals("sk-new", store.getSecret("t1", "OPENAI_API_KEY"));
    }

    @Test
    @Order(8)
    @DisplayName("tenants are isolated")
    void tenantIsolation() {
        store.setSecret("tenant-A", "OPENAI_API_KEY", "sk-a");
        store.setSecret("tenant-B", "OPENAI_API_KEY", "sk-b");
        assertEquals("sk-a", store.getSecret("tenant-A", "OPENAI_API_KEY"));
        assertEquals("sk-b", store.getSecret("tenant-B", "OPENAI_API_KEY"));
    }

    @Test
    @Order(9)
    @DisplayName("generic API_KEY returns null (only provider-specific keys)")
    void genericApiKey() {
        assertNull(store.getSecret("t1", "API_KEY"));
    }

    @Test
    @Order(10)
    @DisplayName("remove nonexistent returns false")
    void removeNonexistent() {
        assertFalse(store.removeSecret("t1", "OPENAI_API_KEY"));
    }

    // ============ Helper ============

    private static javax.sql.DataSource createH2() {
        return org.h2.jdbcx.JdbcConnectionPool.create(
            "jdbc:h2:mem:test-secret;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
