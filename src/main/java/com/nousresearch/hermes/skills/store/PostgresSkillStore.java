package com.nousresearch.hermes.skills.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Postgres-backed {@link SkillStore} with version persistence and rollback.
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE skill_registry (
 *     id              TEXT PRIMARY KEY,
 *     tenant_id       VARCHAR(64) NOT NULL,
 *     name            VARCHAR(128) NOT NULL,
 *     description     TEXT,
 *     scope           VARCHAR(16) DEFAULT 'PRIVATE',
 *     type            VARCHAR(32) DEFAULT 'CUSTOM',
 *     enabled         BOOLEAN DEFAULT true,
 *     current_version VARCHAR(64) DEFAULT '1.0.0',
 *     created_at      BIGINT NOT NULL,
 *     updated_at      BIGINT NOT NULL,
 *     UNIQUE(tenant_id, name)
 * );
 *
 * CREATE TABLE skill_version (
 *     id           TEXT PRIMARY KEY,
 *     skill_id     TEXT NOT NULL REFERENCES skill_registry(id) ON DELETE CASCADE,
 *     version      VARCHAR(64) NOT NULL,
 *     config       TEXT NOT NULL,
 *     published_at BIGINT NOT NULL,
 *     UNIQUE(skill_id, version)
 * );
 * </pre>
 */
public class PostgresSkillStore implements SkillStore {

    private static final Logger logger = LoggerFactory.getLogger(PostgresSkillStore.class);

    private final DataSource dataSource;
    private final List<SkillChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger seqCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    public PostgresSkillStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS skill_registry (
                    id              TEXT PRIMARY KEY,
                    tenant_id       VARCHAR(64) NOT NULL,
                    name            VARCHAR(128) NOT NULL,
                    description     TEXT,
                    scope           VARCHAR(16) DEFAULT 'PRIVATE',
                    type            VARCHAR(32) DEFAULT 'CUSTOM',
                    enabled         BOOLEAN DEFAULT true,
                    current_version VARCHAR(64) DEFAULT '1.0.0',
                    created_at      BIGINT NOT NULL,
                    updated_at      BIGINT NOT NULL
                )
                """);
            try { st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_tenant_name ON skill_registry(tenant_id, name)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_skill_tenant ON skill_registry(tenant_id)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_skill_scope ON skill_registry(scope) WHERE scope != 'PRIVATE'"); } catch (SQLException ignored) {}

            st.execute("""
                CREATE TABLE IF NOT EXISTS skill_version (
                    id           TEXT PRIMARY KEY,
                    skill_id     TEXT NOT NULL,
                    version      VARCHAR(64) NOT NULL,
                    config       TEXT NOT NULL,
                    published_at BIGINT NOT NULL
                )
                """);
            try { st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_version ON skill_version(skill_id, version)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_sv_skill ON skill_version(skill_id)"); } catch (SQLException ignored) {}

            logger.info("PostgresSkillStore schema initialized");
        } catch (SQLException e) {
            logger.error("Failed to init skill schema: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Registration
    // ══════════════════════════════════════════════════════════════════

    @Override
    public String register(String tenantId, SkillRegistration reg) {
        // Check name uniqueness
        if (findByName(tenantId, reg.name()) != null) {
            throw new IllegalStateException(
                "Skill '" + reg.name() + "' already exists for tenant " + tenantId);
        }

        String skillId = "skill_" + seqCounter.incrementAndGet();
        long now = Instant.now().toEpochMilli();

        String sql = """
            INSERT INTO skill_registry (id, tenant_id, name, description, scope, type, enabled,
                current_version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, true, '1.0.0', ?, ?)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.setString(2, tenantId);
            ps.setString(3, reg.name());
            ps.setString(4, reg.description() != null ? reg.description() : "");
            ps.setString(5, reg.scope().name());
            ps.setString(6, reg.type().name());
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to register skill: {}", e.getMessage());
            return null;
        }

        // Save initial version
        publishVersionInternal(skillId, "1.0.0", reg.config());

        notifyChange(tenantId, skillId, reg.name(), ChangeAction.REGISTERED);
        logger.info("Registered skill: {} ({}) for tenant: {}", reg.name(), skillId, tenantId);
        return skillId;
    }

    @Override
    public void unregister(String tenantId, String skillId) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null) return;
        if (info.scope() == SkillScope.SYSTEM) {
            throw new IllegalStateException("Cannot unregister system skill: " + skillId);
        }

        // Delete versions first
        String delVer = "DELETE FROM skill_version WHERE skill_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(delVer)) {
            ps.setString(1, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete skill versions: {}", e.getMessage());
        }

        String delReg = "DELETE FROM skill_registry WHERE id = ? AND tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(delReg)) {
            ps.setString(1, skillId);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to unregister skill: {}", e.getMessage());
        }

        notifyChange(tenantId, skillId, info.name(), ChangeAction.UNREGISTERED);
    }

    @Override
    public void enable(String tenantId, String skillId) {
        updateEnabled(tenantId, skillId, true);
        SkillInfo info = get(tenantId, skillId);
        if (info != null) notifyChange(tenantId, skillId, info.name(), ChangeAction.ENABLED);
    }

    @Override
    public void disable(String tenantId, String skillId) {
        updateEnabled(tenantId, skillId, false);
        SkillInfo info = get(tenantId, skillId);
        if (info != null) notifyChange(tenantId, skillId, info.name(), ChangeAction.DISABLED);
    }

    private void updateEnabled(String tenantId, String skillId, boolean enabled) {
        String sql = "UPDATE skill_registry SET enabled = ?, updated_at = ? WHERE id = ? AND tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setString(3, skillId);
            ps.setString(4, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update skill enabled: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Discovery
    // ══════════════════════════════════════════════════════════════════

    @Override
    public List<SkillInfo> list(String tenantId, SkillScope scope) {
        String sql;
        if (scope == null) {
            sql = "SELECT * FROM skill_registry WHERE tenant_id = ? OR scope != 'PRIVATE' ORDER BY updated_at DESC";
        } else if (scope == SkillScope.PRIVATE) {
            sql = "SELECT * FROM skill_registry WHERE tenant_id = ? AND scope = 'PRIVATE' ORDER BY updated_at DESC";
        } else {
            sql = "SELECT * FROM skill_registry WHERE scope = ? ORDER BY updated_at DESC";
        }

        List<SkillInfo> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (scope == null) {
                ps.setString(1, tenantId);
            } else if (scope == SkillScope.PRIVATE) {
                ps.setString(1, tenantId);
            } else {
                ps.setString(1, scope.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(resultSetToSkillInfo(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list skills: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public SkillInfo get(String tenantId, String skillId) {
        String sql = "SELECT * FROM skill_registry WHERE id = ? AND (tenant_id = ? OR scope != 'PRIVATE')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return resultSetToSkillInfo(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to get skill: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public SkillInfo findByName(String tenantId, String name) {
        String sql = "SELECT * FROM skill_registry WHERE name = ? AND (tenant_id = ? OR scope != 'PRIVATE')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return resultSetToSkillInfo(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to find skill by name: {}", e.getMessage());
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Version Management
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void publishVersion(String tenantId, String skillId,
                                String version, SkillConfig config) {
        publishVersionInternal(skillId, version, config);

        String sql = "UPDATE skill_registry SET current_version = ?, updated_at = ? WHERE id = ? AND tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, version);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setString(3, skillId);
            ps.setString(4, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update current version: {}", e.getMessage());
        }

        SkillInfo info = get(tenantId, skillId);
        if (info != null) notifyChange(tenantId, skillId, info.name(), ChangeAction.VERSION_PUBLISHED);
    }

    private void publishVersionInternal(String skillId, String version, SkillConfig config) {
        String sql = """
            INSERT INTO skill_version (id, skill_id, version, config, published_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (skill_id, version) DO UPDATE SET config = EXCLUDED.config
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "sv_" + System.nanoTime());
            ps.setString(2, skillId);
            ps.setString(3, version);
            ps.setString(4, com.alibaba.fastjson2.JSON.toJSONString(config));
            ps.setLong(5, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to publish version: {}", e.getMessage());
        }
    }

    @Override
    public SkillConfig getActiveVersion(String tenantId, String skillId) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null || info.currentVersion() == null) return null;

        String sql = "SELECT config FROM skill_version WHERE skill_id = ? AND version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, skillId);
            ps.setString(2, info.currentVersion());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return com.alibaba.fastjson2.JSON.parseObject(
                        rs.getString("config"), SkillConfig.class);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get active version: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void rollback(String tenantId, String skillId, String version) {
        // Check version exists
        List<String> versions = listVersions(tenantId, skillId);
        if (!versions.contains(version)) {
            throw new IllegalArgumentException("Version not found: " + version);
        }

        String sql = "UPDATE skill_registry SET current_version = ?, updated_at = ? WHERE id = ? AND tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, version);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setString(3, skillId);
            ps.setString(4, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to rollback: {}", e.getMessage());
        }

        SkillInfo info = get(tenantId, skillId);
        if (info != null) notifyChange(tenantId, skillId, info.name(), ChangeAction.ROLLED_BACK);
    }

    @Override
    public List<String> listVersions(String tenantId, String skillId) {
        String sql = "SELECT version FROM skill_version WHERE skill_id = ? ORDER BY published_at";
        List<String> versions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.add(rs.getString("version"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list versions: {}", e.getMessage());
        }
        return versions;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Change Notification
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void subscribeChanges(SkillChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyChange(String tenantId, String skillId,
                              String skillName, ChangeAction action) {
        SkillChangeEvent event = new SkillChangeEvent(
            tenantId, skillId, skillName, action, Instant.now()
        );
        for (SkillChangeListener listener : listeners) {
            try {
                listener.onSkillChange(event);
            } catch (Exception e) {
                logger.warn("Skill change listener error: {}", e.getMessage());
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    private SkillInfo resultSetToSkillInfo(ResultSet rs) throws SQLException {
        String skillId = rs.getString("id");
        List<String> versions = listVersionsById(skillId);

        return new SkillInfo(
            skillId,
            rs.getString("tenant_id"),
            rs.getString("name"),
            rs.getString("description"),
            SkillScope.valueOf(rs.getString("scope")),
            SkillType.valueOf(rs.getString("type")),
            rs.getBoolean("enabled"),
            rs.getString("current_version"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            Instant.ofEpochMilli(rs.getLong("updated_at")),
            versions
        );
    }

    private List<String> listVersionsById(String skillId) {
        String sql = "SELECT version FROM skill_version WHERE skill_id = ? ORDER BY published_at";
        List<String> versions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.add(rs.getString("version"));
                }
            }
        } catch (SQLException e) {
            logger.debug("Failed to list versions for {}: {}", skillId, e.getMessage());
        }
        return versions;
    }
}
