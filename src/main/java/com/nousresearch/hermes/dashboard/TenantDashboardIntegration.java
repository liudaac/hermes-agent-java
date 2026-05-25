package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical tenant management routes for the Dashboard API.
 *
 * DashboardServer delegates all /api/tenants routes here so the Java dashboard has
 * one tenant API contract instead of two drifting implementations.  The canonical
 * external field name is tenantId.  Tenant creation still accepts legacy id as an
 * input alias to avoid breaking older callers while responses consistently expose
 * tenantId.
 */
public final class TenantDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(TenantDashboardIntegration.class);

    private TenantDashboardIntegration() {
    }

    /**
     * Register all tenant management routes.
     */
    public static void registerRoutes(Javalin app, TenantManager tenantManager) {
        if (tenantManager == null) {
            logger.warn("TenantManager is null, tenant routes not registered");
            return;
        }

        logger.info("Registering tenant management routes");

        app.get("/api/tenants", ctx -> listTenants(ctx, tenantManager));
        app.post("/api/tenants", ctx -> createTenant(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}", ctx -> getTenant(ctx, tenantManager));
        app.delete("/api/tenants/{tenantId}", ctx -> deleteTenant(ctx, tenantManager));
        app.post("/api/tenants/{tenantId}/suspend", ctx -> suspendTenant(ctx, tenantManager));
        app.post("/api/tenants/{tenantId}/resume", ctx -> resumeTenant(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/quota", ctx -> getQuota(ctx, tenantManager));
        app.put("/api/tenants/{tenantId}/quota", ctx -> updateQuota(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/usage", ctx -> getUsage(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/security", ctx -> getSecurity(ctx, tenantManager));
        app.put("/api/tenants/{tenantId}/security", ctx -> updateSecurity(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/audit", ctx -> getAuditLogs(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/sessions", ctx -> getSessions(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/skills", ctx -> getSkills(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/metrics", ctx -> getMetrics(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/config", ctx -> getTenantConfig(ctx, tenantManager));
        app.put("/api/tenants/{tenantId}/config", ctx -> updateTenantConfig(ctx, tenantManager));

        logger.info("Tenant management routes registered successfully");
    }

    static void listTenants(Context ctx, TenantManager tenantManager) {
        List<Map<String, Object>> tenants = tenantManager.getAllTenants().entrySet().stream()
            .map(entry -> tenantSummary(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

        ctx.json(Map.of("tenants", tenants, "total", tenants.size()));
    }

    static void createTenant(Context ctx, TenantManager tenantManager) {
        try {
            JSONObject body = parseBody(ctx);
            String tenantId = body.getString("tenantId");
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = body.getString("id"); // legacy input alias
            }
            String createdBy = body.getString("createdBy");

            if (tenantId == null || tenantId.isBlank()) {
                ctx.status(400).json(Map.of("error", "tenantId is required"));
                return;
            }

            if (tenantManager.exists(tenantId)) {
                ctx.status(409).json(Map.of("error", "Tenant already exists: " + tenantId));
                return;
            }

            TenantProvisioningRequest request = TenantProvisioningRequest.builder(
                    tenantId,
                    createdBy != null ? createdBy : "admin")
                .build();
            TenantContext tenant = tenantManager.createTenant(request);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("success", true);
            response.put("tenantId", tenant.getTenantId());
            response.put("state", tenant.getState().name());
            response.put("message", "Tenant created successfully");
            ctx.json(response);

            logger.info("Tenant created: {}", tenantId);
        } catch (Exception e) {
            logger.error("Failed to create tenant", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    static void getTenant(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        Map<String, Object> info = tenantSummary(ctx.pathParam("tenantId"), tenant);
        info.put("quota", tenant.getQuotaManager().getQuota().toMap());
        ctx.json(info);
    }

    static void deleteTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            notFound(ctx, tenantId);
            return;
        }

        try {
            tenantManager.destroyTenant(tenantId, false);
            ctx.json(Map.of("ok", true, "success", true, "tenantId", tenantId, "message", "Tenant deleted"));
            logger.info("Tenant deleted: {}", tenantId);
        } catch (Exception e) {
            logger.error("Failed to delete tenant: {}", tenantId, e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    static void suspendTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            notFound(ctx, tenantId);
            return;
        }

        tenantManager.suspendTenant(tenantId, "Suspended via dashboard");
        ctx.json(Map.of("ok", true, "success", true, "tenantId", tenantId, "state", "SUSPENDED"));
    }

    static void resumeTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            notFound(ctx, tenantId);
            return;
        }

        tenantManager.resumeTenant(tenantId);
        ctx.json(Map.of("ok", true, "success", true, "tenantId", tenantId, "state", "ACTIVE"));
    }

    static void getQuota(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        ctx.json(tenant.getQuotaManager().getQuota().toMap());
    }

    static void updateQuota(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        JSONObject body = parseBody(ctx);
        TenantQuota quota = tenant.getQuotaManager().getQuota();

        if (body.containsKey("maxDailyRequests")) {
            quota.setMaxDailyRequests(body.getIntValue("maxDailyRequests"));
        }
        if (body.containsKey("maxDailyTokens")) {
            quota.setMaxDailyTokens(body.getLongValue("maxDailyTokens"));
        }
        if (body.containsKey("maxConcurrentAgents")) {
            quota.setMaxConcurrentAgents(body.getIntValue("maxConcurrentAgents"));
        }
        if (body.containsKey("maxConcurrentSessions")) {
            quota.setMaxConcurrentSessions(body.getIntValue("maxConcurrentSessions"));
        }
        if (body.containsKey("maxStorageBytes")) {
            quota.setMaxStorageBytes(body.getLongValue("maxStorageBytes"));
        }
        if (body.containsKey("maxMemoryBytes")) {
            quota.setMaxMemoryBytes(body.getLongValue("maxMemoryBytes"));
        }
        if (body.containsKey("requestsPerSecond")) {
            quota.setRequestsPerSecond(body.getIntValue("requestsPerSecond"));
        }
        if (body.containsKey("requestsPerMinute")) {
            quota.setRequestsPerMinute(body.getIntValue("requestsPerMinute"));
        }
        if (body.containsKey("maxToolCallsPerSession")) {
            quota.setMaxToolCallsPerSession(body.getIntValue("maxToolCallsPerSession"));
        }
        if (body.containsKey("maxFileSizeBytes")) {
            quota.setMaxFileSizeBytes(body.getLongValue("maxFileSizeBytes"));
        }
        if (body.containsKey("maxExecutionTimeSeconds")) {
            quota.setMaxExecutionTime(java.time.Duration.ofSeconds(body.getLongValue("maxExecutionTimeSeconds")));
        }
        if (body.containsKey("allowCodeExecution")) {
            quota.setAllowCodeExecution(body.getBooleanValue("allowCodeExecution"));
        }
        if (body.containsKey("maxPrivateSkills")) {
            quota.setMaxPrivateSkills(body.getIntValue("maxPrivateSkills"));
        }
        if (body.containsKey("maxInstalledSkills")) {
            quota.setMaxInstalledSkills(body.getIntValue("maxInstalledSkills"));
        }

        tenant.getQuotaManager().updateQuota(quota);
        tenant.getConfig().set("quota", quota.toMap());
        tenant.getConfig().save();
        ctx.json(Map.of("ok", true, "success", true, "tenantId", tenant.getTenantId(), "quota", quota.toMap()));
    }

    static void getUsage(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        var usage = tenant.getQuotaManager().getUsage();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.getTenantId());
        result.put("dailyRequests", usage.dailyRequests());
        result.put("maxDailyRequests", usage.maxDailyRequests());
        result.put("dailyTokens", usage.dailyTokens());
        result.put("maxDailyTokens", usage.maxDailyTokens());
        result.put("activeAgents", usage.activeAgents());
        result.put("storageUsage", usage.storageUsage());
        result.put("storage", tenant.getQuotaManager().getStorageUsage());
        result.put("memory", tenant.getQuotaManager().getMemoryUsage());
        ctx.json(result);
    }

    static void getSecurity(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        TenantSecurityPolicy policy = tenant.getSecurityPolicy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.getTenantId());
        result.put("allowCodeExecution", policy.isAllowCodeExecution());
        result.put("requireSandbox", policy.isRequireSandbox());
        result.put("allowNetworkAccess", policy.isAllowNetworkAccess());
        result.put("allowFileRead", policy.isAllowFileRead());
        result.put("allowFileWrite", policy.isAllowFileWrite());
        result.put("allowedLanguages", policy.getAllowedLanguages());
        result.put("allowedHosts", policy.getAllowedHosts());
        result.put("allowedTools", policy.getAllowedTools());
        result.put("deniedTools", policy.getDeniedTools());
        result.put("deniedPaths", policy.getDeniedPaths());
        ctx.json(result);
    }

    static void updateSecurity(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        JSONObject body = parseBody(ctx);
        TenantSecurityPolicy policy = tenant.getSecurityPolicy();

        if (body.containsKey("allowCodeExecution")) {
            policy.setAllowCodeExecution(body.getBoolean("allowCodeExecution"));
        }
        if (body.containsKey("requireSandbox")) {
            policy.setRequireSandbox(body.getBoolean("requireSandbox"));
        }
        if (body.containsKey("allowNetworkAccess")) {
            policy.setAllowNetworkAccess(body.getBoolean("allowNetworkAccess"));
        }
        if (body.containsKey("allowFileRead")) {
            policy.setAllowFileRead(body.getBoolean("allowFileRead"));
        }
        if (body.containsKey("allowFileWrite")) {
            policy.setAllowFileWrite(body.getBoolean("allowFileWrite"));
        }
        if (body.containsKey("allowedLanguages")) {
            policy.setAllowedLanguages(jsonArrayToSet(body.getJSONArray("allowedLanguages")));
        }
        if (body.containsKey("allowedHosts")) {
            policy.setAllowedHosts(jsonArrayToSet(body.getJSONArray("allowedHosts")));
        }
        if (body.containsKey("allowedTools")) {
            policy.setAllowedTools(jsonArrayToSet(body.getJSONArray("allowedTools")));
        }
        if (body.containsKey("deniedTools")) {
            policy.setDeniedTools(jsonArrayToSet(body.getJSONArray("deniedTools")));
        }
        if (body.containsKey("deniedPaths")) {
            policy.setDeniedPaths(jsonArrayToSet(body.getJSONArray("deniedPaths")));
        }

        tenant.setSecurityPolicy(policy);
        try {
            policy.save(tenant.getTenantDir().resolve("config"));
        } catch (Exception e) {
            logger.warn("Failed to save tenant security policy for {}: {}", tenant.getTenantId(), e.getMessage());
        }

        ctx.json(Map.of("ok", true, "success", true, "tenantId", tenant.getTenantId()));
    }

    static void getAuditLogs(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        var events = tenant.getAuditLogger().getRecentEvents(limit).stream()
            .map(e -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("timestamp", e.timestamp().toString());
                event.put("event", e.event().name());
                event.put("type", e.event().name()); // compatibility alias
                event.put("details", e.details());
                return event;
            })
            .collect(Collectors.toList());

        ctx.json(Map.of("tenantId", tenant.getTenantId(), "logs", events, "events", events, "total", events.size()));
    }

    static void getSessions(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        ctx.json(Map.of("tenantId", tenant.getTenantId(), "activeSessions", tenant.getActiveSessionCount()));
    }

    static void getSkills(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        List<Map<String, Object>> skills = tenant.getSkillManager().listSkills().stream()
            .map(summary -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", summary.name());
                map.put("description", summary.description());
                map.put("source", summary.source());
                map.put("version", summary.version());
                map.put("readOnly", summary.readOnly());
                map.put("scope", "tenant");
                map.put("tenantId", tenant.getTenantId());
                return map;
            })
            .collect(Collectors.toList());

        List<String> skillNames = skills.stream()
            .map(skill -> (String) skill.get("name"))
            .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", tenant.getTenantId());
        response.put("scope", "tenant");
        response.put("skills", skills);
        response.put("installedSkills", skillNames); // compatibility alias for the previous dashboard response
        response.put("total", skills.size());
        response.put("totalSkills", skills.size()); // compatibility alias
        ctx.json(response);
    }

    static void getMetrics(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("tenantId", tenant.getTenantId());
        metrics.put("activeSessions", tenant.getActiveSessionCount());
        metrics.put("activeAgents", tenant.getActiveAgentCount());
        ctx.json(metrics);
    }

    static void getTenantConfig(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        ctx.json(tenant.getConfig().getAll());
    }

    static void updateTenantConfig(Context ctx, TenantManager tenantManager) {
        TenantContext tenant = requireTenant(ctx, tenantManager);
        if (tenant == null) {
            return;
        }

        try {
            JSONObject body = parseBody(ctx);
            body.forEach((key, value) -> tenant.getConfig().set(key, value));
            tenant.getConfig().save();
            ctx.json(Map.of("ok", true, "success", true, "tenantId", tenant.getTenantId()));
        } catch (Exception e) {
            logger.error("Failed to update tenant config for {}", tenant.getTenantId(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }


    private static Set<String> jsonArrayToSet(com.alibaba.fastjson2.JSONArray array) {
        Set<String> result = new HashSet<>();
        if (array == null) {
            return result;
        }
        for (Object value : array) {
            if (value != null && !value.toString().isBlank()) {
                result.add(value.toString().trim());
            }
        }
        return result;
    }

    private static TenantContext requireTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            notFound(ctx, tenantId);
        }
        return tenant;
    }

    private static void notFound(Context ctx, String tenantId) {
        ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId, "tenantId", tenantId));
    }

    private static JSONObject parseBody(Context ctx) {
        String raw = ctx.body();
        if (raw == null || raw.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(raw);
    }

    private static Map<String, Object> tenantSummary(String fallbackTenantId, TenantContext tenant) {
        String tenantId = tenant.getTenantId() != null ? tenant.getTenantId() : fallbackTenantId;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("tenantId", tenantId);
        info.put("state", tenant.getState().name());
        info.put("createdAt", tenant.getCreatedAt().toString());
        info.put("lastActivity", tenant.getLastActivity().toString());
        info.put("activeAgents", tenant.getActiveAgentCount());
        info.put("activeSessions", tenant.getActiveSessionCount());
        return info;
    }
}
