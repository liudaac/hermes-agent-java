package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.tenant.core.TenantAIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.quota.QuotaExceededException;
import com.nousresearch.hermes.tools.ToolRegistry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Gateway HTTP Server V2 - 全租户模式
 *
 * 与 V1 的主要区别：
 * - 所有消息处理都通过 TenantAwareAIAgent
 * - 自动识别和创建租户
 * - 真实的租户配额和状态检查
 * - 租户级别的会话隔离
 */
public class GatewayServerV2 {
    private static final Logger logger = LoggerFactory.getLogger(GatewayServerV2.class);

    private final int port;
    private final HermesConfig config;
    private final TenantManager tenantManager;
    private final boolean ownsTenantManager;
    private final Map<String, com.nousresearch.hermes.gateway.PlatformAdapter> adapters;
    private final ExecutorService executor;
    private final ScheduledExecutorService sessionCleanupExecutor;
    private Javalin app;
    private volatile boolean running = false;
    private volatile String sessionToken;

    // 用户到租户的映射缓存
    private final ConcurrentHashMap<String, String> userTenantCache = new ConcurrentHashMap<>();

    public GatewayServerV2(int port, HermesConfig config) {
        this(port, config, new TenantManager(), true);
    }

    public GatewayServerV2(int port, HermesConfig config, TenantManager tenantManager) {
        this(port, config, tenantManager, false);
    }

    private GatewayServerV2(int port, HermesConfig config, TenantManager tenantManager, boolean ownsTenantManager) {
        this.port = port;
        this.config = config;
        this.tenantManager = tenantManager;
        this.ownsTenantManager = ownsTenantManager;
        this.adapters = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "gateway-worker");
            t.setDaemon(true);
            return t;
        });
        this.sessionCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动网关服务器
     */
    public void start() {
        if (running) {
            logger.warn("Gateway already running");
            return;
        }

        // 初始化默认租户
        tenantManager.initializeDefaultTenant();

        app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.jetty.modifyServletContextHandler(handler ->
                handler.setDefaultResponseCharacterEncoding("UTF-8")
            );
        });

        setupRoutes();
        setupMiddleware();

        app.start(port);
        running = true;

        // 启动会话清理任务
        startSessionCleanupTask();

        logger.info("Gateway V2 server started on port {} with tenant mode", port);
    }

    /**
     * 停止网关服务器
     */
    public void stop() {
        running = false;

        if (app != null) {
            app.stop();
        }

        executor.shutdown();
        sessionCleanupExecutor.shutdown();

        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!sessionCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                sessionCleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            sessionCleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close the tenant manager only when this server created it. GatewayRunner
        // can inject a shared manager used by both GatewayServerV2 and DashboardServer.
        if (ownsTenantManager) {
            tenantManager.shutdown();
        }

        logger.info("Gateway V2 server stopped");
    }

    /**
     * Share the DashboardServer session token so that /api/chat endpoints
     * can be protected with the same Bearer token the dashboard UI already has.
     */
    public void setSessionToken(String token) {
        this.sessionToken = token;
        logger.info("Gateway chat endpoints now protected with shared session token");
    }

    // ==================== Routes Setup ====================

    private void setupRoutes() {
        // Health check
        app.get("/health", this::handleHealth);

        // Webhook endpoints
        app.post("/webhook/{platform}", this::handleWebhook);

        // API endpoints
        app.post("/api/message", this::handleMessage);
        app.get("/api/status", this::handleStatus);
        app.get("/api/tools", this::handleTools);

        // Chat API
        app.post("/api/chat", this::handleChat);
        app.post("/api/chat/stream", this::handleChatStream);

        // Tenant API - 真实的实现
        app.get("/api/tenants", this::handleGetTenants);
        app.post("/api/tenants", this::handleCreateTenant);
        app.get("/api/tenants/{id}", this::handleGetTenant);
        app.delete("/api/tenants/{id}", this::handleDeleteTenant);
        app.post("/api/tenants/{id}/suspend", this::handleSuspendTenant);
        app.post("/api/tenants/{id}/resume", this::handleResumeTenant);
        app.get("/api/tenants/{id}/quota", this::handleGetTenantQuota);
        app.put("/api/tenants/{id}/quota", this::handleUpdateTenantQuota);
        app.get("/api/tenants/{id}/usage", this::handleGetTenantUsage);
        app.get("/api/tenants/{id}/audit", this::handleGetTenantAudit);

        // Sessions API
        app.get("/api/tenants/{tenantId}/sessions", this::handleGetTenantSessions);
        app.get("/api/tenants/{tenantId}/sessions/{sessionId}/messages", this::handleGetSessionMessages);
        app.delete("/api/tenants/{tenantId}/sessions/{sessionId}", this::handleDeleteSession);

        // Config API
        app.get("/api/config", this::handleGetConfig);

        // Skills API
        app.get("/api/skills", this::handleGetSkills);
    }

    private void setupMiddleware() {
        // CORS — allow dashboard dev server origins
        app.before(ctx -> {
            String origin = ctx.header("Origin");
            if (origin != null && (origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:"))) {
                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Access-Control-Allow-Credentials", "true");
            } else {
                ctx.header("Access-Control-Allow-Origin", "*");
            }
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Tenant-ID");
        });
        app.options("/*", ctx -> ctx.status(200));

        // Auth middleware for chat endpoints (share DashboardServer session token)
        app.before("/api/chat", this::checkChatAuth);
        app.before("/api/chat/stream", this::checkChatAuth);

        // 租户上下文中间件
        app.before("/api/*", this::extractTenantContext);
    }

    private void checkChatAuth(Context ctx) {
        String token = sessionToken;
        if (token == null || token.isBlank()) {
            return; // no token configured — dev mode, allow all
        }
        String auth = ctx.header("Authorization");
        String expected = "Bearer " + token;
        if (auth == null || !java.security.MessageDigest.isEqual(auth.getBytes(), expected.getBytes())) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            ctx.skipRemainingHandlers();
        }
    }

    // ==================== Handler Methods ====================

    private void handleHealth(Context ctx) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "healthy");
        health.put("version", "2.0.0");
        health.put("mode", "tenant");
        health.put("timestamp", System.currentTimeMillis());

        var stats = tenantManager.getStats();
        health.put("tenants", Map.of(
            "active", stats.activeTenants(),
            "total", stats.totalRegistered()
        ));

        ctx.json(health);
    }

    private void handleWebhook(Context ctx) {
        String platform = ctx.pathParam("platform");
        PlatformAdapter adapter = adapters.get(platform);

        if (adapter == null) {
            ctx.status(404).json(Map.of("error", "Unknown platform: " + platform));
            return;
        }

        try {
            String body = ctx.body();
            JSONObject payload = JSON.parseObject(body);

            JSONObject challengeResponse = adapter.getWebhookChallengeResponse(payload);
            if (challengeResponse != null) {
                ctx.status(200).json(challengeResponse);
                return;
            }

            if (!adapter.verifyWebhook(payload, ctx.headerMap(), body)) {
                logger.warn("Rejected webhook for {}: verification failed", platform);
                ctx.status(401).json(Map.of("error", "Webhook verification failed"));
                return;
            }

            IncomingMessage message = adapter.parseWebhook(payload);
            if (message == null) {
                ctx.status(200).result("OK");
                return;
            }

            // 异步处理
            executor.submit(() -> processMessage(message, adapter));

            ctx.status(200).result("OK");
        } catch (Exception e) {
            logger.error("Webhook error for {}: {}", platform, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleMessage(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String platform = body.getString("platform");
            String channel = body.getString("channel");
            String content = body.getString("content");
            String userId = body.getString("user_id");
            String tenantId = body.getString("tenant_id");

            if (content == null || content.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Content is required"));
                return;
            }

            PlatformAdapter adapter = adapters.get(platform);
            if (adapter == null) {
                ctx.status(400).json(Map.of("error", "Unknown platform: " + platform));
                return;
            }

            IncomingMessage message = new IncomingMessage(
                UUID.randomUUID().toString(),
                channel,
                userId != null ? userId : "api",
                content,
                System.currentTimeMillis(),
                false
            );

            executor.submit(() -> processMessage(message, adapter, tenantId));

            ctx.json(Map.of(
                "status", "queued",
                "message_id", message.id(),
                "tenant_id", resolveTenantId(message, adapter, tenantId)
            ));

        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleStatus(Context ctx) {
        var stats = tenantManager.getStats();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running);
        status.put("port", port);
        status.put("version", "2.0.0");
        status.put("mode", "tenant");
        status.put("adapters", adapters.keySet());
        status.put("active_threads", Thread.activeCount());
        status.put("uptime", System.currentTimeMillis() / 1000);

        status.put("tenants", Map.of(
            "active", stats.activeInMemory(),
            "registered", stats.totalRegistered(),
            "memory_usage_mb", stats.totalMemoryUsage() / (1024 * 1024)
        ));

        ctx.json(status);
    }

    private void handleTools(Context ctx) {
        List<Map<String, Object>> tools = new ArrayList<>();

        // 获取当前租户的可用工具
        String tenantId = ctx.header("X-Tenant-ID");
        if (tenantId != null) {
            TenantContext tenant = tenantManager.getTenant(tenantId);
            if (tenant != null) {
                var allowedTools = tenant.getSecurityPolicy().getAllowedTools();
                var deniedTools = tenant.getSecurityPolicy().getDeniedTools();

                // 只返回允许的工具
                for (var entry : ToolRegistry.getInstance().getAllTools()) {
                    String name = entry.getName();
                    if (!deniedTools.contains(name) &&
                        (allowedTools.isEmpty() || allowedTools.contains(name))) {
                        tools.add(Map.of(
                            "name", name,
                            "description", entry.getSchema().get("description"),
                            "toolset", entry.getToolset()
                        ));
                    }
                }
            }
        } else {
            // 返回所有工具
            for (var entry : ToolRegistry.getInstance().getAllTools()) {
                tools.add(Map.of(
                    "name", entry.getName(),
                    "description", entry.getSchema().get("description"),
                    "toolset", entry.getToolset()
                ));
            }
        }

        ctx.json(tools);
    }

    private void handleChat(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String message = body.getString("message");
            String sessionId = body.getString("session_id");
            String tenantId = body.getString("tenant_id");
            String userId = body.getString("user_id");

            if (message == null || message.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Message is required"));
                return;
            }

            // 解析租户
            String resolvedTenantId = tenantId != null ? tenantId : "default";
            TenantContext tenant = tenantManager.getOrCreateTenant(
                resolvedTenantId,
                createDefaultProvisioningRequest()
            );

            // 检查租户状态
            if (!tenant.isActive()) {
                ctx.status(403).json(Map.of(
                    "error", "Tenant is not active",
                    "state", tenant.getState().name()
                ));
                return;
            }

            // 检查配额
            try {
                tenant.getQuotaManager().checkDailyRequestQuota();
            } catch (QuotaExceededException e) {
                ctx.status(429).json(Map.of(
                    "error", "Quota exceeded",
                    "message", e.getMessage()
                ));
                return;
            }

            // 创建或获取 Agent
            String resolvedSessionId = sessionId != null ? sessionId
                : UUID.randomUUID().toString();

            // 获取或创建 Agent（租户隔离）
            TenantAIAgent agent = tenant.getOrCreateAgent(resolvedSessionId, config);

            // 应用自定义系统提示词（如果提供）
            String customSystemPrompt = body.getString("system_prompt");
            if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
                agent.setSystemPrompt(customSystemPrompt);
            }

            // 应用模型参数覆盖（如果提供）
            if (body.containsKey("model_params") && body.get("model_params") instanceof com.alibaba.fastjson2.JSONObject mp) {
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                mp.forEach((k, v) -> params.put(k, v));
                agent.setModelParams(params);
            }

            // 处理消息
            long startTime = System.currentTimeMillis();
            String response = agent.processMessage(message);
            long duration = System.currentTimeMillis() - startTime;

            // 更新租户活动状态
            tenant.updateActivity();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("response", response != null ? response : "");
            result.put("session_id", resolvedSessionId);
            result.put("tenant_id", resolvedTenantId);
            result.put("duration_ms", duration);
            result.put("timestamp", System.currentTimeMillis());
            result.put("debug", agent.getSessionDebugInfo());
            ctx.status(200).json(result);

        } catch (Exception e) {
            logger.error("Chat error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleChatStream(Context ctx) throws IOException {
        var response = ctx.res();
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");

        JSONObject body = JSON.parseObject(ctx.body());
        String message = body.getString("message");
        String sessionId = body.getString("session_id");
        String tenantId = body.getString("tenant_id");

        if (message == null || message.trim().isEmpty()) {
            sendSseEvent(ctx, "error", Map.of("error", "Message is required"));
            return;
        }

        // 解析租户
        String resolvedTenantId = tenantId != null ? tenantId : "default";
        TenantContext tenant = tenantManager.getOrCreateTenant(
            resolvedTenantId,
            createDefaultProvisioningRequest()
        );

        // 检查租户状态
        if (!tenant.isActive()) {
            sendSseEvent(ctx, "error", Map.of(
                "error", "Tenant is not active",
                "state", tenant.getState().name()
            ));
            return;
        }

        // 检查配额
        try {
            tenant.getQuotaManager().checkDailyRequestQuota();
        } catch (QuotaExceededException e) {
            sendSseEvent(ctx, "error", Map.of("error", e.getMessage()));
            return;
        }

        String resolvedSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();

        // 发送会话信息
        sendSseEvent(ctx, "session", Map.of("session_id", resolvedSessionId));

        // 获取或创建 Agent（租户隔离）
        TenantAIAgent agent = tenant.getOrCreateAgent(resolvedSessionId, config);

        // 应用自定义系统提示词（如果提供）
        String customSystemPrompt = body.getString("system_prompt");
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            agent.setSystemPrompt(customSystemPrompt);
        }

        // 应用模型参数覆盖（如果提供）
        if (body.containsKey("model_params") && body.get("model_params") instanceof com.alibaba.fastjson2.JSONObject mp) {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            mp.forEach((k, v) -> params.put(k, v));
            agent.setModelParams(params);
        }

        try {
            // True streaming: push each chunk as it arrives from the model
            agent.processMessageStream(message, chunk -> {
                sendSseEvent(ctx, "delta", Map.of("content", chunk));
            });

            // Send debug events (usage + tool calls)
            Map<String, Object> debug = agent.getSessionDebugInfo();
            if (debug.containsKey("usage")) {
                sendSseEvent(ctx, "usage", (Map<String, Object>) debug.get("usage"));
            }
            if (debug.containsKey("toolCalls")) {
                sendSseEvent(ctx, "tool_chain", Map.of("calls", debug.get("toolCalls")));
            }

            // Send completion event
            sendSseEvent(ctx, "done", Map.of(
                "timestamp", System.currentTimeMillis()
            ));

            // Update activity
            tenant.updateActivity();

        } catch (Exception e) {
            logger.error("Stream error: {}", e.getMessage(), e);
            sendSseEvent(ctx, "error", Map.of("error", e.getMessage()));
        }
    }

    // ==================== Tenant API Methods ====================

    private void handleGetTenants(Context ctx) {
        var stats = tenantManager.getStats();

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
        int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);

        List<Map<String, Object>> tenants = tenantManager.listActiveTenants().stream()
            .skip(offset)
            .limit(limit)
            .map(t -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", t.getTenantId());
                map.put("state", t.getState().name());
                map.put("created_at", t.getCreatedAt().toString());
                map.put("last_activity", t.getLastActivity().toString());
                map.put("active_agents", t.getActiveAgentCount());
                map.put("active_sessions", t.getActiveSessionCount());
                return map;
            })
            .toList();

        ctx.json(Map.of(
            "tenants", tenants,
            "pagination", Map.of(
                "total", stats.totalRegistered(),
                "limit", limit,
                "offset", offset
            ),
            "stats", Map.of(
                "active_in_memory", stats.activeInMemory(),
                "active_tenants", stats.activeTenants(),
                "suspended_tenants", stats.suspendedTenants(),
                "total_registered", stats.totalRegistered(),
                "memory_usage_mb", stats.totalMemoryUsage() / (1024 * 1024)
            )
        ));
    }

    private void handleCreateTenant(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String tenantId = body.getString("id");

            if (tenantId == null || tenantId.isEmpty()) {
                tenantId = "tenant_" + UUID.randomUUID().toString().substring(0, 8);
            }

            // 检查是否已存在
            if (tenantManager.exists(tenantId)) {
                ctx.status(409).json(Map.of(
                    "error", "Tenant already exists",
                    "id", tenantId
                ));
                return;
            }

            // 创建配置请求
            String createdBy = body.getString("created_by");
            TenantProvisioningRequest request = TenantProvisioningRequest.builder(tenantId, createdBy)
                .build();

            if (body.containsKey("quota")) {
                // 解析自定义配额
                JSONObject quotaJson = body.getJSONObject("quota");
                // ... 设置配额
            }

            TenantContext tenant = tenantManager.createTenant(request);

            ctx.status(201).json(Map.of(
                "id", tenant.getTenantId(),
                "state", tenant.getState().name(),
                "created_at", tenant.getCreatedAt().toString()
            ));

        } catch (Exception e) {
            logger.error("Failed to create tenant: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleGetTenant(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        ctx.json(Map.of(
            "id", tenant.getTenantId(),
            "state", tenant.getState().name(),
            "created_at", tenant.getCreatedAt().toString(),
            "last_activity", tenant.getLastActivity().toString(),
            "active_agents", tenant.getActiveAgentCount(),
            "active_sessions", tenant.getActiveSessionCount(),
            "workspace_path", tenant.getTenantDir().toString()
        ));
    }

    private void handleDeleteTenant(Context ctx) {
        String tenantId = ctx.pathParam("id");

        if (!tenantManager.exists(tenantId)) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        boolean preserveData = ctx.queryParamAsClass("preserve_data", Boolean.class).getOrDefault(false);

        tenantManager.destroyTenant(tenantId, preserveData);

        ctx.json(Map.of(
            "id", tenantId,
            "status", "deleted",
            "preserve_data", preserveData
        ));
    }

    private void handleSuspendTenant(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        String reason = ctx.body().isEmpty() ? "Suspended by administrator"
            : JSON.parseObject(ctx.body()).getString("reason");

        tenantManager.suspendTenant(tenantId, reason);

        ctx.json(Map.of(
            "id", tenantId,
            "status", "suspended",
            "reason", reason
        ));
    }

    private void handleResumeTenant(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        tenantManager.resumeTenant(tenantId);

        ctx.json(Map.of(
            "id", tenantId,
            "status", "resumed",
            "state", tenantManager.getTenant(tenantId).getState().name()
        ));
    }

    private void handleGetTenantQuota(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        var quota = tenant.getQuotaManager().getQuota();
        var usage = tenant.getQuotaManager().getUsage();

        ctx.json(Map.of(
            "tenant_id", tenantId,
            "daily_requests", Map.of(
                "limit", quota.getMaxDailyRequests(),
                "used", usage.getDailyRequests(),
                "remaining", Math.max(0, quota.getMaxDailyRequests() - usage.getDailyRequests())
            ),
            "daily_tokens", Map.of(
                "limit", quota.getMaxDailyTokens(),
                "used", usage.getDailyTokens(),
                "remaining", Math.max(0, quota.getMaxDailyTokens() - usage.getDailyTokens())
            ),
            "concurrent_agents", Map.of(
                "limit", quota.getMaxConcurrentAgents(),
                "used", tenant.getActiveAgentCount()
            ),
            "storage_bytes", Map.of(
                "limit", quota.getMaxStorageBytes(),
                "used", usage.getStorageBytes()
            ),
            "tool_calls_per_session", quota.getMaxToolCallsPerSession()
        ));
    }

    private void handleUpdateTenantQuota(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        try {
            JSONObject body = JSON.parseObject(ctx.body());
            var quota = tenant.getQuotaManager().getQuota();

            if (body.containsKey("daily_requests")) {
                quota.setMaxDailyRequests(body.getIntValue("daily_requests"));
            }
            if (body.containsKey("daily_tokens")) {
                quota.setMaxDailyTokens(body.getIntValue("daily_tokens"));
            }
            if (body.containsKey("concurrent_agents")) {
                quota.setMaxConcurrentAgents(body.getIntValue("concurrent_agents"));
            }
            if (body.containsKey("storage_bytes")) {
                quota.setMaxStorageBytes(body.getLongValue("storage_bytes"));
            }

            tenant.getQuotaManager().updateQuota(quota);

            ctx.json(Map.of(
                "tenant_id", tenantId,
                "status", "updated",
                "quota", quota.toMap()
            ));

        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleGetTenantUsage(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        var usage = tenant.getQuotaManager().getUsage();
        var stats = tenant.getToolRegistry().getStats();

        ctx.json(Map.of(
            "tenant_id", tenantId,
            "requests", Map.of(
                "total", usage.getTotalRequests(),
                "today", usage.getDailyRequests()
            ),
            "tokens", Map.of(
                "total", usage.getTotalTokens(),
                "today", usage.getDailyTokens()
            ),
            "storage_bytes", usage.getStorageBytes(),
            "active_agents", tenant.getActiveAgentCount(),
            "active_sessions", tenant.getActiveSessionCount(),
            "tool_calls", Map.of(
                "current_session", stats.currentCalls(),
                "max_per_session", stats.maxCalls()
            )
        ));
    }

    private void handleGetTenantAudit(Context ctx) {
        String tenantId = ctx.pathParam("id");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        String eventType = ctx.queryParam("event_type");

        // 获取审计日志
        var logs = tenant.getAuditLogger().getRecentEvents(limit, eventType);

        ctx.json(Map.of(
            "tenant_id", tenantId,
            "logs", logs,
            "total", logs.size()
        ));
    }

    // ==================== Session API Methods ====================

    private void handleGetTenantSessions(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);

        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        try {
            var sessions = tenant.getSessionManager().listSessions();
            ctx.json(Map.of(
                "tenant_id", tenantId,
                "sessions", sessions,
                "count", sessions.size()
            ));
        } catch (IOException e) {
            logger.error("Failed to list sessions: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Failed to list sessions: " + e.getMessage()));
        }
    }

    private void handleGetSessionMessages(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String sessionId = ctx.pathParam("sessionId");

        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        try {
            var session = tenant.getSessionManager().getSession(sessionId);
            if (session == null) {
                ctx.status(404).json(Map.of("error", "Session not found"));
                return;
            }

            ctx.json(Map.of(
                "tenant_id", tenantId,
                "session_id", sessionId,
                "messages", session.getMessages()
            ));

        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleDeleteSession(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        String sessionId = ctx.pathParam("sessionId");

        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }

        try {
            tenant.getSessionManager().deleteSession(sessionId);
            ctx.json(Map.of(
                "tenant_id", tenantId,
                "session_id", sessionId,
                "status", "deleted"
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Other API Methods ====================

    private void handleGetConfig(Context ctx) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model", Map.of(
            "provider", this.config.getProvider(),
            "model", this.config.getCurrentModel()
        ));
        config.put("tenant_mode", true);
        config.put("version", "2.0.0");

        ctx.json(config);
    }

    private void handleGetSkills(Context ctx) {
        // 获取租户特定的 skills
        String tenantId = ctx.queryParam("tenant_id");
        if (tenantId != null) {
            TenantContext tenant = tenantManager.getTenant(tenantId);
            if (tenant != null) {
                var skills = tenant.getSkillManager().listSkills();
                ctx.json(skills);
                return;
            }
        }

        // 返回全局 skills
        ctx.json(Map.of("skills", List.of()));
    }

    // ==================== Core Processing Logic ====================

    private void processMessage(com.nousresearch.hermes.gateway.IncomingMessage message, com.nousresearch.hermes.gateway.PlatformAdapter adapter) {
        processMessage(message, adapter, null);
    }

    private void processMessage(com.nousresearch.hermes.gateway.IncomingMessage message,
                                com.nousresearch.hermes.gateway.PlatformAdapter adapter,
                                String explicitTenantId) {
        String tenantId = resolveTenantId(message, adapter, explicitTenantId);

        try {
            logger.info("Processing message from {} on {} for tenant {}",
                message.sender(), adapter.getPlatformName(), tenantId);

            // 获取或创建租户
            TenantContext tenant = tenantManager.getOrCreateTenant(
                tenantId,
                createProvisioningRequest(message, adapter)
            );

            // 检查租户状态
            if (!tenant.isActive()) {
                adapter.sendMessage(message.channel(),
                    "⚠️ Your account is currently " + tenant.getState() +
                    ". Please contact support.");
                return;
            }

            // 检查配额
            try {
                tenant.getQuotaManager().checkDailyRequestQuota();
            } catch (QuotaExceededException e) {
                adapter.sendMessage(message.channel(),
                    "⚠️ Daily quota exceeded. Please try again tomorrow or upgrade your plan.");
                return;
            }

            // 获取或创建会话 ID
            String sessionId = message.id() != null ? message.id()
                : adapter.getPlatformName() + ":" + message.channel();

            // 获取或创建 Agent（租户隔离）
            TenantAIAgent agent = tenant.getOrCreateAgent(sessionId, config);

            // 处理消息
            String response = agent.processMessage(message.content());

            // 发送响应
            if (response != null && !response.isEmpty()) {
                adapter.sendMessage(message.channel(), response);
            }

            // 更新活动状态
            tenant.updateActivity();

        } catch (Exception e) {
            logger.error("Failed to process message: {}", e.getMessage(), e);
            try {
                adapter.sendMessage(message.channel(),
                    "❌ An error occurred while processing your request.");
            } catch (Exception sendError) {
                logger.error("Failed to send error message: {}", sendError.getMessage());
            }
        }
    }

    private String resolveTenantId(com.nousresearch.hermes.gateway.IncomingMessage message, com.nousresearch.hermes.gateway.PlatformAdapter adapter) {
        return resolveTenantId(message, adapter, null);
    }

    private String resolveTenantId(com.nousresearch.hermes.gateway.IncomingMessage message,
                                   com.nousresearch.hermes.gateway.PlatformAdapter adapter,
                                   String explicitTenantId) {
        if (explicitTenantId != null && !explicitTenantId.isBlank()) {
            return sanitizeTenantId(explicitTenantId);
        }

        // 策略1：缓存查询
        String userKey = adapter.getPlatformName() + ":" + message.sender();
        String cachedTenant = userTenantCache.get(userKey);
        if (cachedTenant != null) {
            return cachedTenant;
        }

        // 策略3：自动创建个人租户
        String autoTenantId = "user_" + sanitizeTenantId(message.sender());
        userTenantCache.put(userKey, autoTenantId);
        return autoTenantId;
    }

    private String sanitizeTenantId(String tenantId) {
        return tenantId.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private TenantProvisioningRequest createProvisioningRequest(
            com.nousresearch.hermes.gateway.IncomingMessage message, com.nousresearch.hermes.gateway.PlatformAdapter adapter) {
        String tenantId = resolveTenantId(message, adapter);
        String createdBy = adapter.getPlatformName() + ":" + message.sender();
        return TenantProvisioningRequest.builder(tenantId, createdBy).build();
    }

    private TenantProvisioningRequest createDefaultProvisioningRequest() {
        return new TenantProvisioningRequest()
            .withDefaultQuota()
            .withDefaultSecurityPolicy();
    }

    private void extractTenantContext(Context ctx) {
        String tenantId = ctx.header("X-Tenant-ID");
        if (tenantId != null) {
            ctx.attribute("tenant_id", tenantId);
        }
    }

    // ==================== Helper Methods ====================

    private void sendSseEvent(Context ctx, String event, Map<String, Object> data) {
        try {
            jakarta.servlet.http.HttpServletResponse response = ctx.res();
            String payload = "event: " + event + "\ndata: " + JSON.toJSONString(data) + "\n\n";
            response.getOutputStream().write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.error("Failed to send SSE event: {}", e.getMessage());
        }
    }

    private void startSessionCleanupTask() {
        sessionCleanupExecutor.scheduleWithFixedDelay(
            () -> {
                try {
                    tenantManager.cleanupIdleTenants();
                } catch (Exception e) {
                    logger.error("Session cleanup error: {}", e.getMessage());
                }
            },
            5, 5, TimeUnit.MINUTES
        );
    }

    public void registerAdapter(com.nousresearch.hermes.gateway.PlatformAdapter adapter) {
        adapters.put(adapter.getPlatformName(), adapter);
        logger.info("Registered adapter: {}", adapter.getPlatformName());
    }

    // ==================== Data Classes ====================
    // Using IncomingMessage for compatibility

}
