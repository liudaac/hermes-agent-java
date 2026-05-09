package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.UUID;

/**
 * Gateway HTTP server for webhook handling.
 * Mirrors Python's gateway API server.
 */
public class GatewayServer {
    private static final Logger logger = LoggerFactory.getLogger(GatewayServer.class);
    
    private final int port;
    private final HermesConfig config;
    private final Map<String, PlatformAdapter> adapters;
    private final ExecutorService executor;
    private Javalin app;
    private volatile boolean running = false;
    
    public GatewayServer(int port, HermesConfig config) {
        this.port = port;
        this.config = config;
        this.adapters = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();
    }
    
    /**
     * Register a platform adapter.
     */
    public void registerAdapter(PlatformAdapter adapter) {
        adapters.put(adapter.getPlatformName(), adapter);
        logger.info("Registered adapter: {}", adapter.getPlatformName());
    }
    
    /**
     * Start the gateway server.
     */
    public void start() {
        if (running) {
            logger.warn("Gateway already running");
            return;
        }
        
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });
        
        // Health check
        app.get("/health", ctx -> ctx.result("OK"));
        
        // CORS
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });
        app.options("/*", ctx -> ctx.status(200));
        
        // Webhook endpoints for each platform
        app.post("/webhook/{platform}", this::handleWebhook);
        
        // Message API
        app.post("/api/message", this::handleMessage);
        
        // Status API
        app.get("/api/status", this::handleStatus);
        
        // Tools API
        app.get("/api/tools", this::handleTools);
        
        // Config API
        app.get("/api/config", this::handleGetConfig);
        app.post("/api/config", this::handleUpdateConfig);
        app.get("/api/config/schema", this::handleGetConfigSchema);
        
        // Sessions API
        app.get("/api/sessions", this::handleGetSessions);
        app.get("/api/sessions/{id}/messages", this::handleGetSessionMessages);
        
        // Chat API
        app.post("/api/chat", this::handleChat);
        
        // Tenants API
        app.get("/api/tenants", this::handleGetTenants);
        app.post("/api/tenants", this::handleCreateTenant);
        app.get("/api/tenants/{id}", this::handleGetTenant);
        app.delete("/api/tenants/{id}", this::handleDeleteTenant);
        app.post("/api/tenants/{id}/suspend", this::handleSuspendTenant);
        app.post("/api/tenants/{id}/resume", this::handleResumeTenant);
        app.get("/api/tenants/{id}/quota", this::handleGetTenantQuota);
        app.put("/api/tenants/{id}/quota", this::handleUpdateTenantQuota);
        app.get("/api/tenants/{id}/usage", this::handleGetTenantUsage);
        app.get("/api/tenants/{id}/security", this::handleGetTenantSecurity);
        app.put("/api/tenants/{id}/security", this::handleUpdateTenantSecurity);
        app.get("/api/tenants/{id}/audit", this::handleGetTenantAudit);
        
        // Skills API
        app.get("/api/skills", this::handleGetSkills);
        app.put("/api/skills/{name}", this::handleUpdateSkill);
        
        // Cron API
        app.get("/api/cron", this::handleGetCronJobs);
        app.post("/api/cron", this::handleCreateCronJob);
        app.put("/api/cron/{id}", this::handleUpdateCronJob);
        app.delete("/api/cron/{id}", this::handleDeleteCronJob);
        
        // Env API
        app.get("/api/env", this::handleGetEnv);
        app.put("/api/env", this::handleSetEnv);
        app.delete("/api/env/{key}", this::handleDeleteEnv);
        
        // Actions API
        app.post("/api/actions/restart-gateway", this::handleRestartGateway);
        app.post("/api/actions/update", this::handleUpdateHermes);
        app.get("/api/actions/{name}/status", this::handleGetActionStatus);
        
        // Logs API
        app.get("/api/logs", this::handleGetLogs);
        
        // Analytics API
        app.get("/api/analytics", this::handleGetAnalytics);
        
        app.start(port);
        running = true;
        
        logger.info("Gateway server started on port {}", port);
    }
    
    /**
     * Stop the gateway server.
     */
    public void stop() {
        running = false;
        if (app != null) {
            app.stop();
        }
        executor.shutdown();
        logger.info("Gateway server stopped");
    }
    
    /**
     * Handle incoming webhook.
     */
    private void handleWebhook(Context ctx) {
        String platform = ctx.pathParam("platform");
        PlatformAdapter adapter = adapters.get(platform);
        
        if (adapter == null) {
            ctx.status(404).result("Unknown platform: " + platform);
            return;
        }
        
        try {
            String body = ctx.body();
            JSONObject payload = JSON.parseObject(body);
            
            // Parse message from webhook payload
            IncomingMessage message = adapter.parseWebhook(payload);
            if (message == null) {
                ctx.status(200).result("OK"); // Acknowledge but no message to process
                return;
            }
            
            // Process message asynchronously
            executor.submit(() -> processMessage(message, adapter));
            
            ctx.status(200).result("OK");
        } catch (Exception e) {
            logger.error("Webhook error for {}: {}", platform, e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    /**
     * Handle direct message API.
     */
    private void handleMessage(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String platform = body.getString("platform");
            String channel = body.getString("channel");
            String content = body.getString("content");
            
            PlatformAdapter adapter = adapters.get(platform);
            if (adapter == null) {
                ctx.status(400).result("Unknown platform: " + platform);
                return;
            }
            
            IncomingMessage message = new IncomingMessage(
                UUID.randomUUID().toString(),
                channel,
                "api",
                content,
                System.currentTimeMillis(),
                false
            );
            
            executor.submit(() -> processMessage(message, adapter));
            
            ctx.json(Map.of("status", "queued", "message_id", message.id));
        } catch (Exception e) {
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    /**
     * Get gateway status.
     */
    private void handleStatus(Context ctx) {
        Map<String, Object> status = new HashMap<>();
        status.put("running", running);
        status.put("port", port);
        status.put("adapters", adapters.keySet());
        status.put("active_threads", Thread.activeCount());
        status.put("version", "0.1.0");
        status.put("uptime", System.currentTimeMillis() / 1000);
        status.put("connected", running);
        
        ctx.json(status);
    }
    
    /**
     * Get available tools.
     */
    private void handleTools(Context ctx) {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        tools.add(createTool("terminal", "Execute terminal commands", "💻"));
        tools.add(createTool("web_search", "Search the web", "🔍"));
        tools.add(createTool("file_operations", "Read and write files", "📁"));
        tools.add(createTool("browser", "Browse websites", "🌐"));
        tools.add(createTool("image_generation", "Generate images", "🎨"));
        tools.add(createTool("code_execution", "Execute code", "⚡"));
        tools.add(createTool("memory", "Store and retrieve memories", "🧠"));
        tools.add(createTool("sub_agent", "Spawn sub-agents", "🤖"));
        
        ctx.json(tools);
    }
    
    private Map<String, Object> createTool(String name, String description, String emoji) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("emoji", emoji);
        return tool;
    }
    
    /**
     * Get configuration.
     */
    private void handleGetConfig(Context ctx) {
        Map<String, Object> config = new HashMap<>();
        config.put("model", Map.of(
            "provider", "openrouter",
            "model", "anthropic/claude-3.5-sonnet"
        ));
        config.put("display", Map.of(
            "personality", "kawaii"
        ));
        config.put("tools", Map.of(
            "enabled", List.of("terminal", "web_search", "file_operations", "browser")
        ));
        
        ctx.json(config);
    }
    
    /**
     * Update configuration.
     */
    private void handleUpdateConfig(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            // In real implementation, update config file
            ctx.json(Map.of("status", "updated"));
        } catch (Exception e) {
            ctx.status(400).result("Invalid config: " + e.getMessage());
        }
    }
    
    /**
     * Get active sessions.
     */
    private void handleGetSessions(Context ctx) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        // In real implementation, get from session manager
        sessions.add(Map.of(
            "id", "session-1",
            "platform", "web",
            "user", "anonymous",
            "lastActivity", System.currentTimeMillis(),
            "messageCount", 0
        ));
        
        ctx.json(sessions);
    }
    
    /**
     * Get session messages.
     */
    private void handleGetSessionMessages(Context ctx) {
        String sessionId = ctx.pathParam("id");
        List<Map<String, Object>> messages = new ArrayList<>();
        // In real implementation, get from session manager
        
        ctx.json(Map.of(
            "sessionId", sessionId,
            "messages", messages
        ));
    }
    
    /**
     * Process an incoming message.
     */
    private void processMessage(IncomingMessage message, PlatformAdapter adapter) {
        try {
            logger.info("Processing message from {}: {}", 
                message.sender, message.content.substring(0, Math.min(50, message.content.length())));
            
            // Create agent and process
            AIAgent agent = new AIAgent(config);
            String response = agent.processMessage(message.content);
            
            // Send response back
            adapter.sendMessage(message.channel, response);
            
        } catch (Exception e) {
            logger.error("Failed to process message: {}", e.getMessage(), e);
            try {
                adapter.sendMessage(message.channel, "Error processing your request: " + e.getMessage());
            } catch (Exception sendError) {
                logger.error("Failed to send error message: {}", sendError.getMessage());
            }
        }
    }
    
    // ==================== Chat API ====================
    
    private void handleChat(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String message = body.getString("message");
            String sessionId = body.getString("session_id");
            
            // Process chat message through AI agent
            AIAgent agent = new AIAgent(config);
            String response = agent.processMessage(message);
            
            ctx.json(Map.of(
                "response", response,
                "session_id", sessionId != null ? sessionId : UUID.randomUUID().toString(),
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
    
    // ==================== Config Schema API ====================
    
    private void handleGetConfigSchema(Context ctx) {
        List<Map<String, Object>> sections = new ArrayList<>();
        
        // Model section
        Map<String, Object> modelSection = new LinkedHashMap<>();
        modelSection.put("name", "model");
        modelSection.put("title", "模型设置");
        List<Map<String, Object>> modelFields = new ArrayList<>();
        modelFields.add(createField("provider", "string", "提供商", "AI模型提供商", "openrouter", false, 
            List.of(Map.of("label", "OpenRouter", "value", "openrouter"),
                    Map.of("label", "OpenAI", "value", "openai"),
                    Map.of("label", "Anthropic", "value", "anthropic"))));
        modelFields.add(createField("model", "string", "模型", "使用的AI模型", "anthropic/claude-3.5-sonnet", false, null));
        modelFields.add(createField("api_key", "password", "API密钥", "提供商的API密钥", "", true, null));
        modelSection.put("fields", modelFields);
        sections.add(modelSection);
        
        // Display section
        Map<String, Object> displaySection = new LinkedHashMap<>();
        displaySection.put("name", "display");
        displaySection.put("title", "显示设置");
        List<Map<String, Object>> displayFields = new ArrayList<>();
        displayFields.add(createField("personality", "select", "性格", "AI助手性格", "kawaii", false,
            List.of(Map.of("label", "可爱", "value", "kawaii"),
                    Map.of("label", "专业", "value", "professional"),
                    Map.of("label", "友好", "value", "friendly"))));
        displaySection.put("fields", displayFields);
        sections.add(displaySection);
        
        ctx.json(Map.of("sections", sections));
    }
    
    private Map<String, Object> createField(String key, String type, String label, String description, 
                                             Object defaultValue, boolean secret, List<Map<String, Object>> options) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", key);
        field.put("type", type);
        field.put("label", label);
        field.put("description", description);
        field.put("default", defaultValue);
        field.put("secret", secret);
        if (options != null) field.put("options", options);
        return field;
    }
    
    // ==================== Tenants API ====================
    
    private void handleGetTenants(Context ctx) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active_tenants", 1);
        stats.put("suspended_tenants", 0);
        stats.put("total_registered", 1);
        ctx.json(stats);
    }
    
    private void handleCreateTenant(Context ctx) {
        ctx.json(Map.of("id", UUID.randomUUID().toString(), "status", "created"));
    }
    
    private void handleGetTenant(Context ctx) {
        String tenantId = ctx.pathParam("id");
        ctx.json(Map.of(
            "id", tenantId,
            "name", "Default Tenant",
            "status", "active",
            "created_at", System.currentTimeMillis()
        ));
    }
    
    private void handleDeleteTenant(Context ctx) {
        ctx.json(Map.of("status", "deleted"));
    }
    
    private void handleSuspendTenant(Context ctx) {
        ctx.json(Map.of("status", "suspended"));
    }
    
    private void handleResumeTenant(Context ctx) {
        ctx.json(Map.of("status", "resumed"));
    }
    
    private void handleGetTenantQuota(Context ctx) {
        ctx.json(Map.of(
            "daily_requests", 1000,
            "max_tokens", 100000,
            "max_sessions", 100,
            "used_requests", 123,
            "used_tokens", 45678
        ));
    }
    
    private void handleUpdateTenantQuota(Context ctx) {
        ctx.json(Map.of("status", "updated"));
    }
    
    private void handleGetTenantUsage(Context ctx) {
        ctx.json(Map.of(
            "total_requests", 1234,
            "total_tokens", 567890,
            "sessions", 12,
            "uptime", 86400
        ));
    }
    
    private void handleGetTenantSecurity(Context ctx) {
        ctx.json(Map.of(
            "file_access", true,
            "web_access", true,
            "shell_access", false,
            "max_file_size", 10485760
        ));
    }
    
    private void handleUpdateTenantSecurity(Context ctx) {
        ctx.json(Map.of("status", "updated"));
    }
    
    private void handleGetTenantAudit(Context ctx) {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(Map.of(
            "timestamp", System.currentTimeMillis(),
            "type", "login",
            "details", Map.of("ip", "127.0.0.1")
        ));
        ctx.json(logs);
    }
    
    // ==================== Skills API ====================
    
    private void handleGetSkills(Context ctx) {
        List<Map<String, Object>> skills = new ArrayList<>();
        skills.add(Map.of("name", "terminal", "version", "1.0", "description", "终端操作", "enabled", true));
        skills.add(Map.of("name", "web_search", "version", "1.0", "description", "网页搜索", "enabled", true));
        skills.add(Map.of("name", "file_operations", "version", "1.0", "description", "文件操作", "enabled", true));
        ctx.json(skills);
    }
    
    private void handleUpdateSkill(Context ctx) {
        ctx.json(Map.of("status", "updated"));
    }
    
    // ==================== Cron API ====================
    
    private void handleGetCronJobs(Context ctx) {
        List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(Map.of(
            "id", "job-1",
            "name", "Daily Report",
            "schedule", "0 9 * * *",
            "command", "generate-daily-report",
            "enabled", true,
            "last_run", System.currentTimeMillis() - 3600000,
            "next_run", System.currentTimeMillis() + 3600000
        ));
        ctx.json(jobs);
    }
    
    private void handleCreateCronJob(Context ctx) {
        ctx.json(Map.of("id", UUID.randomUUID().toString(), "status", "created"));
    }
    
    private void handleUpdateCronJob(Context ctx) {
        ctx.json(Map.of("status", "updated"));
    }
    
    private void handleDeleteCronJob(Context ctx) {
        ctx.json(Map.of("status", "deleted"));
    }
    
    // ==================== Env API ====================
    
    private void handleGetEnv(Context ctx) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("OPENAI_API_KEY", Map.of("value", "sk-****", "updated_at", System.currentTimeMillis()));
        env.put("ANTHROPIC_API_KEY", Map.of("value", "sk-****", "updated_at", System.currentTimeMillis()));
        ctx.json(env);
    }
    
    private void handleSetEnv(Context ctx) {
        ctx.json(Map.of("status", "updated"));
    }
    
    private void handleDeleteEnv(Context ctx) {
        ctx.json(Map.of("status", "deleted"));
    }
    
    // ==================== Actions API ====================
    
    private void handleRestartGateway(Context ctx) {
        ctx.json(Map.of("status", "restarting", "timestamp", System.currentTimeMillis()));
        // In real implementation, schedule restart
    }
    
    private void handleUpdateHermes(Context ctx) {
        ctx.json(Map.of("status", "updating", "version", "latest"));
    }
    
    private void handleGetActionStatus(Context ctx) {
        String actionName = ctx.pathParam("name");
        ctx.json(Map.of(
            "action", actionName,
            "running", false,
            "exit_code", 0,
            "lines", List.of("Action completed successfully")
        ));
    }
    
    // ==================== Logs API ====================
    
    private void handleGetLogs(Context ctx) {
        String level = ctx.queryParam("level");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(Map.of(
            "timestamp", System.currentTimeMillis(),
            "level", "INFO",
            "message", "Gateway server started",
            "source", "GatewayServer"
        ));
        logs.add(Map.of(
            "timestamp", System.currentTimeMillis() - 60000,
            "level", "DEBUG",
            "message", "Processing message",
            "source", "AIAgent"
        ));
        ctx.json(logs);
    }
    
    // ==================== Analytics API ====================
    
    private void handleGetAnalytics(Context ctx) {
        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("total_messages", 1234);
        analytics.put("total_sessions", 56);
        analytics.put("avg_response_time", 2.5);
        analytics.put("top_platforms", Map.of("web", 500, "telegram", 400, "discord", 334));
        ctx.json(analytics);
    }
    
    // ==================== Data Classes ====================
    
    public record IncomingMessage(
        String id,
        String channel,
        String sender,
        String content,
        long timestamp,
        boolean isGroup
    ) {}
    
    /**
     * Platform adapter interface.
     */
    public interface PlatformAdapter {
        String getPlatformName();
        IncomingMessage parseWebhook(JSONObject payload);
        void sendMessage(String channel, String content) throws Exception;
        void sendReply(String channel, String messageId, String content) throws Exception;
    }
}
