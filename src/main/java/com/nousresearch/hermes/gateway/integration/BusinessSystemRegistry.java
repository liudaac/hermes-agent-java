package com.nousresearch.hermes.gateway.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D1: Registry for business systems - CRUD + API Key verification.
 *
 * <p>Manages the lifecycle of registered business systems and provides
 * API Key verification for the auth middleware.</p>
 *
 * <p>Uses MySQL for persistence (CLUSTER mode) with in-memory cache
 * for fast API Key lookups on every request.</p>
 */
public class BusinessSystemRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BusinessSystemRegistry.class);

    private final DataSource dataSource;
    // Cache: apiKey -> BusinessSystem (for fast auth middleware lookup)
    private final ConcurrentHashMap<String, BusinessSystem> apiKeyCache = new ConcurrentHashMap<>();
    // Cache: tenantId -> List<BusinessSystem>
    private final ConcurrentHashMap<String, List<BusinessSystem>> tenantCache = new ConcurrentHashMap<>();

    public BusinessSystemRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Register a new business system. Generates a random API Key.
     *
     * @param systemId     unique system identifier
     * @param displayName  human-readable name
     * @param tenantId     owning tenant
     * @param workspaceId  default workspace (may be null)
     * @param scopes       allowed scopes (e.g. "read,write")
     * @return the created BusinessSystem with generated API Key
     */
    public BusinessSystem register(String systemId, String displayName,
                                   String tenantId, String workspaceId,
                                   String scopes) {
        String apiKey = generateApiKey();
        String scopeStr = scopes != null ? scopes : "read,write";

        String sql = """
            INSERT INTO business_system (system_id, display_name, api_key, tenant_id, workspace_id, allowed_scopes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                display_name = VALUES(display_name),
                api_key = VALUES(api_key),
                tenant_id = VALUES(tenant_id),
                workspace_id = VALUES(workspace_id),
                allowed_scopes = VALUES(allowed_scopes),
                is_active = 1
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, systemId);
            ps.setString(2, displayName);
            ps.setString(3, apiKey);
            ps.setString(4, tenantId);
            ps.setString(5, workspaceId);
            ps.setString(6, scopeStr);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to register business system {}: {}", systemId, e.getMessage());
            throw new RuntimeException("Failed to register business system", e);
        }

        // Invalidate cache
        tenantCache.remove(tenantId);

        BusinessSystem system = new BusinessSystem(
            systemId, displayName, apiKey, tenantId, workspaceId,
            parseScopes(scopeStr), null, null, true, Instant.now()
        );
        apiKeyCache.put(apiKey, system);
        logger.info("Registered business system: {} (tenant={}, key={}...)",
            systemId, tenantId, apiKey.substring(0, 8));
        return system;
    }

    /**
     * Verify an API Key and return the associated business system.
     * Used by auth middleware on every /api/v1/ request.
     *
     * @param apiKey the API key (ak_xxx)
     * @return the BusinessSystem, or null if invalid/inactive
     */
    public BusinessSystem verifyApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;

        // Check cache first
        BusinessSystem cached = apiKeyCache.get(apiKey);
        if (cached != null) {
            return cached.active() ? cached : null;
        }

        // Cache miss -> query DB
        BusinessSystem system = loadByApiKey(apiKey);
        if (system != null) {
            apiKeyCache.put(apiKey, system);
        }
        return system != null && system.active() ? system : null;
    }

    /**
     * List all registered systems for a tenant.
     */
    public List<BusinessSystem> listByTenant(String tenantId) {
        List<BusinessSystem> cached = tenantCache.get(tenantId);
        if (cached != null) return cached;

        List<BusinessSystem> systems = new ArrayList<>();
        String sql = "SELECT * FROM business_system WHERE tenant_id = ? AND is_active = 1 ORDER BY created_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    systems.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list business systems for tenant {}: {}", tenantId, e.getMessage());
        }
        tenantCache.put(tenantId, systems);
        return systems;
    }

    /**
     * Get a business system by system_id.
     */
    public BusinessSystem get(String systemId) {
        String sql = "SELECT * FROM business_system WHERE system_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, systemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to get business system {}: {}", systemId, e.getMessage());
        }
        return null;
    }

    /**
     * Deactivate a business system (revoke API Key).
     */
    public boolean deactivate(String systemId) {
        String sql = "UPDATE business_system SET is_active = 0 WHERE system_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, systemId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                // Clear cache
                apiKeyCache.values().removeIf(s -> s.systemId().equals(systemId));
                tenantCache.clear();
                logger.info("Deactivated business system: {}", systemId);
                return true;
            }
        } catch (SQLException e) {
            logger.error("Failed to deactivate business system {}: {}", systemId, e.getMessage());
        }
        return false;
    }

    /**
     * Update webhook configuration for a business system.
     */
    public void updateWebhook(String systemId, String url, String secret) {
        String sql = "UPDATE business_system SET webhook_url = ?, webhook_secret = ? WHERE system_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setString(2, secret);
            ps.setString(3, systemId);
            ps.executeUpdate();
            apiKeyCache.clear();
            tenantCache.clear();
        } catch (SQLException e) {
            logger.error("Failed to update webhook for {}: {}", systemId, e.getMessage());
        }
    }

    /**
     * Invalidate all caches (for Admin API use).
     */
    public void invalidateCache() {
        apiKeyCache.clear();
        tenantCache.clear();
    }

    // ============ Internal ============

    private BusinessSystem loadByApiKey(String apiKey) {
        String sql = "SELECT * FROM business_system WHERE api_key = ? AND is_active = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, apiKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.debug("Failed to load business system by API key: {}", e.getMessage());
        }
        return null;
    }

    private BusinessSystem mapRow(ResultSet rs) throws SQLException {
        return new BusinessSystem(
            rs.getString("system_id"),
            rs.getString("display_name"),
            rs.getString("api_key"),
            rs.getString("tenant_id"),
            rs.getString("workspace_id"),
            parseScopes(rs.getString("allowed_scopes")),
            rs.getString("webhook_url"),
            rs.getString("webhook_secret"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant() : Instant.now()
        );
    }

    private static Set<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) return Set.of("read", "write");
        Set<String> result = new LinkedHashSet<>();
        for (String s : scopes.split(",")) {
            String trimmed = s.trim().toLowerCase();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static String generateApiKey() {
        return "ak_" + UUID.randomUUID().toString().replace("-", "");
    }
}
