package com.nousresearch.hermes.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.sandbox.*;
import com.nousresearch.hermes.tenant.metrics.TenantMetrics;
import io.javalin.http.Context;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 资源沙箱管理 REST API
 *
 * 提供进程执行、网络请求等受限资源的 API 接口
 */
public class ResourceSandboxController {

    private final TenantManager tenantManager;
    private final ObjectMapper objectMapper;

    public ResourceSandboxController(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 执行系统命令
     *
     * POST /api/tenants/{tenantId}/exec
     * Body: { "command": ["git", "clone", "url"], "timeout": 60 }
     */
    public void executeCommand(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext context = tenantManager.getTenant(tenantId);

        if (context == null) {
            ctx.status(404);
            ctx.json(error("Tenant not found: " + tenantId));
            return;
        }

        try {
            Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);

            // 解析命令
            List<String> command = ((List<?>) body.get("command")).stream()
                .map(Object::toString)
                .collect(Collectors.toList());

            // 解析选项
            ProcessOptions.Builder optionsBuilder = ProcessOptions.builder();
            if (body.containsKey("timeout")) {
                optionsBuilder.timeoutSeconds((Integer) body.get("timeout"));
            }
            if (body.containsKey("maxMemoryMB")) {
                optionsBuilder.maxMemoryMB((Integer) body.get("maxMemoryMB"));
            }

            ProcessOptions options = optionsBuilder.build();

            // 执行命令
            ProcessResult result = context.exec(command, options);

            // 构建响应
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.isSuccess());
            response.put("exitCode", result.getExitCode());
            response.put("stdout", result.getStdout());
            response.put("stderr", result.getStderr());
            response.put("timedOut", result.isTimedOut());

            if (!result.isSuccess()) {
                ctx.status(400);
            }

            ctx.json(response);

        } catch (ProcessSandboxException e) {
            ctx.status(403);
            ctx.json(error("Command not allowed: " + e.getMessage()));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(error("Execution failed: " + e.getMessage()));
        }
    }

    /**
     * 发送 HTTP GET 请求
     *
     * GET /api/tenants/{tenantId}/http?url=https://api.example.com
     */
    public void httpGet(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String url = ctx.queryParam("url");

        if (url == null || url.isEmpty()) {
            ctx.status(400);
            ctx.json(error("Missing required parameter: url"));
            return;
        }

        TenantContext context = tenantManager.getTenant(tenantId);
        if (context == null) {
            ctx.status(404);
            ctx.json(error("Tenant not found: " + tenantId));
            return;
        }

        try {
            var response = context.httpGet(url);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statusCode", response.statusCode());
            result.put("headers", response.headers().map());
            result.put("body", response.body());

            ctx.json(result);

        } catch (NetworkSandboxException e) {
            ctx.status(403);
            ctx.json(error("Access denied: " + e.getMessage()));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(error("Request failed: " + e.getMessage()));
        }
    }

    /**
     * 获取租户资源使用指标
     *
     * GET /api/tenants/{tenantId}/metrics
     */
    public void getMetrics(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext context = tenantManager.getTenant(tenantId);

        if (context == null) {
            ctx.status(404);
            ctx.json(error("Tenant not found: " + tenantId));
            return;
        }

        try {
            // 获取指标
            TenantMetrics metrics = new TenantMetrics(tenantId);

            // 收集实时数据
            TenantFileSandbox.StorageUsage storageUsage = context.getFileSandbox().getStorageUsage();
            metrics.setCurrentStorageUsage(storageUsage.usedBytes());

            Map<String, Object> result = metrics.getAllMetrics();

            ctx.json(result);

        } catch (Exception e) {
            ctx.status(500);
            ctx.json(error("Failed to get metrics: " + e.getMessage()));
        }
    }

    /**
     * 获取网络访问日志
     *
     * GET /api/tenants/{tenantId}/network/logs?limit=100
     */
    public void getNetworkLogs(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String limitParam = ctx.queryParam("limit");
        int limit = limitParam != null ? Integer.parseInt(limitParam) : 100;

        TenantContext context = tenantManager.getTenant(tenantId);
        if (context == null) {
            ctx.status(404);
            ctx.json(error("Tenant not found: " + tenantId));
            return;
        }

        try {
            // 从审计日志获取网络相关事件
            var events = context.getAuditLogger().getRecentEvents(limit).stream()
                .filter(e -> e.event().name().contains("NETWORK"))
                .map(e -> Map.of(
                    "timestamp", e.timestamp().toString(),
                    "event", e.event().name(),
                    "details", e.details()
                ))
                .collect(Collectors.toList());

            ctx.json(Map.of(
                "tenantId", tenantId,
                "logs", events
            ));

        } catch (Exception e) {
            ctx.status(500);
            ctx.json(error("Failed to get logs: " + e.getMessage()));
        }
    }

    /**
     * 测试网络连接（不实际发送请求）
     *
     * POST /api/tenants/{tenantId}/network/test
     * Body: { "url": "https://api.example.com" }
     */
    public void testNetworkAccess(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext context = tenantManager.getTenant(tenantId);

        if (context == null) {
            ctx.status(404);
            ctx.json(error("Tenant not found: " + tenantId));
            return;
        }

        try {
            Map<String, Object> body = objectMapper.readValue(ctx.body(), Map.class);
            String url = (String) body.get("url");

            boolean allowed = context.isNetworkAllowed(url);

            ctx.json(Map.of(
                "url", url,
                "allowed", allowed
            ));

        } catch (Exception e) {
            ctx.status(500);
            ctx.json(error("Test failed: " + e.getMessage()));
        }
    }

    // ============ 辅助方法 ============

    private Map<String, String> error(String message) {
        return Map.of("error", message);
    }
}
