package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 租户管理功能集成到 Dashboard
 *
 * 使用方式：
 * 1. 在 DashboardServer 中添加 TenantManager 字段
 * 2. 调用 TenantDashboardIntegration.registerRoutes(app, tenantManager)
 */
public class TenantDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(TenantDashboardIntegration.class);

    /**
     * 注册所有租户管理路由
     */
    public static void registerRoutes(Javalin app, TenantManager tenantManager) {
        if (tenantManager == null) {
            logger.warn("TenantManager is null, tenant routes not registered");
            return;
        }

        logger.info("Registering tenant management routes");

        // 租户列表
        app.get("/api/tenants", ctx -> listTenants(ctx, tenantManager));

        // 创建租户
        app.post("/api/tenants", ctx -> createTenant(ctx, tenantManager));

        // 租户详情
        app.get("/api/tenants/{tenantId}", ctx -> getTenant(ctx, tenantManager));

        // 删除租户
        app.delete("/api/tenants/{tenantId}", ctx -> deleteTenant(ctx, tenantManager));

        // 暂停/恢复
        app.post("/api/tenants/{tenantId}/suspend", ctx -> suspendTenant(ctx, tenantManager));
        app.post("/api/tenants/{tenantId}/resume", ctx -> resumeTenant(ctx, tenantManager));

        // 配额管理
        app.get("/api/tenants/{tenantId}/quota", ctx -> getQuota(ctx, tenantManager));
        app.put("/api/tenants/{tenantId}/quota", ctx -> updateQuota(ctx, tenantManager));
        app.get("/api/tenants/{tenantId}/usage", ctx -> getUsage(ctx, tenantManager));

        // 安全策略
        app.get("/api/tenants/{tenantId}/security", ctx -> getSecurity(ctx, tenantManager));

        // 审计日志
        app.get("/api/tenants/{tenantId}/audit", ctx -> getAuditLogs(ctx, tenantManager));

        // 资源指标
        app.get("/api/tenants/{tenantId}/metrics", ctx -> getMetrics(ctx, tenantManager));

        // 租户配置
        app.get("/api/tenants/{tenantId}/config", ctx -> getTenantConfig(ctx, tenantManager));
        app.put("/api/tenants/{tenantId}/config", ctx -> updateTenantConfig(ctx, tenantManager));

        logger.info("Tenant management routes registered successfully");
    }

    // ============ 路由处理 ============

    private static void listTenants(Context ctx, TenantManager tenantManager) {
        Map<String, TenantContext> tenants = tenantManager.getAllTenants();

        List<Map<String, Object>> list = tenants.entrySet().stream()
            .map(e -> {
                TenantContext t = e.getValue();
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("tenantId", t.getTenantId());
                info.put("state", t.getState().name());
                info.put("createdAt", t.getCreatedAt().toString());
                info.put("lastActivity", t.getLastActivity().toString());
                info.put("activeAgents", t.getActiveAgentCount());
                info.put("activeSessions", t.getActiveSessionCount());
                return info;
            })
            .collect(Collectors.toList());

        ctx.json(Map.of("tenants", list, "total", list.size()));
    }

    private static void createTenant(Context ctx, TenantManager tenantManager) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String tenantId = body.getString("tenantId");
            String createdBy = body.getString("createdBy");

            if (tenantId == null || tenantId.isEmpty()) {
                ctx.status(400).json(Map.of("error", "tenantId is required"));
                return;
            }

            if (tenantManager.exists(tenantId)) {
                ctx.status(409).json(Map.of("error", "Tenant already exists: " + tenantId));
                return;
            }

            TenantProvisioningRequest request = TenantProvisioningRequest.builder(
                    tenantId, createdBy != null ? createdBy : "admin")
                .build();

            TenantContext tenant = tenantManager.createTenant(request);

            ctx.json(Map.of(
                "success", true,
                "tenantId", tenantId,
                "message", "Tenant created successfully"
            ));

            logger.info("Tenant created: {}", tenantId);

        } catch (Exception e) {
            logger.error("Failed to create tenant", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private static void getTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("tenantId", tenant.getTenantId());
        info.put("state", tenant.getState().name());
        info.put("createdAt", tenant.getCreatedAt().toString());
        info.put("lastActivity", tenant.getLastActivity().toString());
        info.put("activeAgents", tenant.getActiveAgentCount());
        info.put("activeSessions", tenant.getActiveSessionCount());

        ctx.json(info);
    }

    private static void deleteTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        try {
            tenantManager.destroyTenant(tenantId, false);
            ctx.json(Map.of("success", true, "message", "Tenant deleted"));
            logger.info("Tenant deleted: {}", tenantId);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private static void suspendTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        tenantManager.suspendTenant(tenantId, "Suspended via dashboard");
        ctx.json(Map.of("success", true, "state", "SUSPENDED"));
    }

    private static void resumeTenant(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        tenantManager.resumeTenant(tenantId);
        ctx.json(Map.of("success", true, "state", "ACTIVE"));
    }

    private static void getQuota(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        ctx.json(tenant.getQuotaManager().getQuota().toMap());
    }

    private static void updateQuota(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        // 实现配额更新逻辑
        ctx.json(Map.of("success", true));
    }

    private static void getUsage(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        var usage = tenant.getQuotaManager().getUsage();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailyRequests", usage.dailyRequests());
        result.put("maxDailyRequests", usage.maxDailyRequests());
        result.put("dailyTokens", usage.dailyTokens());
        result.put("maxDailyTokens", usage.maxDailyTokens());
        result.put("activeAgents", usage.activeAgents());
        result.put("storageUsage", usage.storageUsage());

        ctx.json(result);
    }

    private static void getSecurity(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        var policy = tenant.getSecurityPolicy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowCodeExecution", policy.isAllowCodeExecution());
        result.put("allowNetworkAccess", policy.isAllowNetworkAccess());
        result.put("allowFileRead", policy.isAllowFileRead());
        result.put("allowFileWrite", policy.isAllowFileWrite());

        ctx.json(result);
    }

    private static void getAuditLogs(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);

        var events = tenant.getAuditLogger().getRecentEvents(limit).stream()
            .map(e -> Map.of(
                "timestamp", e.timestamp().toString(),
                "event", e.event().name(),
                "details", e.details()
            ))
            .collect(Collectors.toList());

        ctx.json(Map.of("logs", events, "total", events.size()));
    }

    private static void getMetrics(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("activeSessions", tenant.getActiveSessionCount());
        metrics.put("activeAgents", tenant.getActiveAgentCount());

        ctx.json(metrics);
    }

    private static void getTenantConfig(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        ctx.json(tenant.getConfig().getAll());
    }

    private static void updateTenantConfig(Context ctx, TenantManager tenantManager) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        try {
            JSONObject body = JSON.parseObject(ctx.body());
            body.forEach((key, value) -> tenant.getConfig().set(key, value));
            tenant.getConfig().save();
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
