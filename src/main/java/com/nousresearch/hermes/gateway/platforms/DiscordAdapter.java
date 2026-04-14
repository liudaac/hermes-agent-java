package com.nousresearch.hermes.gateway.platforms;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
    private static final String API_BASE = "https://discord.com/api/v10";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
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
    public GatewayServer.IncomingMessage parseWebhook(JSONObject payload) {
        try {
            // Handle ping
            if (payload.getIntValue("type") == 1) {
                return null;
            }
            
            // Handle application commands
            if (payload.getIntValue("type") == 2) {
                JSONObject data = payload.getJSONObject("data");
                String content = "/" + data.getString("name");
                
                return new GatewayServer.IncomingMessage(
                    payload.getString("id"),
                    payload.getString("channel_id"),
                    payload.getJSONObject("member").getJSONObject("user").getString("id"),
                    content,
                    System.currentTimeMillis(),
                    payload.containsKey("guild_id")
                );
            }
            
            // Handle messages
            String content = payload.getString("content");
            
            return new GatewayServer.IncomingMessage(
                payload.getString("id"),
                payload.getString("channel_id"),
                payload.getJSONObject("author").getString("id"),
                content,
                System.currentTimeMillis(),
                payload.containsKey("guild_id")
            );
            
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendMessage(String channel, String content) throws Exception {
        JSONObject body = new JSONObject();
        body.put("content", content);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channel + "/messages")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
        JSONObject body = new JSONObject();
        body.put("content", content);
        
        JSONObject reference = new JSONObject();
        reference.put("message_id", messageId);
        body.put("message_reference", reference);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channel + "/messages")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
        JSONObject body = new JSONObject();
        
        JSONObject embed = new JSONObject();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("color", color);
        
        JSONArray embeds = new JSONArray();
        embeds.add(embed);
        body.put("embeds", embeds);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/channels/" + channel + "/messages")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
        JSONObject body = new JSONObject();
        body.put("type", 4); // Channel message with source
        
        JSONObject data = new JSONObject();
        data.put("content", content);
        body.put("data", data);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/interactions/" + interactionId + "/" + interactionToken + "/callback")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
