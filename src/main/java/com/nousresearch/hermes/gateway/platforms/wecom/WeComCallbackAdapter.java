package com.nousresearch.hermes.gateway.platforms.wecom;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.gateway.platforms.PlatformAdapter;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * WeCom (Enterprise WeChat) callback platform adapter.
 * Handles WeCom callback messages for enterprise integration.
 * Mirrors Python gateway/platforms/wecom_callback.py
 */
public class WeComCallbackAdapter implements PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(WeComCallbackAdapter.class);
    
    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com/cgi-bin";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private AIAgent agent;
    private volatile boolean connected;
    private volatile boolean running;
    
    private String corpId;
    private String agentId;
    private String secret;
    private String token;
    private String encodingAesKey;
    private String accessToken;
    private long tokenExpiry;
    
    public WeComCallbackAdapter() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "wecom";
    }
    
    @Override
    public void start() throws Exception {
        logger.info("Starting WeCom callback adapter...");
        
        // Load credentials from environment
        corpId = System.getenv("WECOM_CORP_ID");
        agentId = System.getenv("WECOM_AGENT_ID");
        secret = System.getenv("WECOM_SECRET");
        token = System.getenv("WECOM_TOKEN");
        encodingAesKey = System.getenv("WECOM_ENCODING_AES_KEY");
        
        if (corpId == null || secret == null) {
            throw new IllegalStateException("WECOM_CORP_ID and WECOM_SECRET must be set");
        }
        
        // Get access token
        refreshToken();
        
        running = true;
        connected = true;
        
        logger.info("WeCom callback adapter started");
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Stopping WeCom callback adapter...");
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
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("touser", chatId);
        requestBody.put("msgtype", "text");
        requestBody.put("agentid", agentId);
        
        JSONObject text = new JSONObject();
        text.put("content", message);
        requestBody.put("text", text);
        
        sendPost("/message/send", requestBody);
    }
    
    /**
     * Send message to department.
     */
    public void sendDepartmentMessage(String departmentId, String message) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("toparty", departmentId);
        requestBody.put("msgtype", "text");
        requestBody.put("agentid", agentId);
        
        JSONObject text = new JSONObject();
        text.put("content", message);
        requestBody.put("text", text);
        
        sendPost("/message/send", requestBody);
    }
    
    /**
     * Send message to tag.
     */
    public void sendTagMessage(String tagId, String message) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("totag", tagId);
        requestBody.put("msgtype", "text");
        requestBody.put("agentid", agentId);
        
        JSONObject text = new JSONObject();
        text.put("content", message);
        requestBody.put("text", text);
        
        sendPost("/message/send", requestBody);
    }
    
    /**
     * Send markdown message.
     */
    public void sendMarkdownMessage(String chatId, String markdown) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("touser", chatId);
        requestBody.put("msgtype", "markdown");
        requestBody.put("agentid", agentId);
        
        JSONObject markdownObj = new JSONObject();
        markdownObj.put("content", markdown);
        requestBody.put("markdown", markdownObj);
        
        sendPost("/message/send", requestBody);
    }
    
    /**
     * Send news article message.
     */
    public void sendNewsMessage(String chatId, String title, String description, String url, String picUrl) throws Exception {
        ensureTokenValid();
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("touser", chatId);
        requestBody.put("msgtype", "news");
        requestBody.put("agentid", agentId);
        
        JSONObject article = new JSONObject();
        article.put("title", title);
        article.put("description", description);
        article.put("url", url);
        article.put("picurl", picUrl);
        
        JSONObject news = new JSONObject();
        news.put("articles", new com.alibaba.fastjson2.JSONArray() {{ add(article); }});
        requestBody.put("news", news);
        
        sendPost("/message/send", requestBody);
    }
    
    /**
     * Verify callback URL signature.
     * 
     * @param signature Signature from request
     * @param timestamp Timestamp from request
     * @param nonce Nonce from request
     * @param echostr Echo string to verify
     * @return true if signature is valid
     */
    public boolean verifySignature(String signature, String timestamp, String nonce, String echostr) {
        try {
            String[] params = new String[]{token, timestamp, nonce, echostr};
            java.util.Arrays.sort(params);
            StringBuilder content = new StringBuilder();
            for (String param : params) {
                content.append(param);
            }
            
            Mac mac = Mac.getInstance("SHA1");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "SHA1"));
            byte[] hash = mac.doFinal(content.toString().getBytes(StandardCharsets.UTF_8));
            String expectedSignature = bytesToHex(hash);
            
            return expectedSignature.equalsIgnoreCase(signature);
        } catch (Exception e) {
            logger.error("Error verifying signature", e);
            return false;
        }
    }
    
    /**
     * Process incoming callback message.
     * 
     * @param message JSON message from WeCom
     */
    public void processCallback(JSONObject message) {
        String msgType = message.getString("MsgType");
        String fromUser = message.getString("FromUserName");
        String content = message.getString("Content");
        
        logger.debug("Received WeCom message from {}: {}", fromUser, msgType);
        
        if ("text".equals(msgType) && agent != null) {
            try {
                agent.processMessage(fromUser, content);
            } catch (Exception e) {
                logger.error("Error processing WeCom message", e);
            }
        }
    }
    
    // Private helper methods
    
    private void refreshToken() throws Exception {
        Request request = new Request.Builder()
            .url(API_BASE_URL + "/gettoken?corpid=" + corpId + "&corpsecret=" + secret)
            .get()
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get access token: " + response);
            }
            
            String responseBody = response.body().string();
            JSONObject json = JSON.parseObject(responseBody);
            
            int errcode = json.getIntValue("errcode");
            if (errcode != 0) {
                throw new IOException("WeCom API error: " + json.getString("errmsg"));
            }
            
            accessToken = json.getString("access_token");
            int expiresIn = json.getIntValue("expires_in", 7200);
            tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L) - 60000;
            
            logger.debug("WeCom token refreshed, expires in {} seconds", expiresIn);
        }
    }
    
    private void ensureTokenValid() throws Exception {
        if (System.currentTimeMillis() >= tokenExpiry) {
            refreshToken();
        }
    }
    
    private String sendPost(String path, JSONObject body) throws Exception {
        ensureTokenValid();
        
        Request request = new Request.Builder()
            .url(API_BASE_URL + path + "?access_token=" + accessToken)
            .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                logger.error("WeCom API error: {} - {}", response.code(), responseBody);
                throw new IOException("API error " + response.code() + ": " + responseBody);
            }
            
            // Check errcode in response
            JSONObject json = JSON.parseObject(responseBody);
            int errcode = json.getIntValue("errcode");
            if (errcode != 0) {
                throw new IOException("WeCom API error " + errcode + ": " + json.getString("errmsg"));
            }
            
            return responseBody;
        }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
