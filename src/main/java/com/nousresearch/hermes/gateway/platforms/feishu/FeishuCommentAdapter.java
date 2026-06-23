package com.nousresearch.hermes.gateway.platforms.feishu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.gateway.IncomingMessage;
import com.nousresearch.hermes.gateway.platforms.PlatformAdapter;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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
    private static final long DEFAULT_MAX_TIMESTAMP_SKEW_MS = 5 * 60 * 1000L;
    
    private final OkHttpClient httpClient;
    private TenantAwareAIAgent agent;
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
    public void setAgent(TenantAwareAIAgent agent) {
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
        
        if ("doc".equals(type)) {
            replyToDocumentComment(token, commentId, message);
        } else if ("docx".equals(type)) {
            replyToDocxComment(token, commentId, message);
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
    
    @Override
    public JSONObject getWebhookChallengeResponse(JSONObject payload) {
        if (payload == null || !payload.containsKey("challenge")) {
            return null;
        }
        if (!verifyFeishuToken(payload)) {
            logger.warn("Rejected Feishu comment challenge: verification token mismatch");
            return null;
        }
        return JSONObject.of("challenge", payload.getString("challenge"));
    }

    @Override
    public boolean verifyWebhook(JSONObject payload, Map<String, String> headers, String rawBody) {
        if (!verifyFeishuToken(payload)) {
            logger.warn("Rejected Feishu comment webhook: verification token mismatch");
            return false;
        }
        if (!verifyTimestamp(headers)) {
            logger.warn("Rejected Feishu comment webhook: timestamp outside allowed skew");
            return false;
        }
        if (!verifySignatureIfConfigured(headers, rawBody)) {
            logger.warn("Rejected Feishu comment webhook: signature mismatch");
            return false;
        }
        return true;
    }

    @Override
    public IncomingMessage parseWebhook(JSONObject payload) {
        JSONObject event = extractEvent(payload);
        if (event == null) {
            return null;
        }

        String eventType = firstNonBlank(
            event.getString("event_type"),
            event.getString("type"),
            payload != null && payload.getJSONObject("header") != null
                ? payload.getJSONObject("header").getString("event_type")
                : null
        );
        if (!"comment.created".equals(eventType) && !"comment.updated".equals(eventType)) {
            return null;
        }

        JSONObject comment = event.getJSONObject("comment");
        if (comment == null) {
            return null;
        }

        String commentId = firstNonBlank(comment.getString("comment_id"), comment.getString("id"));
        String content = firstNonBlank(comment.getString("content"), comment.getString("text"));
        if (commentId == null || content == null || content.isBlank()) {
            return null;
        }

        JSONObject document = event.getJSONObject("document");
        String docType = document != null ? firstNonBlank(document.getString("type"), document.getString("obj_type")) : null;
        String docToken = document != null ? firstNonBlank(document.getString("token"), document.getString("obj_token")) : null;
        if (docType == null || docToken == null) {
            return null;
        }

        String creator = extractCreator(comment);
        String chatId = docType + ":" + docToken + ":" + commentId;
        String context = String.format("Document: %s (%s)\nComment by: %s\n\n%s",
            docToken, docType, creator, content);

        logger.debug("Parsed Feishu comment webhook from {} on {}: {}", creator, docType, commentId);
        return new IncomingMessage(
            commentId,
            chatId,
            creator,
            context,
            System.currentTimeMillis(),
            false
        );
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
                String response = agent.processMessage(context);
                if (response != null && !response.isEmpty()) {
                    sendMessage(chatId, response);
                }
            } catch (Exception e) {
                logger.error("Error processing Feishu comment", e);
            }
        }
    }

    private boolean verifyFeishuToken(JSONObject payload) {
        String expectedToken = getExpectedVerificationToken();
        if (expectedToken == null) {
            return true;
        }

        String actualToken = null;
        if (payload != null) {
            actualToken = firstNonBlank(payload.getString("token"));
            JSONObject header = payload.getJSONObject("header");
            if (actualToken == null && header != null) {
                actualToken = firstNonBlank(header.getString("token"));
            }
        }
        return expectedToken.equals(actualToken);
    }

    private boolean verifyTimestamp(Map<String, String> headers) {
        String timestamp = getHeader(headers, "X-Lark-Request-Timestamp");
        if (timestamp == null) {
            return true;
        }
        try {
            long requestTimeMs = Long.parseLong(timestamp) * 1000L;
            long skew = Math.abs(System.currentTimeMillis() - requestTimeMs);
            return skew <= DEFAULT_MAX_TIMESTAMP_SKEW_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean verifySignatureIfConfigured(Map<String, String> headers, String rawBody) {
        String encryptKey = getEncryptKey();
        if (encryptKey == null || encryptKey.isBlank()) {
            return true;
        }

        String signature = getHeader(headers, "X-Lark-Signature");
        String timestamp = getHeader(headers, "X-Lark-Request-Timestamp");
        String nonce = getHeader(headers, "X-Lark-Request-Nonce");
        if (signature == null || timestamp == null || nonce == null) {
            return false;
        }

        try {
            String base = timestamp + nonce + encryptKey + (rawBody != null ? rawBody : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String expected = HexFormat.of().formatHex(digest.digest(base.getBytes(StandardCharsets.UTF_8)));
            return expected.equalsIgnoreCase(signature);
        } catch (Exception e) {
            logger.error("Failed to verify Feishu comment signature", e);
            return false;
        }
    }

    protected String getExpectedVerificationToken() {
        return firstNonBlank(
            System.getenv("FEISHU_VERIFICATION_TOKEN"),
            System.getenv("FEISHU_EVENT_TOKEN")
        );
    }

    protected String getEncryptKey() {
        return System.getenv("FEISHU_ENCRYPT_KEY");
    }

    private String getHeader(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private JSONObject extractEvent(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        JSONObject event = payload.getJSONObject("event");
        return event != null ? event : payload;
    }

    private String extractCreator(JSONObject comment) {
        JSONObject creator = comment.getJSONObject("creator");
        if (creator == null) {
            return "unknown";
        }
        return firstNonBlank(
            creator.getString("open_id"),
            creator.getString("user_id"),
            creator.getString("union_id"),
            creator.getString("name"),
            "unknown"
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
