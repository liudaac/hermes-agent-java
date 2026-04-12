package com.nousresearch.hermes.gateway.platforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Feishu (Lark) platform adapter.
 * Handles Feishu bot messages.
 */
public class FeishuAdapter implements PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(FeishuAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
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
        
        Map<String, Object> requestBody = Map.of(
            "receive_id", chatId,
            "content", Map.of("text", message),
            "msg_type", "text"
        );
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
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
            JsonNode event = mapper.readTree(eventData);
            String eventType = event.path("header").path("event_type").asText();
            
            switch (eventType) {
                case "im.message.receive_v1":
                    handleMessage(event.path("event"));
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
    private void handleMessage(JsonNode event) {
        try {
            String messageType = event.path("message").path("message_type").asText();
            
            if (!"text".equals(messageType)) {
                logger.debug("Ignoring non-text message");
                return;
            }
            
            String chatId = event.path("message").path("chat_id").asText();
            String sender = event.path("sender").path("sender_id").path("open_id").asText();
            String content = event.path("message").path("content").asText();
            
            // Parse content JSON
            JsonNode contentNode = mapper.readTree(content);
            String text = contentNode.path("text").asText();
            
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
        Map<String, Object> requestBody = Map.of(
            "app_id", appId,
            "app_secret", appSecret
        );
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/auth/v3/tenant_access_token/internal")
            .post(body)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get token: " + response.code());
            }
            
            JsonNode result = mapper.readTree(response.body().string());
            tenantAccessToken = result.path("tenant_access_token").asText();
            int expire = result.path("expire").asInt(7200);
            tokenExpiry = System.currentTimeMillis() + (expire - 300) * 1000L;
            
            logger.debug("Token refreshed, expires in {} seconds", expire);
        }
    }
    
    private void ensureTokenValid() throws Exception {
        if (System.currentTimeMillis() > tokenExpiry) {
            refreshToken();
        }
    }
}
