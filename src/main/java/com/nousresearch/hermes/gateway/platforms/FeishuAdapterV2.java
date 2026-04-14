package com.nousresearch.hermes.gateway.platforms;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
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
    private static final String API_BASE = "https://open.feishu.cn/open-apis";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
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
    public GatewayServer.IncomingMessage parseWebhook(JSONObject payload) {
        try {
            if (payload.containsKey("challenge")) {
                return null;
            }
            
            JSONObject event = payload.getJSONObject("event");
            if (event == null) return null;
            
            String messageType = event.getString("message_type");
            String content = event.getString("content");
            
            if ("text".equals(messageType)) {
                JSONObject contentNode = JSON.parseObject(content);
                content = contentNode.getString("text");
            }
            
            JSONObject sender = event.getJSONObject("sender");
            String senderId = sender != null && sender.getJSONObject("sender_id") != null 
                ? sender.getJSONObject("sender_id").getString("open_id") 
                : "";
            
            return new GatewayServer.IncomingMessage(
                event.getString("message_id"),
                event.getString("chat_id"),
                senderId,
                content,
                System.currentTimeMillis(),
                "group".equals(event.getString("chat_type"))
            );
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendMessage(String channel, String content) throws Exception {
        ensureToken();
        
        JSONObject body = new JSONObject();
        body.put("receive_id", channel);
        
        JSONObject contentNode = new JSONObject();
        contentNode.put("text", content);
        body.put("content", contentNode.toString());
        body.put("msg_type", "text");
        
        Request request = new Request.Builder()
            .url(API_BASE + "/im/v1/messages?receive_id_type=chat_id")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
        
        JSONObject body = new JSONObject();
        JSONObject contentNode = new JSONObject();
        contentNode.put("text", content);
        body.put("content", contentNode.toString());
        body.put("msg_type", "text");
        
        Request request = new Request.Builder()
            .url(API_BASE + "/im/v1/messages/" + messageId + "/reply")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
        
        JSONObject body = new JSONObject();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        
        Request request = new Request.Builder()
            .url(API_BASE + "/auth/v3/tenant_access_token/internal")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            JSONObject result = JSON.parseObject(response.body().string());
            accessToken = result.getString("tenant_access_token");
            tokenExpiry = System.currentTimeMillis() + (result.getIntValue("expire", 7200) * 1000L);
        }
    }
}
