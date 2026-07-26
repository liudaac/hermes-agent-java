package com.nousresearch.hermes.config.repository;

import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.platform.ProviderCatalog;
import com.nousresearch.hermes.tenant.core.TenantConfig;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * LocalConfigRepository - file-based implementation.
 *
 * <p>Reads/writes tenant configuration from the local filesystem:
 * <pre>
 * {hermesHome}/tenants/{tenantId}/
 *   config/config.yaml
 *   config/secrets.env
 * </pre>
 *
 * <p>This is the default implementation for LOCAL mode. All methods
 * delegate to {@link TenantConfig} which handles file I/O.</p>
 */
public class LocalConfigRepository implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(LocalConfigRepository.class);

    private final Path hermesHome;

    public LocalConfigRepository(Path hermesHome) {
        this.hermesHome = hermesHome;
    }

    private Path tenantDir(String tenantId) {
        return hermesHome.resolve("tenants").resolve(tenantId);
    }

    private TenantConfig loadTenantConfig(String tenantId) {
        Path configDir = tenantDir(tenantId).resolve("config");
        if (!Files.exists(configDir)) {
            return new TenantConfig(configDir, Map.of());
        }
        return TenantConfig.load(configDir);
    }

    @Override
    public Map<String, Object> loadModelConfig(String tenantId) {
        TenantConfig tc = loadTenantConfig(tenantId);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("provider", tc.getModelProvider());
        model.put("model", tc.getModelName());
        model.put("base_url", tc.getString("model.base_url", ""));
        model.put("temperature", tc.get("model.temperature", 0.7));
        model.put("max_tokens", tc.getInt("model.max_tokens", 4096));
        model.put("key_source", tc.getKeySource());
        model.put("api_key", tc.getString("model.api_key", ""));
        return model;
    }

    @Override
    public void saveModelConfig(String tenantId, Map<String, Object> config) {
        TenantConfig tc = loadTenantConfig(tenantId);
        if (config.containsKey("provider")) tc.set("model.provider", config.get("provider"));
        if (config.containsKey("model")) tc.set("model.model", config.get("model"));
        if (config.containsKey("base_url")) tc.set("model.base_url", config.get("base_url"));
        if (config.containsKey("temperature")) tc.set("model.temperature", config.get("temperature"));
        if (config.containsKey("max_tokens")) tc.set("model.max_tokens", config.get("max_tokens"));
        if (config.containsKey("key_source")) tc.set("model.key_source", config.get("key_source"));
        if (config.containsKey("api_key")) tc.set("model.api_key", config.get("api_key"));
        tc.save();
    }

    @Override
    public long getConfigVersion(String tenantId) {
        Path configFile = tenantDir(tenantId).resolve("config").resolve("config.yaml");
        if (!Files.exists(configFile)) return 0;
        try {
            return Files.getLastModifiedTime(configFile).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public Map<String, String> loadApiKeys(String tenantId) {
        TenantConfig tc = loadTenantConfig(tenantId);
        // Load all secrets that end with _API_KEY
        Map<String, String> keys = new LinkedHashMap<>();
        for (var entry : tc.listProviderApiKeys().entrySet()) {
            String providerId = entry.getKey();
            String envKey = entry.getValue();
            String value = tc.getSecret(envKey);
            if (value != null && !value.isBlank()) {
                keys.put(providerId, value);
            }
        }
        // Also include generic API_KEY
        String generic = tc.getSecret("API_KEY");
        if (generic != null && !generic.isBlank()) {
            keys.put("_generic", generic);
        }
        return keys;
    }

    @Override
    public void saveApiKey(String tenantId, String provider, String apiKey) {
        TenantConfig tc = loadTenantConfig(tenantId);
        tc.setProviderApiKey(provider, apiKey);
    }

    @Override
    public void removeApiKey(String tenantId, String provider) {
        TenantConfig tc = loadTenantConfig(tenantId);
        tc.removeProviderApiKey(provider);
    }

    @Override
    public List<ModelRoute> loadModelRoutes(String tenantId) {
        TenantConfig tc = loadTenantConfig(tenantId);
        return tc.getModelRoutes();
    }

    @Override
    public void saveModelRoute(String tenantId, ModelRoute route) {
        TenantConfig tc = loadTenantConfig(tenantId);
        List<ModelRoute> existing = tc.getModelRoutes();
        // Remove existing with same alias, then add new
        List<Map<String, Object>> routes = new java.util.ArrayList<>();
        boolean replaced = false;
        for (ModelRoute r : existing) {
            if (!r.getAlias().equalsIgnoreCase(route.getAlias())) {
                routes.add(routeToMap(r));
            } else {
                routes.add(routeToMap(route));
                replaced = true;
            }
        }
        if (!replaced) {
            routes.add(routeToMap(route));
        }
        tc.set("model_routes", routes);
        tc.save();
    }

    @Override
    public void removeModelRoute(String tenantId, String alias) {
        TenantConfig tc = loadTenantConfig(tenantId);
        List<ModelRoute> existing = tc.getModelRoutes();
        List<Map<String, Object>> routes = new java.util.ArrayList<>();
        for (ModelRoute r : existing) {
            if (!r.getAlias().equalsIgnoreCase(alias)) {
                routes.add(routeToMap(r));
            }
        }
        tc.set("model_routes", routes);
        tc.save();
    }

    @Override
    public TenantQuota loadQuota(String tenantId) {
        // Local mode: quota is loaded by TenantQuotaManager from files
        // Return defaults for now
        return TenantQuota.defaults();
    }

    @Override
    public void saveQuota(String tenantId, TenantQuota quota) {
        // Local mode: quota is managed by TenantQuotaManager
        logger.debug("saveQuota delegated to TenantQuotaManager (local mode) for tenant={}", tenantId);
    }

    @Override
    public ProviderCatalog loadProviderCatalog() {
        // Local mode: use hardcoded defaults
        return ProviderCatalog.withDefaults();
    }

    @Override
    public List<ModelRoute> loadPlatformModelRoutes() {
        // Local mode: no platform routes table, return empty
        return List.of();
    }

    @Override
    public String loadPlatformApiKey(String provider) {
        // Local mode: check system properties / env vars
        String envKey = TenantConfig.providerToEnvKey(provider);
        String key = System.getProperty("platform." + envKey);
        if (key != null && !key.isBlank()) return key;
        return System.getenv("PLATFORM_" + envKey);
    }

    // ============ Helper ============

    private Map<String, Object> routeToMap(ModelRoute route) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alias", route.getAlias());
        m.put("model", route.getModel());
        if (route.getProvider() != null) m.put("provider", route.getProvider());
        if (route.getBaseUrl() != null) m.put("base-url", route.getBaseUrl());
        return m;
    }
}
