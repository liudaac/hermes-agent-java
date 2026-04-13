package com.nousresearch.hermes.gateway.platforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nousresearch.hermes.gateway.GatewayServer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Discord platform adapter.
 */
public class DiscordAdapter implements GatewayServer.PlatformAdapter, com.nousresearch.hermes.gateway.platforms.PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String API_BASE = "https://discord.com/api/v10";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String botToken;
    
    public DiscordAdapter() {
        this.botToken = System.getenv("DISCORD_BOT_TOKEN");
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    public DiscordAdapter(com.nousresearch.hermes.config.HermesConfig config) {
        this(); // Delegate to default constructor
    }
    
    @Override
    public String getPlatformName() {
        return "discord";
    }
    
    @Override
    public GatewayServer.IncomingMessage parseWebhook(JsonNode payload) {
        try {
            // Handle ping
            if (payload.path("type").asInt() == 1) {
                return null;
            }
            
            // Handle application commands
            if (payload.path("type").asInt() == 2) {
                JsonNode data = payload.path("data");
                String content = "/" + data.path("name").asText();
                
                return new GatewayServer.IncomingMessage(
                    payload.path("id").asText(),
                    payload.path("channel_id").asText(),
                    payload.path("member").path("user").path("id").asText(),
                    content,
                    System.currentTimeMillis(),
                    payload.has("guild_id")
                );
            }
            
            // Handle messages
            JsonNode message = payload;
            String content = message.path("content").asText();
            
            return new GatewayServer.IncomingMessage(
                message.path("id").asText(),
                message.path("channel_id").asText(),
                message.path("author").path("id").asText(),
                content,
                System.currentTimeMillis(),
                message.has("guild_id")
            );
            
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendMessage(String channel, String content) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("content", content);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channel + "/messages")
            .post(RequestBody.create(body.toString(), JSON))
            .header("Authorization", "Bot " + botToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Send failed: " + response.code());
            }
        }
    }
    
    @Override
    public void sendReply(String channel, String messageId, String content) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("content", content);
        
        ObjectNode reference = mapper.createObjectNode();
        reference.put("message_id", messageId);
        body.set("message_reference", reference);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channel + "/messages")
            .post(RequestBody.create(body.toString(), JSON))
            .header("Authorization", "Bot " + botToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Reply failed: " + response.code());
            }
        }
    }
    
    /**
     * Send embed message.
     */
    public void sendEmbed(String channel, String title, String description, int color) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        
        ObjectNode embed = mapper.createObjectNode();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("color", color);
        
        ArrayNode embeds = mapper.createArrayNode();
        embeds.add(embed);
        body.set("embeds", embeds);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channel + "/messages")
            .post(RequestBody.create(body.toString(), JSON))
            .header("Authorization", "Bot " + botToken)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Send failed: " + response.code());
            }
        }
    }
    
    /**
     * Create interaction response.
     */
    public void createInteractionResponse(String interactionId, String interactionToken, 
                                         String content) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", 4); // Channel message with source
        
        ObjectNode data = mapper.createObjectNode();
        data.put("content", content);
        body.set("data", data);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/interactions/" + interactionId + "/" + interactionToken + "/callback")
            .post(RequestBody.create(body.toString(), JSON))
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Response failed: " + response.code());
            }
        }
    }
    
    // ==================== PlatformAdapter Interface Methods ====================
    
    @Override
    public String getName() {
        return getPlatformName();
    }
    
    @Override
    public void start() throws Exception {
        // Webhook is set externally
    }
    
    @Override
    public void stop() throws Exception {
        // Cleanup if needed
    }
    
    @Override
    public boolean isConnected() {
        return botToken != null && !botToken.isEmpty();
    }
    
    @Override
    public void setAgent(com.nousresearch.hermes.agent.AIAgent agent) {
        // Agent is set externally
    }
}
