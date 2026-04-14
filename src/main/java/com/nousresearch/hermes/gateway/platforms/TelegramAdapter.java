package com.nousresearch.hermes.gateway.platforms;

import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.gateway.GatewayServer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Telegram platform adapter.
 * Supports: messages, replies, markdown, inline keyboards.
 */
public class TelegramAdapter implements GatewayServer.PlatformAdapter, com.nousresearch.hermes.gateway.platforms.PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TelegramAdapter.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String botToken;
    private final String apiUrl;
    
    public TelegramAdapter() {
        this.botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        this.apiUrl = API_BASE + botToken;
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    public TelegramAdapter(com.nousresearch.hermes.config.HermesConfig config) {
        this(); // Delegate to default constructor
    }
    
    @Override
    public String getPlatformName() {
        return "telegram";
    }
    
    @Override
    public GatewayServer.IncomingMessage parseWebhook(JSONObject payload) {
        try {
            JSONObject message = payload.getJSONObject("message");
            if (message == null) {
                message = payload.getJSONObject("edited_message");
            }
            if (message == null) {
                return null;
            }
            
            String text = message.getString("text");
            if (text == null || text.isEmpty()) {
                text = message.getString("caption");
            }
            
            JSONObject chat = message.getJSONObject("chat");
            JSONObject from = message.getJSONObject("from");
            
            String chatType = chat != null ? chat.getString("type") : "";
            
            return new GatewayServer.IncomingMessage(
                String.valueOf(message.getLongValue("message_id")),
                String.valueOf(chat != null ? chat.getLongValue("id") : 0),
                String.valueOf(from != null ? from.getLongValue("id") : 0),
                text,
                System.currentTimeMillis(),
                "group".equals(chatType) || "supergroup".equals(chatType)
            );
            
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendMessage(String channel, String content) throws Exception {
        JSONObject body = new JSONObject();
        body.put("chat_id", channel);
        body.put("text", content);
        body.put("parse_mode", "Markdown");
        body.put("disable_web_page_preview", false);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
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
        body.put("chat_id", channel);
        body.put("text", content);
        body.put("reply_to_message_id", Integer.parseInt(messageId));
        body.put("parse_mode", "Markdown");
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Reply failed: " + response.code());
            }
        }
    }
    
    /**
     * Send message with inline keyboard.
     */
    public void sendMessageWithButtons(String channel, String text, JSONObject buttons) throws Exception {
        JSONObject body = new JSONObject();
        body.put("chat_id", channel);
        body.put("text", text);
        body.put("parse_mode", "Markdown");
        body.put("reply_markup", buttons);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Send failed: " + response.code());
            }
        }
    }
    
    /**
     * Edit message.
     */
    public void editMessage(String channel, String messageId, String newText) throws Exception {
        JSONObject body = new JSONObject();
        body.put("chat_id", channel);
        body.put("message_id", Integer.parseInt(messageId));
        body.put("text", newText);
        body.put("parse_mode", "Markdown");
        
        Request request = new Request.Builder()
            .url(apiUrl + "/editMessageText")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Edit failed: " + response.code());
            }
        }
    }
    
    /**
     * Set webhook.
     */
    public boolean setWebhook(String webhookUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("url", webhookUrl);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/setWebhook")
            .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            JSONObject result = com.alibaba.fastjson2.JSON.parseObject(response.body().string());
            return result.getBooleanValue("ok");
        }
    }
    
    /**
     * Delete webhook.
     */
    public boolean deleteWebhook() throws Exception {
        Request request = new Request.Builder()
            .url(apiUrl + "/deleteWebhook")
            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            JSONObject result = com.alibaba.fastjson2.JSON.parseObject(response.body().string());
            return result.getBooleanValue("ok");
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
