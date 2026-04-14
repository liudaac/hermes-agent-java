package com.nousresearch.hermes.gateway.platforms;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Feishu (Lark) platform adapter.
 * Handles Feishu bot messages.
 */
public class FeishuAdapter implements PlatformAdapter, com.nousresearch.hermes.gateway.GatewayServer.PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(FeishuAdapter.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    
    private final HermesConfig config;
    private final OkHttpClient httpClient;
    private AIAgent agent;
    private volatile boolean connected;
    private volatile boolean running;
    
    private String appId;
    private String appSecret;
    private String tenantAccessToken;
    private long tokenExpiry;
    
    public FeishuAdapter(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "feishu";
    }
    
    @Override
    public void start() throws Exception {
        logger.info("Starting Feishu adapter...");
        
        // Load credentials from environment
        appId = System.getenv("FEISHU_APP_ID");
        appSecret = System.getenv("FEISHU_APP_SECRET");
        
        if (appId == null || appSecret == null) {
            throw new IllegalStateException("FEISHU_APP_ID and FEISHU_APP_SECRET must be set");
        }
        
        // Get tenant access token
        refreshToken();
        
        running = true;
        connected = true;
        
        logger.info("Feishu adapter started");
        
        // In a real implementation, this would:
        // 1. Set up webhook endpoint
        // 2. Start listening for events
        // 3. Handle message callbacks
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Stopping Feishu adapter...");
        running = false;
        connected = false;
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public void sendMessage(String chatId, String message) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("receive_id", chatId);
        JSONObject content = new JSONObject();
        content.put("text", message);
        requestBody.put("content", content.toString());
        requestBody.put("msg_type", "text");
        
        RequestBody body = RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/im/v1/messages?receive_id_type=chat_id")
            .post(body)
            .header("Authorization", "Bearer " + tenantAccessToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new IOException("Failed to send message: " + response.code() + " - " + errorBody);
            }
            
            logger.debug("Message sent to Feishu chat: {}", chatId);
        }
    }
    
    @Override
    public void setAgent(AIAgent agent) {
        this.agent = agent;
    }
    
    /**
     * Handle incoming Feishu event.
     * This would be called by the webhook handler.
     */
    public void handleEvent(String eventData) {
        try {
            JSONObject event = JSON.parseObject(eventData);
            String eventType = event.getJSONObject("header").getString("event_type");
            
            switch (eventType) {
                case "im.message.receive_v1":
                    handleMessage(event.getJSONObject("event"));
                    break;
                case "url_verification":
                    // Handle challenge
                    break;
                default:
                    logger.debug("Unhandled event type: {}", eventType);
            }
            
        } catch (Exception e) {
            logger.error("Failed to handle Feishu event: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Handle incoming message.
     */
    private void handleMessage(JSONObject event) {
        try {
            String messageType = event.getJSONObject("message").getString("message_type");
            
            if (!"text".equals(messageType)) {
                logger.debug("Ignoring non-text message");
                return;
            }
            
            String chatId = event.getJSONObject("message").getString("chat_id");
            String sender = event.getJSONObject("sender").getJSONObject("sender_id").getString("open_id");
            String content = event.getJSONObject("message").getString("content");
            
            // Parse content JSON
            JSONObject contentNode = JSON.parseObject(content);
            String text = contentNode.getString("text");
            
            logger.info("Received message from {}: {}", sender, text);
            
            // Process with agent
            if (agent != null) {
                // In real implementation, this would:
                // 1. Create a conversation context
                // 2. Process through agent
                // 3. Send response back
                
                // For now, just echo
                sendMessage(chatId, "Received: " + text);
            }
            
        } catch (Exception e) {
            logger.error("Failed to handle message: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Verify event signature.
     */
    public boolean verifySignature(String timestamp, String nonce, String body, String signature) {
        try {
            String encryptKey = System.getenv("FEISHU_ENCRYPT_KEY");
            if (encryptKey == null) {
                return true; // Skip verification if no key
            }
            
            String signStr = timestamp + nonce + encryptKey + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(encryptKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(signStr.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(hash);
            
            return expectedSignature.equals(signature);
            
        } catch (Exception e) {
            logger.error("Failed to verify signature: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Refresh tenant access token.
     */
    private void refreshToken() throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("app_id", appId);
        requestBody.put("app_secret", appSecret);
        
        RequestBody body = RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/auth/v3/tenant_access_token/internal")
            .post(body)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get token: " + response.code());
            }
            
            JSONObject result = JSON.parseObject(response.body().string());
            tenantAccessToken = result.getString("tenant_access_token");
            int expire = result.getIntValue("expire", 7200);
            tokenExpiry = System.currentTimeMillis() + (expire - 300) * 1000L;
            
            logger.debug("Token refreshed, expires in {} seconds", expire);
        }
    }
    
    private void ensureTokenValid() throws Exception {
        if (System.currentTimeMillis() > tokenExpiry) {
            refreshToken();
        }
    }
    
    // ==================== GatewayServer.PlatformAdapter Methods ====================
    
    @Override
    public String getPlatformName() {
        return "feishu";
    }
    
    @Override
    public com.nousresearch.hermes.gateway.GatewayServer.IncomingMessage parseWebhook(JSONObject payload) {
        try {
            JSONObject event = payload.getJSONObject("event");
            if (event == null) {
                return null;
            }
            
            String eventType = event.getString("type");
            if (!"im.message.receive_v1".equals(eventType)) {
                return null;
            }
            
            JSONObject message = event.getJSONObject("message");
            String messageId = message.getString("message_id");
            String chatId = message.getString("chat_id");
            String sender = message.getJSONObject("sender").getJSONObject("sender_id").getString("open_id");
            String content = message.getString("content");
            
            // Parse content (it's a JSON string)
            JSONObject contentNode = JSON.parseObject(content);
            String text = contentNode.getString("text");
            
            return new com.nousresearch.hermes.gateway.GatewayServer.IncomingMessage(
                messageId, chatId, sender, text, System.currentTimeMillis(), false
            );
            
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendReply(String channel, String messageId, String content) throws Exception {
        // Feishu doesn't have a direct reply API, use sendMessage
        sendMessage(channel, content);
    }
}
