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
        
        // Sessions API
        app.get("/api/sessions", this::handleGetSessions);
        app.get("/api/sessions/{id}/messages", this::handleGetSessionMessages);
        
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
