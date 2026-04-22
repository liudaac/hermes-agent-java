package com.nousresearch.hermes.gateway.platforms.feishu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.gateway.platforms.PlatformAdapter;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Feishu (Lark) Comment platform adapter.
 * Handles comment-based interactions in Feishu documents and sheets.
 * Mirrors Python gateway/platforms/feishu_comment.py
 */
public class FeishuCommentAdapter implements PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(FeishuCommentAdapter.class);
    
    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private AIAgent agent;
    private volatile boolean connected;
    private volatile boolean running;
    
    private String appId;
    private String appSecret;
    private String tenantAccessToken;
    private long tokenExpiry;
    
    public FeishuCommentAdapter() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "feishu_comment";
    }
    
    @Override
    public void start() throws Exception {
        logger.info("Starting Feishu Comment adapter...");
        
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
        
        logger.info("Feishu Comment adapter started");
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Stopping Feishu Comment adapter...");
        running = false;
        connected = false;
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public void setAgent(AIAgent agent) {
        this.agent = agent;
    }
    
    @Override
    public void sendMessage(String chatId, String message) throws Exception {
        // For comments, chatId format: "doc:doc_token:comment_id" or "sheet:spreadsheet_token:comment_id"
        String[] parts = chatId.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid chat ID format. Expected: 'type:token:comment_id'");
        }
        
        String type = parts[0];
        String token = parts[1];
        String commentId = parts[2];
        
        ensureTokenValid();
        
        if ("doc".equals(type) || "docx".equals(type)) {
            replyToDocumentComment(token, commentId, message);
        } else if ("sheet".equals(type) || "spreadsheet".equals(type)) {
            replyToSpreadsheetComment(token, commentId, message);
        } else if ( "bitable".equals(type)) {
            replyToBitableComment(token, commentId, message);
        } else {
            throw new IllegalArgumentException("Unsupported document type: " + type);
        }
    }
    
    /**
     * Reply to a document comment.
     */
    public void replyToDocumentComment(String documentToken, String commentId, String content) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("content", buildCommentContent(content));
        
        String path = String.format("/doc/v2/documents/%s/comments/%s/reply", documentToken, commentId);
        sendPost(path, requestBody);
    }
    
    /**
     * Reply to a docx comment.
     */
    public void replyToDocxComment(String documentToken, String commentId, String content) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("content", buildCommentContent(content));
        
        String path = String.format("/docx/v1/documents/%s/comments/%s/reply", documentToken, commentId);
        sendPost(path, requestBody);
    }
    
    /**
     * Reply to a spreadsheet comment.
     */
    public void replyToSpreadsheetComment(String spreadsheetToken, String commentId, String content) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("content", buildCommentContent(content));
        
        String path = String.format("/sheets/v3/spreadsheets/%s/comments/%s/reply", spreadsheetToken, commentId);
        sendPost(path, requestBody);
    }
    
    /**
     * Reply to a bitable comment.
     */
    public void replyToBitableComment(String appToken, String commentId, String content) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("content", buildCommentContent(content));
        
        String path = String.format("/bitable/v1/apps/%s/comments/%s/reply", appToken, commentId);
        sendPost(path, requestBody);
    }
    
    /**
     * Get document comments.
     */
    public JSONObject getDocumentComments(String documentToken) throws Exception {
        ensureTokenValid();
        
        String path = String.format("/doc/v2/documents/%s/comments", documentToken);
        String response = sendGet(path);
        return JSON.parseObject(response);
    }
    
    /**
     * Get docx comments.
     */
    public JSONObject getDocxComments(String documentToken) throws Exception {
        ensureTokenValid();
        
        String path = String.format("/docx/v1/documents/%s/comments", documentToken);
        String response = sendGet(path);
        return JSON.parseObject(response);
    }
    
    /**
     * Process incoming comment event.
     */
    public void processCommentEvent(JSONObject event) {
        String eventType = event.getString("event_type");
        
        if (!"comment.created".equals(eventType) && !"comment.updated".equals(eventType)) {
            return;
        }
        
        JSONObject comment = event.getJSONObject("comment");
        if (comment == null) {
            return;
        }
        
        String commentId = comment.getString("comment_id");
        String content = comment.getString("content");
        String creator = comment.getJSONObject("creator") != null ? 
            comment.getJSONObject("creator").getString("name") : "Unknown";
        
        JSONObject document = event.getJSONObject("document");
        String docType = document != null ? document.getString("type") : "unknown";
        String docToken = document != null ? document.getString("token") : "";
        
        // Build chat ID
        String chatId = docType + ":" + docToken + ":" + commentId;
        
        logger.debug("Received Feishu comment from {} on {}: {}", creator, docType, commentId);
        
        if (agent != null && content != null && !content.isEmpty()) {
            try {
                String context = String.format("Document: %s (%s)\nComment by: %s\n\n%s", 
                    docToken, docType, creator, content);
                agent.processMessage(chatId, context);
            } catch (Exception e) {
                logger.error("Error processing Feishu comment", e);
            }
        }
    }
    
    // Private helper methods
    
    private void refreshToken() throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("app_id", appId);
        requestBody.put("app_secret", appSecret);
        
        Request request = new Request.Builder()
            .url(API_BASE_URL + "/auth/v3/tenant_access_token/internal")
            .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Failed to get tenant access token: " + response);
            }
            
            String responseBody = response.body().string();
            JSONObject json = JSON.parseObject(responseBody);
            
            int code = json.getIntValue("code");
            if (code != 0) {
                throw new Exception("Feishu API error: " + json.getString("msg"));
            }
            
            tenantAccessToken = json.getString("tenant_access_token");
            int expire = json.getIntValue("expire", 7200);
            tokenExpiry = System.currentTimeMillis() + (expire * 1000L) - 60000;
            
            logger.debug("Feishu tenant token refreshed, expires in {} seconds", expire);
        }
    }
    
    private void ensureTokenValid() throws Exception {
        if (System.currentTimeMillis() >= tokenExpiry) {
            refreshToken();
        }
    }
    
    private String sendPost(String path, JSONObject body) throws Exception {
        Request request = new Request.Builder()
            .url(API_BASE_URL + path)
            .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer " + tenantAccessToken)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("Feishu API error: {} - {}", response.code(), responseBody);
                throw new Exception("API error " + response.code() + ": " + responseBody);
            }
            
            JSONObject json = JSON.parseObject(responseBody);
            int code = json.getIntValue("code");
            if (code != 0) {
                throw new Exception("Feishu API error " + code + ": " + json.getString("msg"));
            }
            
            return responseBody;
        }
    }
    
    private String sendGet(String path) throws Exception {
        Request request = new Request.Builder()
            .url(API_BASE_URL + path)
            .get()
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("Feishu API error: {} - {}", response.code(), responseBody);
                throw new Exception("API error " + response.code() + ": " + responseBody);
            }
            
            return responseBody;
        }
    }
    
    private JSONObject buildCommentContent(String text) {
        JSONObject content = new JSONObject();
        
        // Build rich text content
        com.alibaba.fastjson2.JSONArray elements = new com.alibaba.fastjson2.JSONArray();
        
        JSONObject textElement = new JSONObject();
        textElement.put("type", "text");
        textElement.put("text", text);
        elements.add(textElement);
        
        content.put("elements", elements);
        return content;
    }
}
