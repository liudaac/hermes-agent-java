package com.nousresearch.hermes.gateway.platforms.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.gateway.platforms.PlatformAdapter;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * QQ Bot platform adapter using the Official QQ Bot API (v2).
 * Mirrors Python gateway/platforms/qqbot/adapter.py
 * 
 * Configuration in config.yaml:
 *   platforms:
 *     qq:
 *       enabled: true
 *       extra:
 *         app_id: "your-app-id"
 *         client_secret: "your-secret"
 *         markdown_support: true
 *         dm_policy: "open"
 *         group_policy: "open"
 */
public class QQBotAdapter implements PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(QQBotAdapter.class);
    
    private static final String API_BASE_URL = QQBotConstants.API_BASE_URL;
    private static final String SANDBOX_URL = QQBotConstants.SANDBOX_API_URL;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private AIAgent agent;
    private volatile boolean connected;
    private volatile boolean running;
    
    private String appId;
    private String clientSecret;
    private String accessToken;
    private String baseUrl;
    private boolean useSandbox;
    private boolean markdownSupport;
    private String dmPolicy;
    private String groupPolicy;
    
    private long tokenExpiry;
    private WebSocket webSocket;
    private int sequenceNumber;
    private String sessionId;
    
    public QQBotAdapter() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "qq";
    }
    
    @Override
    public void start() throws Exception {
        logger.info("Starting QQ Bot adapter...");
        
        // Load credentials from environment
        appId = System.getenv("QQ_APP_ID");
        clientSecret = System.getenv("QQ_CLIENT_SECRET");
        
        if (appId == null || clientSecret == null) {
            throw new IllegalStateException("QQ_APP_ID and QQ_CLIENT_SECRET must be set");
        }
        
        // Configuration
        useSandbox = "true".equals(System.getenv("QQ_SANDBOX"));
        markdownSupport = "true".equals(System.getenv("QQ_MARKDOWN_SUPPORT"));
        dmPolicy = System.getenv().getOrDefault("QQ_DM_POLICY", "open");
        groupPolicy = System.getenv().getOrDefault("QQ_GROUP_POLICY", "open");
        
        baseUrl = useSandbox ? SANDBOX_URL : API_BASE_URL;
        
        // Get access token
        refreshToken();
        
        // Get WebSocket gateway
        String wsUrl = getGatewayUrl();
        
        // Connect to WebSocket
        connectWebSocket(wsUrl);
        
        running = true;
        connected = true;
        
        logger.info("QQ Bot adapter started (sandbox: {}, markdown: {})", useSandbox, markdownSupport);
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Stopping QQ Bot adapter...");
        running = false;
        connected = false;
        
        if (webSocket != null) {
            webSocket.close(1000, "Stopping adapter");
            webSocket = null;
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected && webSocket != null;
    }
    
    @Override
    public void setAgent(AIAgent agent) {
        this.agent = agent;
    }
    
    @Override
    public void sendMessage(String chatId, String message) throws Exception {
        ensureTokenValid();
        
        // Determine message type
        int msgType = QQBotConstants.MSG_TYPE_TEXT;
        if (markdownSupport && isMarkdown(message)) {
            msgType = QQBotConstants.MSG_TYPE_MARKDOWN;
        }
        
        JSONObject requestBody = new JSONObject();
        
        // Check if it's a group or DM
        if (chatId.startsWith("GROUP_")) {
            // Group message
            String groupOpenid = chatId.substring(6);
            requestBody.put("group_openid", groupOpenid);
            requestBody.put("msg_type", msgType);
            
            if (msgType == QQBotConstants.MSG_TYPE_MARKDOWN) {
                requestBody.put("markdown", buildMarkdownContent(message));
            } else {
                requestBody.put("content", buildTextContent(message));
            }
            
            sendPost("/v2/groups/" + groupOpenid + "/messages", requestBody);
        } else if (chatId.startsWith("C2C_")) {
            // C2C message
            String userOpenid = chatId.substring(4);
            requestBody.put("openid", userOpenid);
            requestBody.put("msg_type", msgType);
            
            if (msgType == QQBotConstants.MSG_TYPE_MARKDOWN) {
                requestBody.put("markdown", buildMarkdownContent(message));
            } else {
                requestBody.put("content", buildTextContent(message));
            }
            
            sendPost("/v2/users/" + userOpenid + "/messages", requestBody);
        } else {
            // Direct message (DM)
            requestBody.put("openid", chatId);
            requestBody.put("msg_type", msgType);
            
            if (msgType == QQBotConstants.MSG_TYPE_MARKDOWN) {
                requestBody.put("markdown", buildMarkdownContent(message));
            } else {
                requestBody.put("content", buildTextContent(message));
            }
            
            sendPost("/v2/users/" + chatId + "/messages", requestBody);
        }
    }
    
    /**
     * Send text message to channel.
     */
    public void sendChannelMessage(String channelId, String message) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("channel_id", channelId);
        requestBody.put("content", message);
        
        sendPost("/channels/" + channelId + "/messages", requestBody);
    }
    
    /**
     * Send image message.
     */
    public void sendImageMessage(String chatId, String imageUrl) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        JSONObject imageContent = new JSONObject();
        imageContent.put("url", imageUrl);
        
        // Check if it's a group or DM
        if (chatId.startsWith("GROUP_")) {
            String groupOpenid = chatId.substring(6);
            requestBody.put("group_openid", groupOpenid);
            requestBody.put("msg_type", QQBotConstants.MSG_TYPE_IMAGE);
            requestBody.put("image", imageContent);
            sendPost("/v2/groups/" + groupOpenid + "/messages", requestBody);
        } else {
            requestBody.put("openid", chatId.startsWith("C2C_") ? chatId.substring(4) : chatId);
            requestBody.put("msg_type", QQBotConstants.MSG_TYPE_IMAGE);
            requestBody.put("image", imageContent);
            sendPost("/v2/users/" + (chatId.startsWith("C2C_") ? chatId.substring(4) : chatId) + "/messages", requestBody);
        }
    }
    
    /**
     * Reply to a message.
     */
    public void replyToMessage(String chatId, String messageId, String content) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("msg_id", messageId);
        requestBody.put("content", buildTextContent(content));
        
        // Determine endpoint based on chat type
        if (chatId.startsWith("GROUP_")) {
            String groupOpenid = chatId.substring(6);
            sendPost("/v2/groups/" + groupOpenid + "/messages", requestBody);
        } else {
            String userOpenid = chatId.startsWith("C2C_") ? chatId.substring(4) : chatId;
            sendPost("/v2/users/" + userOpenid + "/messages", requestBody);
        }
    }
    
    /**
     * Acknowledge an interaction (button click, etc).
     */
    public void acknowledgeInteraction(String interactionId, int code) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("code", code);
        
        sendPut("/interactions/" + interactionId, requestBody);
    }
    
    // Private helper methods
    
    private void refreshToken() throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("appId", appId);
        requestBody.put("clientSecret", clientSecret);
        
        Request request = new Request.Builder()
            .url(baseUrl + "/app/getAppAccessToken")
            .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
            .headers(Headers.of(QQBotUtils.getApiHeaders()))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get access token: " + response);
            }
            
            String responseBody = response.body().string();
            JSONObject json = JSON.parseObject(responseBody);
            
            accessToken = json.getString("access_token");
            int expiresIn = json.getIntValue("expires_in", 7200);
            tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L) - 60000; // 1 min buffer
            
            logger.debug("QQ Bot token refreshed, expires in {} seconds", expiresIn);
        }
    }
    
    private void ensureTokenValid() throws Exception {
        if (System.currentTimeMillis() >= tokenExpiry) {
            refreshToken();
        }
    }
    
    private String getGatewayUrl() throws Exception {
        ensureTokenValid();
        
        Request request = new Request.Builder()
            .url(baseUrl + "/gateway")
            .get()
            .header("Authorization", "QQBot " + appId + "." + accessToken)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get gateway URL: " + response);
            }
            
            String responseBody = response.body().string();
            JSONObject json = JSON.parseObject(responseBody);
            
            return json.getString("url");
        }
    }
    
    private void connectWebSocket(String wsUrl) {
        Request request = new Request.Builder()
            .url(wsUrl)
            .build();
        
        webSocket = httpClient.newWebSocket(request, new QQWebSocketListener());
    }
    
    private String sendPost(String path, JSONObject body) throws Exception {
        ensureTokenValid();
        
        Request request = new Request.Builder()
            .url(baseUrl + path)
            .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
            .header("Authorization", "QQBot " + appId + "." + accessToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("QQ Bot API error: {} - {}", response.code(), responseBody);
                throw new IOException("API error " + response.code() + ": " + responseBody);
            }
            
            return responseBody;
        }
    }
    
    private String sendPut(String path, JSONObject body) throws Exception {
        ensureTokenValid();
        
        Request request = new Request.Builder()
            .url(baseUrl + path)
            .put(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
            .header("Authorization", "QQBot " + appId + "." + accessToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("QQ Bot API error: {} - {}", response.code(), responseBody);
                throw new IOException("API error " + response.code() + ": " + responseBody);
            }
            
            return responseBody;
        }
    }
    
    private boolean isMarkdown(String text) {
        return text != null && (
            text.contains("**") ||
            text.contains("*") ||
            text.contains("`") ||
            text.contains("[") ||
            text.contains("#") ||
            text.contains(">")
        );
    }
    
    private JSONObject buildTextContent(String text) {
        JSONObject content = new JSONObject();
        
        // Parse mentions
        JSONArray mentions = new JSONArray();
        StringBuilder parsedText = new StringBuilder();
        
        // Simple mention parsing: @username or @everyone
        String[] parts = text.split("@");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                String mention = parts[i];
                int spaceIdx = mention.indexOf(' ');
                if (spaceIdx > 0) {
                    String username = mention.substring(0, spaceIdx);
                    if ("everyone".equals(username) || "here".equals(username)) {
                        JSONObject mentionObj = new JSONObject();
                        mentionObj.put("type", "everyone");
                        mentions.add(mentionObj);
                    }
                    parsedText.append("@").append(username).append(" ").append(mention.substring(spaceIdx + 1));
                } else {
                    parsedText.append("@").append(mention);
                }
            } else {
                parsedText.append(parts[i]);
            }
        }
        
        content.put("text", parsedText.toString().trim());
        if (!mentions.isEmpty()) {
            content.put("mentions", mentions);
        }
        
        return content;
    }
    
    private JSONObject buildMarkdownContent(String markdown) {
        JSONObject content = new JSONObject();
        content.put("content", markdown);
        return content;
    }
    
    /**
     * WebSocket listener for QQ Bot Gateway.
     */
    private class QQWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            logger.info("QQ Bot WebSocket connected");
            
            // Send identify payload
            JSONObject identify = new JSONObject();
            identify.put("op", QQBotConstants.OP_IDENTIFY);
            
            JSONObject data = new JSONObject();
            data.put("token", "QQBot " + appId + "." + accessToken);
            data.put("intents", QQBotConstants.DEFAULT_INTENTS);
            data.put("shard", new JSONArray() {{ add(0); add(1); }});
            
            JSONObject properties = new JSONObject();
            properties.put("$os", System.getProperty("os.name"));
            properties.put("$browser", "HermesJava");
            properties.put("$device", "HermesJava");
            data.put("properties", properties);
            
            identify.put("d", data);
            
            webSocket.send(identify.toJSONString());
        }
        
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject payload = JSON.parseObject(text);
                int op = payload.getIntValue("op");
                
                switch (op) {
                    case QQBotConstants.OP_DISPATCH:
                        handleDispatch(payload);
                        break;
                    case QQBotConstants.OP_HELLO:
                        handleHello(payload);
                        break;
                    case QQBotConstants.OP_HEARTBEAT_ACK:
                        // Heartbeat acknowledged
                        break;
                    case QQBotConstants.OP_RECONNECT:
                        handleReconnect();
                        break;
                    case QQBotConstants.OP_INVALID_SESSION:
                        handleInvalidSession();
                        break;
                    default:
                        logger.debug("Unknown op code: {}", op);
                }
            } catch (Exception e) {
                logger.error("Error handling WebSocket message", e);
            }
        }
        
        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            logger.info("QQ Bot WebSocket closing: {} - {}", code, reason);
            connected = false;
        }
        
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            logger.info("QQ Bot WebSocket closed: {} - {}", code, reason);
            connected = false;
            
            // Attempt to reconnect if still running
            if (running) {
                try {
                    Thread.sleep(5000);
                    String wsUrl = getGatewayUrl();
                    connectWebSocket(wsUrl);
                } catch (Exception e) {
                    logger.error("Failed to reconnect", e);
                }
            }
        }
        
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            logger.error("QQ Bot WebSocket error", t);
            connected = false;
        }
        
        private void handleDispatch(JSONObject payload) {
            sequenceNumber = payload.getIntValue("s");
            String eventType = payload.getString("t");
            JSONObject data = payload.getJSONObject("d");
            
            logger.debug("Received event: {}", eventType);
            
            switch (eventType) {
                case QQBotConstants.EVENT_READY:
                    sessionId = data.getString("session_id");
                    JSONObject user = data.getJSONObject("user");
                    String botName = user != null ? user.getString("username") : "Unknown";
                    logger.info("QQ Bot ready: {} (session: {})", botName, sessionId);
                    break;
                case QQBotConstants.EVENT_AT_MESSAGE_CREATE:
                case QQBotConstants.EVENT_GROUP_AT_MESSAGE_CREATE:
                    handleAtMessage(data);
                    break;
                case QQBotConstants.EVENT_DIRECT_MESSAGE_CREATE:
                case QQBotConstants.EVENT_C2C_MESSAGE_CREATE:
                    handleDirectMessage(data);
                    break;
                case QQBotConstants.EVENT_MESSAGE_CREATE:
                    handleChannelMessage(data);
                    break;
                default:
                    logger.debug("Unhandled event type: {}", eventType);
            }
        }
        
        private void handleHello(JSONObject payload) {
            JSONObject data = payload.getJSONObject("d");
            int heartbeatInterval = data.getIntValue("heartbeat_interval");
            
            // Start heartbeat
            startHeartbeat(heartbeatInterval);
        }
        
        private void handleReconnect() {
            logger.info("Server requested reconnect");
            connected = false;
            
            if (running) {
                try {
                    Thread.sleep(1000);
                    String wsUrl = getGatewayUrl();
                    connectWebSocket(wsUrl);
                } catch (Exception e) {
                    logger.error("Failed to reconnect", e);
                }
            }
        }
        
        private void handleInvalidSession() {
            logger.error("Invalid session, reconnecting...");
            sessionId = null;
            sequenceNumber = 0;
            
            if (running) {
                try {
                    Thread.sleep(5000);
                    String wsUrl = getGatewayUrl();
                    connectWebSocket(wsUrl);
                } catch (Exception e) {
                    logger.error("Failed to reconnect", e);
                }
            }
        }
        
        private void handleAtMessage(JSONObject data) {
            String content = data.getString("content");
            String messageId = data.getString("id");
            JSONObject author = data.getJSONObject("author");
            JSONObject member = data.getJSONObject("member");
            
            // Remove bot mention from content
            if (content != null) {
                // Parse mentions
                JSONArray mentions = data.getJSONArray("mentions");
                if (mentions != null) {
                    for (int i = 0; i < mentions.size(); i++) {
                        JSONObject mention = mentions.getJSONObject(i);
                        String mentionId = mention.getString("id");
                        if (appId.equals(mentionId)) {
                            // Remove the mention from content
                            content = content.replace("<@!" + mentionId + ">", "").trim();
                        }
                    }
                }
            }
            
            // Determine chat ID
            String chatId;
            JSONObject groupInfo = data.getJSONObject("group_openid") != null 
                ? data.getJSONObject("group_openid") 
                : data;
            String groupOpenid = data.getString("group_openid");
            
            if (groupOpenid != null) {
                chatId = "GROUP_" + groupOpenid;
            } else {
                String userId = author != null ? author.getString("id") : data.getString("author_id");
                chatId = "C2C_" + userId;
            }
            
            if (agent != null && content != null && !content.isEmpty()) {
                try {
                    agent.processMessage(chatId, content);
                } catch (Exception e) {
                    logger.error("Error processing message", e);
                    try {
                        replyToMessage(chatId, messageId, "抱歉，处理消息时出错: " + e.getMessage());
                    } catch (Exception ex) {
                        logger.error("Error sending error message", ex);
                    }
                }
            }
        }
        
        private void handleDirectMessage(JSONObject data) {
            String content = data.getString("content");
            String messageId = data.getString("id");
            JSONObject author = data.getJSONObject("author");
            String userId = author != null ? author.getString("id") : data.getString("author_id");
            String chatId = "C2C_" + userId;
            
            if (agent != null && content != null && !content.isEmpty()) {
                try {
                    agent.processMessage(chatId, content);
                } catch (Exception e) {
                    logger.error("Error processing direct message", e);
                    try {
                        replyToMessage(chatId, messageId, "抱歉，处理消息时出错: " + e.getMessage());
                    } catch (Exception ex) {
                        logger.error("Error sending error message", ex);
                    }
                }
            }
        }
        
        private void handleChannelMessage(JSONObject data) {
            String content = data.getString("content");
            String channelId = data.getString("channel_id");
            String messageId = data.getString("id");
            
            if (agent != null && content != null && !content.isEmpty()) {
                try {
                    agent.processMessage(channelId, content);
                } catch (Exception e) {
                    logger.error("Error processing channel message", e);
                }
            }
        }
        
        private void startHeartbeat(int interval) {
            Thread heartbeatThread = new Thread(() -> {
                while (running && connected) {
                    try {
                        Thread.sleep(interval);
                        
                        if (webSocket != null && connected) {
                            JSONObject heartbeat = new JSONObject();
                            heartbeat.put("op", QQBotConstants.OP_HEARTBEAT);
                            heartbeat.put("d", sequenceNumber > 0 ? sequenceNumber : null);
                            webSocket.send(heartbeat.toJSONString());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            heartbeatThread.setDaemon(true);
            heartbeatThread.setName("QQBot-Heartbeat");
            heartbeatThread.start();
        }
    }
}
