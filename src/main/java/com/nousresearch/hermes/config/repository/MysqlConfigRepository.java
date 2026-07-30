package com.nousresearch.hermes.config.repository;

import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.harness.AgentTemplate;
import com.nousresearch.hermes.harness.ForkMode;
import com.nousresearch.hermes.platform.ProviderCatalog;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * MysqlConfigRepository - MySQL-backed implementation.
 *
 * <p>Reads/writes tenant configuration from MySQL tables.
 * Used in CLUSTER mode for multi-instance deployments.</p>
 *
 * <p>Requires tables defined in {@code resources/sql/schema.sql}.</p>
 */
public class MysqlConfigRepository implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(MysqlConfigRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final DataSource dataSource;

    public MysqlConfigRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ============ Model Config ============

    @Override
    public Map<String, Object> loadModelConfig(String tenantId) {
        String sql = "SELECT provider, model, base_url, api_key, temperature, max_tokens, key_source FROM tenant_model_config WHERE tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("provider", rs.getString("provider"));
                    config.put("model", rs.getString("model"));
                    config.put("base_url", rs.getString("base_url"));
                    config.put("api_key", rs.getString("api_key"));
                    config.put("temperature", rs.getDouble("temperature"));
                    config.put("max_tokens", rs.getInt("max_tokens"));
                    config.put("key_source", rs.getString("key_source"));
                    return config;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load model config for tenant {}: {}", tenantId, e.getMessage());
        }
        // Return defaults
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("provider", "doubao");
        defaults.put("model", "deepseek-v3-250324");
        defaults.put("base_url", "");
        defaults.put("api_key", "");
        defaults.put("temperature", 0.7);
        defaults.put("max_tokens", 4096);
        defaults.put("key_source", "hybrid");
        return defaults;
    }

    @Override
    public void saveModelConfig(String tenantId, Map<String, Object> config) {
        String sql = """
            INSERT INTO tenant_model_config (tenant_id, provider, model, base_url, api_key, temperature, max_tokens, key_source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                provider = VALUES(provider),
                model = VALUES(model),
                base_url = VALUES(base_url),
                api_key = VALUES(api_key),
                temperature = VALUES(temperature),
                max_tokens = VALUES(max_tokens),
                key_source = VALUES(key_source)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, (String) config.getOrDefault("provider", "doubao"));
            ps.setString(3, (String) config.getOrDefault("model", "deepseek-v3-250324"));
            ps.setString(4, (String) config.getOrDefault("base_url", ""));
            ps.setString(5, (String) config.getOrDefault("api_key", ""));
            ps.setDouble(6, ((Number) config.getOrDefault("temperature", 0.7)).doubleValue());
            ps.setInt(7, ((Number) config.getOrDefault("max_tokens", 4096)).intValue());
            ps.setString(8, (String) config.getOrDefault("key_source", "hybrid"));
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save model config for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    @Override
    public long getConfigVersion(String tenantId) {
        String sql = "SELECT UNIX_TIMESTAMP(updated_at) * 1000 AS version FROM tenant_model_config WHERE tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("version");
                }
            }
        } catch (SQLException e) {
            logger.debug("getConfigVersion failed for tenant {}: {}", tenantId, e.getMessage());
        }
        return 0;
    }

    // ============ API Keys ============

    @Override
    public Map<String, String> loadApiKeys(String tenantId) {
        String sql = "SELECT provider, api_key FROM tenant_api_key WHERE tenant_id = ?";
        Map<String, String> keys = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.put(rs.getString("provider"), rs.getString("api_key"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load API keys for tenant {}: {}", tenantId, e.getMessage());
        }
        return keys;
    }

    @Override
    public void saveApiKey(String tenantId, String provider, String apiKey) {
        String sql = """
            INSERT INTO tenant_api_key (tenant_id, provider, api_key)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE api_key = VALUES(api_key)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, provider);
            ps.setString(3, apiKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save API key for tenant {}/{}: {}", tenantId, provider, e.getMessage());
        }
    }

    @Override
    public void removeApiKey(String tenantId, String provider) {
        String sql = "DELETE FROM tenant_api_key WHERE tenant_id = ? AND provider = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, provider);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove API key for tenant {}/{}: {}", tenantId, provider, e.getMessage());
        }
    }

    // ============ Model Routes ============

    @Override
    public List<ModelRoute> loadModelRoutes(String tenantId) {
        String sql = "SELECT alias, model, provider, base_url FROM tenant_model_route WHERE tenant_id = ? ORDER BY sort_order";
        List<ModelRoute> routes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    routes.add(new ModelRoute(
                        rs.getString("alias"),
                        rs.getString("model"),
                        rs.getString("provider"),
                        rs.getString("base_url")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load model routes for tenant {}: {}", tenantId, e.getMessage());
        }
        return routes;
    }

    @Override
    public void saveModelRoute(String tenantId, ModelRoute route) {
        String sql = """
            INSERT INTO tenant_model_route (tenant_id, alias, model, provider, base_url)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                model = VALUES(model),
                provider = VALUES(provider),
                base_url = VALUES(base_url)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, route.getAlias());
            ps.setString(3, route.getModel());
            ps.setString(4, route.getProvider());
            ps.setString(5, route.getBaseUrl());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save model route for tenant {}/{}: {}", tenantId, route.getAlias(), e.getMessage());
        }
    }

    @Override
    public void removeModelRoute(String tenantId, String alias) {
        String sql = "DELETE FROM tenant_model_route WHERE tenant_id = ? AND alias = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, alias);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove model route for tenant {}/{}: {}", tenantId, alias, e.getMessage());
        }
    }

    // ============ Quota ============

    @Override
    public TenantQuota loadQuota(String tenantId) {
        String sql = """
            SELECT max_daily_requests, max_daily_tokens, max_concurrent_agents, max_concurrent_sessions,
                   max_storage_bytes, max_memory_bytes, requests_per_second, requests_per_minute,
                   max_tool_calls_per_session, max_file_size_bytes, allow_code_execution,
                   max_private_skills, max_installed_skills, on_exceed, degrade_model, degrade_provider
            FROM tenant_quota WHERE tenant_id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    TenantQuota q = new TenantQuota();
                    q.setMaxDailyRequests(rs.getInt("max_daily_requests"));
                    q.setMaxDailyTokens(rs.getLong("max_daily_tokens"));
                    q.setMaxConcurrentAgents(rs.getInt("max_concurrent_agents"));
                    q.setMaxConcurrentSessions(rs.getInt("max_concurrent_sessions"));
                    q.setMaxStorageBytes(rs.getLong("max_storage_bytes"));
                    q.setMaxMemoryBytes(rs.getLong("max_memory_bytes"));
                    q.setRequestsPerSecond(rs.getInt("requests_per_second"));
                    q.setRequestsPerMinute(rs.getInt("requests_per_minute"));
                    q.setMaxToolCallsPerSession(rs.getInt("max_tool_calls_per_session"));
                    q.setMaxFileSizeBytes(rs.getLong("max_file_size_bytes"));
                    q.setAllowCodeExecution(rs.getBoolean("allow_code_execution"));
                    q.setMaxPrivateSkills(rs.getInt("max_private_skills"));
                    q.setMaxInstalledSkills(rs.getInt("max_installed_skills"));
                    return q;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load quota for tenant {}: {}", tenantId, e.getMessage());
        }
        return TenantQuota.defaults();
    }

    @Override
    public void saveQuota(String tenantId, TenantQuota quota) {
        String sql = """
            INSERT INTO tenant_quota (tenant_id, max_daily_requests, max_daily_tokens, max_concurrent_agents,
                max_concurrent_sessions, max_storage_bytes, max_memory_bytes, requests_per_second,
                requests_per_minute, max_tool_calls_per_session, max_file_size_bytes, allow_code_execution,
                max_private_skills, max_installed_skills)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                max_daily_requests = VALUES(max_daily_requests),
                max_daily_tokens = VALUES(max_daily_tokens),
                max_concurrent_agents = VALUES(max_concurrent_agents),
                max_concurrent_sessions = VALUES(max_concurrent_sessions),
                max_storage_bytes = VALUES(max_storage_bytes),
                max_memory_bytes = VALUES(max_memory_bytes),
                requests_per_second = VALUES(requests_per_second),
                requests_per_minute = VALUES(requests_per_minute),
                max_tool_calls_per_session = VALUES(max_tool_calls_per_session),
                max_file_size_bytes = VALUES(max_file_size_bytes),
                allow_code_execution = VALUES(allow_code_execution),
                max_private_skills = VALUES(max_private_skills),
                max_installed_skills = VALUES(max_installed_skills)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setInt(2, quota.getMaxDailyRequests());
            ps.setLong(3, quota.getMaxDailyTokens());
            ps.setInt(4, quota.getMaxConcurrentAgents());
            ps.setInt(5, quota.getMaxConcurrentSessions());
            ps.setLong(6, quota.getMaxStorageBytes());
            ps.setLong(7, quota.getMaxMemoryBytes());
            ps.setInt(8, quota.getRequestsPerSecond());
            ps.setInt(9, quota.getRequestsPerMinute());
            ps.setInt(10, quota.getMaxToolCallsPerSession());
            ps.setLong(11, quota.getMaxFileSizeBytes());
            ps.setBoolean(12, quota.isAllowCodeExecution());
            ps.setInt(13, quota.getMaxPrivateSkills());
            ps.setInt(14, quota.getMaxInstalledSkills());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save quota for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ============ Platform ============

    @Override
    public ProviderCatalog loadProviderCatalog() {
        String sql = "SELECT provider_id, display_name, default_base_url, allow_tenant_keys, allow_platform_keys, supported_models FROM platform_provider ORDER BY sort_order";
        ProviderCatalog.Builder builder = ProviderCatalog.builder();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String supportedModelsJson = rs.getString("supported_models");
                List<String> models = supportedModelsJson != null && !supportedModelsJson.isBlank()
                    ? mapper.readValue(supportedModelsJson, List.class)
                    : List.of();
                builder.add(new ProviderCatalog.Provider(
                    rs.getString("provider_id"),
                    rs.getString("display_name"),
                    rs.getString("default_base_url"),
                    rs.getBoolean("allow_tenant_keys"),
                    rs.getBoolean("allow_platform_keys"),
                    models
                ));
            }
        } catch (Exception e) {
            logger.error("Failed to load provider catalog from DB, using defaults: {}", e.getMessage());
            return ProviderCatalog.withDefaults();
        }
        ProviderCatalog catalog = builder.build();
        return catalog.listProviderIds().isEmpty() ? ProviderCatalog.withDefaults() : catalog;
    }

    @Override
    public List<ModelRoute> loadPlatformModelRoutes() {
        String sql = "SELECT alias, model, provider, base_url FROM platform_model_route ORDER BY sort_order";
        List<ModelRoute> routes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                routes.add(new ModelRoute(
                    rs.getString("alias"),
                    rs.getString("model"),
                    rs.getString("provider"),
                    rs.getString("base_url")
                ));
            }
        } catch (SQLException e) {
            logger.error("Failed to load platform model routes: {}", e.getMessage());
        }
        return routes;
    }

    @Override
    public String loadPlatformApiKey(String provider) {
        String sql = "SELECT api_key FROM platform_api_key WHERE provider = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, provider);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("api_key");
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load platform API key for {}: {}", provider, e.getMessage());
        }
        return null;
    }

    
    // ============ Agent Templates ============

    @Override
    public Map<String, AgentTemplate> loadAgentTemplates(String tenantId) {
        Map<String, AgentTemplate> result = new LinkedHashMap<>();
        String sql = "SELECT template_name, description, system_prompt, tool_whitelist, max_iterations, fork_mode " +
                     "FROM tenant_agent_template WHERE tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("template_name");
                    String desc = rs.getString("description");
                    String prompt = rs.getString("system_prompt");
                    String toolsJson = rs.getString("tool_whitelist");
                    Set<String> tools = new LinkedHashSet<>();
                    if (toolsJson != null && !toolsJson.isBlank()) {
                        tools = new ObjectMapper().readValue(toolsJson, 
                            new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {});
                    }
                    int maxIter = rs.getInt("max_iterations");
                    ForkMode fork = ForkMode.valueOf(rs.getString("fork_mode").toUpperCase());
                    result.put(name.toLowerCase().trim(), 
                        new AgentTemplate(name, desc != null ? desc : "", prompt, tools, maxIter, fork));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load agent templates for {}: {}", tenantId, e.getMessage());
        }
        return result;
    }

    @Override
    public void saveAgentTemplate(String tenantId, String name, AgentTemplate template) {
        String sql = "INSERT INTO tenant_agent_template (tenant_id, template_name, description, system_prompt, tool_whitelist, max_iterations, fork_mode) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                     "description=VALUES(description), system_prompt=VALUES(system_prompt), " +
                     "tool_whitelist=VALUES(tool_whitelist), max_iterations=VALUES(max_iterations), fork_mode=VALUES(fork_mode)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, template.description());
            ps.setString(4, template.systemPrompt());
            ps.setString(5, new ObjectMapper().writeValueAsString(template.toolWhitelist()));
            ps.setInt(6, template.maxIterations());
            ps.setString(7, template.defaultForkMode().name());
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to save agent template: {}", e.getMessage());
        }
    }

    @Override
    public void deleteAgentTemplate(String tenantId, String name) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM tenant_agent_template WHERE tenant_id = ? AND template_name = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to delete agent template: {}", e.getMessage());
        }
    }

    // ============ Tenant Settings (KV) ============

    @Override
    public String loadTenantSetting(String tenantId, String key) {
        String sql = "SELECT setting_value FROM tenant_setting WHERE tenant_id = ? AND setting_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("setting_value");
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load setting {}/{}: {}", tenantId, key, e.getMessage());
        }
        return null;
    }

    @Override
    public void saveTenantSetting(String tenantId, String key, String value) {
        String sql = """
            INSERT INTO tenant_setting (tenant_id, setting_key, setting_value)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save setting {}/{}: {}", tenantId, key, e.getMessage());
        }
    }

    @Override
    public Map<String, String> loadAllTenantSettings(String tenantId) {
        String sql = "SELECT setting_key, setting_value FROM tenant_setting WHERE tenant_id = ?";
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("setting_key"), rs.getString("setting_value"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load all settings for tenant {}: {}", tenantId, e.getMessage());
        }
        return result;
    }
}
