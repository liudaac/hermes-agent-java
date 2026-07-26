package com.nousresearch.hermes.platform.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * C5: MySQL-backed SecretStore.
 *
 * <p>Reads/writes API keys from {@code tenant_api_key} table.
 * Drop-in replacement for {@link FileSecretStore} in CLUSTER mode.</p>
 */
public class MysqlSecretStore implements SecretStore {

    private static final Logger logger = LoggerFactory.getLogger(MysqlSecretStore.class);

    private final DataSource dataSource;

    public MysqlSecretStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String getSecret(String tenantId, String key) {
        // key format: {PROVIDER}_API_KEY -> extract provider
        String provider = envKeyToProvider(key);
        if (provider == null) return null;

        String sql = "SELECT api_key FROM tenant_api_key WHERE tenant_id = ? AND provider = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, provider);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("api_key");
            }
        } catch (SQLException e) {
            logger.error("Failed to get secret for {}/{}: {}", tenantId, provider, e.getMessage());
        }
        return null;
    }

    @Override
    public void setSecret(String tenantId, String key, String value) {
        String provider = envKeyToProvider(key);
        if (provider == null) {
            logger.warn("Cannot determine provider from key: {}", key);
            return;
        }
        String sql = """
            INSERT INTO tenant_api_key (tenant_id, provider, api_key)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE api_key = VALUES(api_key)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, provider);
            ps.setString(3, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set secret for {}/{}: {}", tenantId, provider, e.getMessage());
        }
    }

    @Override
    public boolean removeSecret(String tenantId, String key) {
        String provider = envKeyToProvider(key);
        if (provider == null) return false;

        String sql = "DELETE FROM tenant_api_key WHERE tenant_id = ? AND provider = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, provider);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to remove secret for {}/{}: {}", tenantId, provider, e.getMessage());
        }
        return false;
    }

    @Override
    public Set<String> listSecrets(String tenantId) {
        String sql = "SELECT provider FROM tenant_api_key WHERE tenant_id = ?";
        Set<String> keys = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(providerToEnvKey(rs.getString("provider")));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list secrets for {}: {}", tenantId, e.getMessage());
        }
        return keys;
    }

    @Override
    public boolean hasSecret(String tenantId, String key) {
        return getSecret(tenantId, key) != null;
    }

    @Override
    public Map<String, String> loadAll(String tenantId) {
        String sql = "SELECT provider, api_key FROM tenant_api_key WHERE tenant_id = ?";
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(
                        providerToEnvKey(rs.getString("provider")),
                        rs.getString("api_key")
                    );
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load all secrets for {}: {}", tenantId, e.getMessage());
        }
        return result;
    }

    // ============ Helper ============

    /** OPENAI_API_KEY -> openai */
    private static String envKeyToProvider(String envKey) {
        if (envKey == null || !envKey.endsWith("_API_KEY") || envKey.equals("API_KEY")) return null;
        return envKey.substring(0, envKey.length() - "_API_KEY".length())
            .toLowerCase().replace("_", "-");
    }

    /** openai -> OPENAI_API_KEY */
    private static String providerToEnvKey(String provider) {
        if (provider == null) return "API_KEY";
        return provider.toUpperCase().replace("-", "_") + "_API_KEY";
    }
}
