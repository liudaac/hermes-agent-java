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

    @Test
    @Order(4)
    @DisplayName("listVersions returns history sorted by version number desc")
    void listVersions() {
        versionService.snapshot("t1", Map.of("v", 1), "admin", "v1");
        versionService.snapshot("t1", Map.of("v", 2), "admin", "v2");
        versionService.snapshot("t1", Map.of("v", 3), "admin", "v3");

        var versions = versionService.listVersions("t1", 10);
        assertEquals(3, versions.size());
        assertEquals(3, versions.get(0).versionNumber()); // newest first
        assertEquals(1, versions.get(2).versionNumber()); // oldest last
    }

    @Test
    @Order(5)
    @DisplayName("listVersions respects limit")
    void listVersions_limit() {
        for (int i = 0; i < 10; i++) {
            versionService.snapshot("t1", Map.of("v", i), "admin", "v" + i);
        }
        assertEquals(3, versionService.listVersions("t1", 3).size());
    }

    @Test
    @Order(6)
    @DisplayName("rollback returns config from that version")
    void rollback() {
        versionService.snapshot("t1", Map.of("provider", "openai"), "admin", "original");
        ConfigVersion v2 = versionService.snapshot("t1",
            Map.of("provider", "anthropic"), "admin", "changed");

        Map<String, Object> rolledBack = versionService.rollback("t1", v2.versionId());
        assertEquals("anthropic", rolledBack.get("provider"));
    }

    @Test
    @Order(7)
    @DisplayName("rollback throws for wrong tenant")
    void rollback_wrongTenant() {
        ConfigVersion v = versionService.snapshot("t1", Map.of("v", 1), "admin", "test");
        assertThrows(IllegalArgumentException.class, () ->
            versionService.rollback("t2", v.versionId()));
    }

    @Test
    @Order(8)
    @DisplayName("getLatestVersionNumber returns 0 for new tenant")
    void latestVersion_newTenant() {
        assertEquals(0, versionService.getLatestVersionNumber("new-tenant"));
    }

    @Test
    @Order(9)
    @DisplayName("toApi returns summary without full config")
    void toApi() {
        ConfigVersion v = versionService.snapshot("t1",
            Map.of("provider", "openai", "model", "gpt-4o"), "admin", "test");
        var api = v.toApi();
        assertEquals("t1", api.get("tenantId"));
        assertNotNull(api.get("configKeys"));
        assertFalse(((Set<?>) api.get("configKeys")).isEmpty());
    }

    // ============ CanaryDeploymentManager ============

    @Test
    @Order(10)
    @DisplayName("IMMEDIATE strategy: all sessions use canary")
    void canary_immediate() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.IMMEDIATE, 100, false, 0);
        assertTrue(canaryManager.shouldUseCanary("t1", "session-a"));
        assertTrue(canaryManager.shouldUseCanary("t1", "session-b"));
    }

    @Test
    @Order(11)
    @DisplayName("PERCENTAGE strategy: deterministic session assignment")
    void canary_percentage_deterministic() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 50, false, 0);

        // Same session should always get the same answer
        boolean first = canaryManager.shouldUseCanary("t1", "session-x");
        boolean second = canaryManager.shouldUseCanary("t1", "session-x");
        assertEquals(first, second);
    }

    @Test
    @Order(12)
    @DisplayName("PERCENTAGE strategy: 0% means no canary")
    void canary_zeroPercent() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 0, false, 0);
        assertFalse(canaryManager.shouldUseCanary("t1", "any-session"));
    }

    @Test
    @Order(13)
    @DisplayName("PERCENTAGE strategy: 100% means all canary")
    void canary_hundredPercent() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 100, false, 0);
        assertTrue(canaryManager.shouldUseCanary("t1", "any-session"));
    }

    @Test
    @Order(14)
    @DisplayName("promote increases percentage")
    void canary_promote() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 10, false, 0);
        canaryManager.promote("t1", 50);

        var status = canaryManager.getStatus("t1");
        assertEquals(50, status.get("percentage"));
    }

    @Test
    @Order(15)
    @DisplayName("complete removes canary")
    void canary_complete() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.IMMEDIATE, 100, false, 0);
        canaryManager.complete("t1");
        assertFalse(canaryManager.hasCanary("t1"));
    }

    @Test
    @Order(16)
    @DisplayName("abort removes canary with reason")
    void canary_abort() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 50, true, 5);
        canaryManager.abort("t1", "too many errors");
        assertFalse(canaryManager.hasCanary("t1"));
    }

    @Test
    @Order(17)
    @DisplayName("EXPLICIT strategy: only listed sessions get canary")
    void canary_explicit() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.EXPLICIT, 0, false, 0);
        canaryManager.addSession("t1", "special-session");

        assertTrue(canaryManager.shouldUseCanary("t1", "special-session"));
        assertFalse(canaryManager.shouldUseCanary("t1", "normal-session"));
    }

    @Test
    @Order(18)
    @DisplayName("hasCanary returns false when no canary active")
    void canary_none() {
        assertFalse(canaryManager.hasCanary("t1"));
        assertFalse(canaryManager.shouldUseCanary("t1", "any"));
    }

    @Test
    @Order(19)
    @DisplayName("getStatus returns active=false when no canary")
    void canary_statusInactive() {
        var status = canaryManager.getStatus("t1");
        assertEquals(false, status.get("active"));
    }

    @Test
    @Order(20)
    @DisplayName("getStatus returns full canary info when active")
    void canary_statusActive() {
        canaryManager.startCanary("t1", "ver_123",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 30, true, 10);
        var status = canaryManager.getStatus("t1");
        assertEquals(true, status.get("active"));
        assertEquals("ver_123", status.get("versionId"));
        assertEquals("PERCENTAGE", status.get("strategy"));
        assertEquals(30, status.get("percentage"));
        assertEquals(true, status.get("autoRollback"));
        assertEquals(10, status.get("errorThreshold"));
    }

    @Test
    @Order(21)
    @DisplayName("percentage clamped to 0-100")
    void canary_clampPercentage() {
        canaryManager.startCanary("t1", "ver_1",
            CanaryDeploymentManager.Strategy.PERCENTAGE, 150, false, 0);
        var status = canaryManager.getStatus("t1");
        assertEquals(100, status.get("percentage"));

        canaryManager.promote("t1", -10);
        status = canaryManager.getStatus("t1");
        assertEquals(0, status.get("percentage"));
    }
}
