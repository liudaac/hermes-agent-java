package com.nousresearch.hermes.gateway.platforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Discord platform adapter.
 * Handles Discord bot messages via Gateway API.
 */
public class DiscordAdapter implements PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_BASE = "https://discord.com/api/v10";
    
    private final HermesConfig config;
    private final OkHttpClient httpClient;
    private AIAgent agent;
    private volatile boolean connected;
    private volatile boolean running;
    
    private String botToken;
    private Thread gatewayThread;
    
    public DiscordAdapter(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "discord";
    }
    
    @Override
    public void start() throws Exception {
        logger.info("Starting Discord adapter...");
        
        botToken = System.getenv("DISCORD_BOT_TOKEN");
        if (botToken == null || botToken.isEmpty()) {
            throw new IllegalStateException("DISCORD_BOT_TOKEN must be set");
        }
        
        // Test connection
        if (!testConnection()) {
            throw new IllegalStateException("Failed to connect to Discord API");
        }
        
        running = true;
        connected = true;
        
        // Start Gateway connection thread
        gatewayThread = new Thread(this::runGateway, "discord-gateway");
        gatewayThread.setDaemon(true);
        gatewayThread.start();
        
        logger.info("Discord adapter started");
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Stopping Discord adapter...");
        running = false;
        connected = false;
        
        if (gatewayThread != null) {
            gatewayThread.interrupt();
            gatewayThread.join(5000);
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public void sendMessage(String channelId, String message) throws Exception {
        Map<String, Object> requestBody = Map.of(
            "content", message
        );
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channelId + "/messages")
            .post(body)
            .header("Authorization", "Bot " + botToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new IOException("Failed to send message: " + response.code() + " - " + errorBody);
            }
            
            logger.debug("Message sent to Discord channel: {}", channelId);
        }
    }
    
    @Override
    public void setAgent(AIAgent agent) {
        this.agent = agent;
    }
    
    /**
     * Run Discord Gateway connection.
     * In a full implementation, this would use WebSocket for real-time events.
     * For now, we use a simplified polling approach.
     */
    private void runGateway() {
        // Note: Full Discord Gateway requires WebSocket implementation
        // This is a simplified version that polls for messages
        
        logger.info("Discord Gateway started (simplified polling mode)");
        
        while (running) {
            try {
                // In a real implementation, this would:
                // 1. Connect to wss://gateway.discord.gg
                // 2. Handle heartbeat
                // 3. Process Gateway events
                
                // For now, just keep alive
                Thread.sleep(10000);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Gateway error: {}", e.getMessage(), e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    /**
     * Handle incoming Discord message.
     * This would be called by the Gateway event handler.
     */
    public void handleMessage(JsonNode message) {
        try {
            String channelId = message.path("channel_id").asText();
            String authorId = message.path("author").path("id").asText();
            String content = message.path("content").asText();
            boolean isBot = message.path("author").path("bot").asBoolean(false);
            
            // Ignore bot messages
            if (isBot) {
                return;
            }
            
            logger.info("Received Discord message: {}", content);
            
            // Check if mentioned or DM
            boolean isMentioned = content.contains("<@") && content.contains(">");
            boolean isDM = message.path("guild_id").isMissingNode();
            
            if (!isMentioned && !isDM) {
                return; // Only respond to mentions or DMs
            }
            
            // Remove mention from content
            String cleanContent = content.replaceAll("<@!?\\d+>", "").trim();
            
            // Process with agent
            if (agent != null) {
                // In real implementation, process through agent
                sendMessage(channelId, "Received: " + cleanContent + 
                    "\n\n(Agent processing not yet implemented)");
            }
            
        } catch (Exception e) {
            logger.error("Failed to handle message: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Test connection to Discord API.
     */
    private boolean testConnection() {
        try {
            Request request = new Request.Builder()
                .url(API_BASE + "/users/@me")
                .header("Authorization", "Bot " + botToken)
                .get()
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return false;
                }
                
                String body = response.body().string();
                JsonNode result = mapper.readTree(body);
                
                String botName = result.path("username").asText();
                String discriminator = result.path("discriminator").asText();
                
                logger.info("Connected to Discord as {}#{}", botName, discriminator);
                return true;
            }
        } catch (Exception e) {
            logger.error("Connection test failed: {}", e.getMessage());
        }
        return false;
    }
}