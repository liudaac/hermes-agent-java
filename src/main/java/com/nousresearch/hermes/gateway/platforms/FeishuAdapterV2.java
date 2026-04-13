package com.nousresearch.hermes.gateway.platforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nousresearch.hermes.gateway.GatewayServer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Complete Feishu platform adapter.
 */
public class FeishuAdapterV2 implements GatewayServer.PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(FeishuAdapterV2.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String API_BASE = "https://open.feishu.cn/open-apis";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String appId;
    private final String appSecret;
    private String accessToken;
    private long tokenExpiry;
    
    public FeishuAdapterV2() {
        this.appId = System.getenv("FEISHU_APP_ID");
        this.appSecret = System.getenv("FEISHU_APP_SECRET");
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getPlatformName() {
        return "feishu";
    }
    
    @Override
    public GatewayServer.IncomingMessage parseWebhook(JsonNode payload) {
        try {
            if (payload.has("challenge")) {
                return null;
            }
            
            JsonNode event = payload.path("event");
            if (event.isMissingNode()) return null;
            
            String messageType = event.path("message_type").asText();
            String content = event.path("content").asText();
            
            if ("text".equals(messageType)) {
                JsonNode contentNode = mapper.readTree(content);
                content = contentNode.path("text").asText();
            }
            
            return new GatewayServer.IncomingMessage(
                event.path("message_id").asText(),
                event.path("chat_id").asText(),
                event.path("sender").path("sender_id").path("open_id").asText(),
                content,
                System.currentTimeMillis(),
                "group".equals(event.path("chat_type").asText())
            );
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendMessage(String channel, String content) throws Exception {
        ensureToken();
        
        ObjectNode body = mapper.createObjectNode();
        body.put("receive_id", channel);
        
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("text", content);
        body.put("content", contentNode.toString());
        body.put("msg_type", "text");
        
        Request request = new Request.Builder()
            .url(API_BASE + "/im/v1/messages?receive_id_type=chat_id")
            .post(RequestBody.create(body.toString(), JSON))
            .header("Authorization", "Bearer " + accessToken)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Send failed: " + response.code());
            }
        }
    }
    
    @Override
    public void sendReply(String channel, String messageId, String content) throws Exception {
        ensureToken();
        
        ObjectNode body = mapper.createObjectNode();
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("text", content);
        body.put("content", contentNode.toString());
        body.put("msg_type", "text");
        
        Request request = new Request.Builder()
            .url(API_BASE + "/im/v1/messages/" + messageId + "/reply")
            .post(RequestBody.create(body.toString(), JSON))
            .header("Authorization", "Bearer " + accessToken)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Reply failed: " + response.code());
            }
        }
    }
    
    private synchronized void ensureToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiry - 60000) {
            return;
        }
        
        ObjectNode body = mapper.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/auth/v3/tenant_access_token/internal")
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            JsonNode result = mapper.readTree(response.body().string());
            accessToken = result.path("tenant_access_token").asText();
            tokenExpiry = System.currentTimeMillis() + (result.path("expire").asInt(7200) * 1000L);
        }
    }
}
