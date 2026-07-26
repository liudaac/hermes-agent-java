package com.nousresearch.hermes.config.versioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * P3: Config version service - snapshot, rollback, diff, audit trail.
 *
 * <p>Every config change (saveModelConfig, saveApiKey, saveModelRoute,
 * saveQuota) creates a versioned snapshot. Snapshots are stored in MySQL
 * and can be rolled back.</p>
 *
 * <p>Version history is per-tenant, with a monotonic version number.</p>
 */
public class ConfigVersionService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigVersionService.class);

    private final DataSource dataSource;

    public ConfigVersionService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create a snapshot of the current config.
     * Called automatically on every config change, or manually via API.
     */
    public ConfigVersion snapshot(String tenantId, Map<String, Object> config,
                                  String changedBy, String reason) {
        int versionNumber = getNextVersionNumber(tenantId);
        String versionId = "ver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        String sql = """
            INSERT INTO config_version (version_id, tenant_id, version_number, config_json, changed_by, change_reason)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, versionId);
            ps.setString(2, tenantId);
            ps.setInt(3, versionNumber);
            ps.setString(4, com.alibaba.fastjson2.JSON.toJSONString(config));
            ps.setString(5, changedBy);
            ps.setString(6, reason != null ? reason : "manual snapshot");
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to create snapshot for tenant {}: {}", tenantId, e.getMessage());
            throw new RuntimeException("Failed to create config snapshot", e);
        }

        ConfigVersion version = new ConfigVersion(
            versionId, tenantId, versionNumber, config, changedBy, reason, Instant.now());
        logger.info("Config snapshot created: tenant={} version={} (#{})",
            tenantId, versionId, versionNumber);
        return version;
    }

    /**
     * Get a specific version by ID.
     */
    public ConfigVersion getVersion(String versionId) {
        String sql = "SELECT * FROM config_version WHERE version_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to get version {}: {}", versionId, e.getMessage());
        }
        return null;
    }

    /**
     * List version history for a tenant.
     */
    public List<ConfigVersion> listVersions(String tenantId, int limit) {
        String sql = "SELECT * FROM config_version WHERE tenant_id = ? ORDER BY version_number DESC LIMIT ?";
        List<ConfigVersion> versions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setInt(2, Math.min(limit, 100));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) versions.add(mapRow(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to list versions for tenant {}: {}", tenantId, e.getMessage());
        }
        return versions;
    }

    /**
     * Roll back to a specific version.
     * @return the config Map from that version (caller applies it)
     */
    public Map<String, Object> rollback(String tenantId, String versionId) {
        ConfigVersion version = getVersion(versionId);
        if (version == null || !tenantId.equals(version.tenantId())) {
            throw new IllegalArgumentException("Version not found: " + versionId);
        }
        logger.info("Rolling back tenant {} to version {} (#{})",
            tenantId, versionId, version.versionNumber());
        return version.config();
    }

    /**
     * Get the latest version number for a tenant.
     */
    public int getLatestVersionNumber(String tenantId) {
        String sql = "SELECT MAX(version_number) AS max_ver FROM config_version WHERE tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("max_ver");
            }
        } catch (SQLException e) {
            logger.debug("Failed to get latest version for tenant {}: {}", tenantId, e.getMessage());
        }
        return 0;
    }

    // ============ Internal ============

    private int getNextVersionNumber(String tenantId) {
        return getLatestVersionNumber(tenantId) + 1;
    }

    private ConfigVersion mapRow(ResultSet rs) throws SQLException {
        String configJson = rs.getString("config_json");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = configJson != null
            ? com.alibaba.fastjson2.JSON.parseObject(configJson).toJavaObject(Map.class)
            : Map.of();
        return new ConfigVersion(
            rs.getString("version_id"),
            rs.getString("tenant_id"),
            rs.getInt("version_number"),
            config,
            rs.getString("changed_by"),
            rs.getString("change_reason"),
            rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant() : Instant.now()
        );
    }
}
