package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.config.repository.ConfigCache;
import com.nousresearch.hermes.config.repository.ConfigRepository;
import com.nousresearch.hermes.platform.ProviderCatalog;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * C4: Admin API handler for tenant model configuration management.
 *
 * <p>Provides HTTP endpoints for managing tenant model config, API keys,
 * model routes, quota, and platform-level settings. Replaces SSH + file
 * editing for cloud deployments.</p>
 *
 * <p>All writes go through {@link ConfigCache} which invalidates the
 * cache entry, so the next read picks up the new config (hot reload).</p>
 */
public class AdminConfigHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdminConfigHandler.class);

    private final ConfigRepository repository;
    private final ConfigCache cache;

    public AdminConfigHandler(ConfigRepository repository, ConfigCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    // ============ Tenant Model Config ============

    public void getModelConfig(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        ctx.json(repository.loadModelConfig(tenantId));
    }

    public void updateModelConfig(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        Map<String, Object> config = body.toJavaObject(Map.class);
        repository.saveModelConfig(tenantId, config);
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "tenantId", tenantId));
    }

    public void patchModelConfig(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        // Load existing, merge, save
        Map<String, Object> existing = repository.loadModelConfig(tenantId);
        for (String key : body.keySet()) {
            existing.put(key, body.get(key));
        }
        repository.saveModelConfig(tenantId, existing);
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "tenantId", tenantId, "merged", true));
    }

    // ============ API Keys ============

    public void listApiKeys(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        Map<String, String> keys = repository.loadApiKeys(tenantId);
        JSONArray arr = new JSONArray();
        for (var entry : keys.entrySet()) {
            JSONObject obj = new JSONObject();
            obj.put("provider", entry.getKey());
            obj.put("hasKey", entry.getValue() != null && !entry.getValue().isBlank());
            obj.put("keyPrefix", maskKey(entry.getValue()));
            arr.add(obj);
        }
        ctx.json(arr);
    }

    public void setApiKey(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String provider = ctx.pathParam("provider");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String apiKey = body.getString("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            ctx.status(400).json(Map.of("error", "apiKey is required"));
            return;
        }
        repository.saveApiKey(tenantId, provider, apiKey);
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "provider", provider));
    }

    public void deleteApiKey(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String provider = ctx.pathParam("provider");
        repository.removeApiKey(tenantId, provider);
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "provider", provider, "deleted", true));
    }

    // ============ Model Routes ============

    public void listModelRoutes(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        List<ModelRoute> routes = repository.loadModelRoutes(tenantId);
        JSONArray arr = new JSONArray();
        for (ModelRoute r : routes) {
            JSONObject obj = new JSONObject();
            obj.put("alias", r.getAlias());
            obj.put("model", r.getModel());
            obj.put("provider", r.getProvider());
            obj.put("baseUrl", r.getBaseUrl());
            arr.add(obj);
        }
        ctx.json(arr);
    }

    public void setModelRoute(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String alias = ctx.pathParam("alias");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String model = body.getString("model");
        String provider = body.getString("provider");
        String baseUrl = body.getString("baseUrl");
        if (model == null || model.isBlank()) {
            ctx.status(400).json(Map.of("error", "model is required"));
            return;
        }
        repository.saveModelRoute(tenantId, new ModelRoute(alias, model, provider, baseUrl));
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "alias", alias));
    }

    public void deleteModelRoute(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String alias = ctx.pathParam("alias");
        repository.removeModelRoute(tenantId, alias);
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "alias", alias, "deleted", true));
    }

    // ============ Quota ============

    public void getQuota(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantQuota quota = repository.loadQuota(tenantId);
        ctx.json(quota.toMap());
    }

    public void updateQuota(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        TenantQuota quota = new TenantQuota();
        quota.setMaxDailyRequests(body.getIntValue("maxDailyRequests", 10000));
        quota.setMaxDailyTokens(body.getLongValue("maxDailyTokens", 10_000_000L));
        quota.setMaxConcurrentAgents(body.getIntValue("maxConcurrentAgents", 5));
        quota.setMaxConcurrentSessions(body.getIntValue("maxConcurrentSessions", 10));
        quota.setMaxStorageBytes(body.getLongValue("maxStorageBytes", 1_073_741_824L));
        quota.setMaxMemoryBytes(body.getLongValue("maxMemoryBytes", 536_870_912L));
        quota.setRequestsPerSecond(body.getIntValue("requestsPerSecond", 10));
        quota.setRequestsPerMinute(body.getIntValue("requestsPerMinute", 100));
        quota.setMaxToolCallsPerSession(body.getIntValue("maxToolCallsPerSession", 100));
        quota.setMaxFileSizeBytes(body.getLongValue("maxFileSizeBytes", 104_857_600L));
        quota.setAllowCodeExecution(body.getBooleanValue("allowCodeExecution", true));
        quota.setMaxPrivateSkills(body.getIntValue("maxPrivateSkills", 50));
        quota.setMaxInstalledSkills(body.getIntValue("maxInstalledSkills", 100));
        repository.saveQuota(tenantId, quota);
        cache.invalidate(tenantId);
        ctx.status(200).json(Map.of("status", "ok", "tenantId", tenantId));
    }

    // ============ Platform ============

    public void listProviders(Context ctx) {
        ProviderCatalog catalog = repository.loadProviderCatalog();
        JSONArray arr = new JSONArray();
        for (var p : catalog.listProviders()) {
            JSONObject obj = new JSONObject();
            obj.put("id", p.id());
            obj.put("displayName", p.displayName());
            obj.put("defaultBaseUrl", p.defaultBaseUrl());
            obj.put("allowTenantKeys", p.allowTenantKeys());
            obj.put("allowPlatformKeys", p.allowPlatformKeys());
            obj.put("supportedModels", p.supportedModels());
            arr.add(obj);
        }
        ctx.json(arr);
    }

    public void listPlatformRoutes(Context ctx) {
        List<ModelRoute> routes = repository.loadPlatformModelRoutes();
        JSONArray arr = new JSONArray();
        for (ModelRoute r : routes) {
            JSONObject obj = new JSONObject();
            obj.put("alias", r.getAlias());
            obj.put("model", r.getModel());
            obj.put("provider", r.getProvider());
            arr.add(obj);
        }
        ctx.json(arr);
    }

    // ============ Billing Query ============

    public void getBillingSummary(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        // Delegate to billing repository if available
        ctx.json(Map.of("tenantId", tenantId, "message", "Use /api/admin/tenants/{id}/billing?from=&to= for detailed query"));
    }

    // ============ Cache Management ============

    public void invalidateCache(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        if (tenantId != null) {
            cache.invalidate(tenantId);
        } else {
            cache.invalidateAll();
        }
        ctx.status(200).json(Map.of("status", "ok", "tenantId", tenantId != null ? tenantId : "all"));
    }

    public void getCacheStats(Context ctx) {
        var stats = cache.getStats();
        ctx.json(stats);
    }

    // ============ Helper ============

    private static String maskKey(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
