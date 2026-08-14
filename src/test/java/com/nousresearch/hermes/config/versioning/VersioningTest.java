package com.nousresearch.hermes.config.versioning;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3: ConfigVersionService + CanaryDeploymentManager tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VersioningTest {

    private static javax.sql.DataSource h2DataSource;
    private ConfigVersionService versionService;
    private CanaryDeploymentManager canaryManager;

    @BeforeAll
    static void initDB() throws Exception {
        h2DataSource = org.h2.jdbcx.JdbcConnectionPool.create(
            "jdbc:h2:mem:test-version;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS config_version (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    version_id VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    version_number INT NOT NULL,
                    config_json TEXT NOT NULL,
                    changed_by VARCHAR(64) DEFAULT NULL,
                    change_reason VARCHAR(256) DEFAULT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE (version_id)
                )
                """);
        }
    }

    @BeforeEach
    void setUp() {
        versionService = new ConfigVersionService(h2DataSource);
        canaryManager = new CanaryDeploymentManager();
        // Clean up
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM config_version");
        } catch (Exception ignored) {}
    }

    // ============ ConfigVersionService ============

    @Test
    @Order(1)
    @DisplayName("snapshot creates version with incrementing number")
    void snapshot_createsVersion() {
        ConfigVersion v1 = versionService.snapshot("t1",
            Map.of("provider", "openai", "model", "gpt-4o"), "admin", "initial");
        ConfigVersion v2 = versionService.snapshot("t1",
            Map.of("provider", "anthropic", "model", "claude"), "admin", "change provider");

        assertEquals(1, v1.versionNumber());
        assertEquals(2, v2.versionNumber());
        assertNotNull(v1.versionId());
        assertNotNull(v2.versionId());
        assertNotEquals(v1.versionId(), v2.versionId());
    }

    @Test
    @Order(2)
    @DisplayName("getVersion retrieves by ID")
    void getVersion() {
        ConfigVersion v = versionService.snapshot("t1",
            Map.of("provider", "openai"), "admin", "test");
        ConfigVersion found = versionService.getVersion(v.versionId());
        assertNotNull(found);
        assertEquals("openai", found.config().get("provider"));
    }

    @Test
    @Order(3)
    @DisplayName("getVersion returns null for nonexistent")
    void getVersion_nonexistent() {
        assertNull(versionService.getVersion("ver_nonexistent"));
    }


    // ============ CanaryDeploymentManager ============

}
