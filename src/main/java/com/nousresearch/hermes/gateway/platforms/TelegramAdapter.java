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
 * Telegram platform adapter.
 * Supports: messages, replies, markdown, inline keyboards.
 */
public class TelegramAdapter implements GatewayServer.PlatformAdapter, com.nousresearch.hermes.gateway.platforms.PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TelegramAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
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
    public GatewayServer.IncomingMessage parseWebhook(JsonNode payload) {
        try {
            JsonNode message = payload.path("message");
            if (message.isMissingNode()) {
                message = payload.path("edited_message");
            }
            if (message.isMissingNode()) {
                return null;
            }
            
            String text = message.path("text").asText();
            if (text.isEmpty()) {
                text = message.path("caption").asText();
            }
            
            JsonNode chat = message.path("chat");
            JsonNode from = message.path("from");
            
            return new GatewayServer.IncomingMessage(
                String.valueOf(message.path("message_id").asLong()),
                String.valueOf(chat.path("id").asLong()),
                String.valueOf(from.path("id").asLong()),
                text,
                System.currentTimeMillis(),
                "group".equals(chat.path("type").asText()) || 
                "supergroup".equals(chat.path("type").asText())
            );
            
        } catch (Exception e) {
            logger.error("Parse error: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void sendMessage(String channel, String content) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", channel);
        body.put("text", content);
        body.put("parse_mode", "Markdown");
        body.put("disable_web_page_preview", false);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(RequestBody.create(body.toString(), JSON))
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
        body.put("chat_id", channel);
        body.put("text", content);
        body.put("reply_to_message_id", Integer.parseInt(messageId));
        body.put("parse_mode", "Markdown");
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(RequestBody.create(body.toString(), JSON))
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
    public void sendMessageWithButtons(String channel, String text, JsonNode buttons) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", channel);
        body.put("text", text);
        body.put("parse_mode", "Markdown");
        body.set("reply_markup", buttons);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(RequestBody.create(body.toString(), JSON))
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
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", channel);
        body.put("message_id", Integer.parseInt(messageId));
        body.put("text", newText);
        body.put("parse_mode", "Markdown");
        
        Request request = new Request.Builder()
            .url(apiUrl + "/editMessageText")
            .post(RequestBody.create(body.toString(), JSON))
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
        ObjectNode body = mapper.createObjectNode();
        body.put("url", webhookUrl);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/setWebhook")
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            JsonNode result = mapper.readTree(response.body().string());
            return result.path("ok").asBoolean();
        }
    }
    
    /**
     * Delete webhook.
     */
    public boolean deleteWebhook() throws Exception {
        Request request = new Request.Builder()
            .url(apiUrl + "/deleteWebhook")
            .post(RequestBody.create("{}", JSON))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            JsonNode result = mapper.readTree(response.body().string());
            return result.path("ok").asBoolean();
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
