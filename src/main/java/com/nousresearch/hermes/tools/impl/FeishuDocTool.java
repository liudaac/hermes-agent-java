package com.nousresearch.hermes.tools.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tools.ToolEntry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Feishu Document Tool -- read document content via Feishu/Lark API.
 * Mirrors Python tools/feishu_doc_tool.py
 *
 * Provides feishu_doc_read for reading document content as plain text.
 */
public class FeishuDocTool {
    private static final Logger logger = LoggerFactory.getLogger(FeishuDocTool.class);

    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private String tenantAccessToken;
    private String appId;
    private String appSecret;
    private long tokenExpiry;

    public FeishuDocTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

        // Load credentials from environment
        this.appId = System.getenv("FEISHU_APP_ID");
        this.appSecret = System.getenv("FEISHU_APP_SECRET");
    }

    /**
     * Get tool definition for feishu_doc_read.
     */
    public static ToolEntry getToolDefinition() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("doc_token", Map.of(
            "type", "string",
            "description", "The document token (from the document URL or comment context)."
        ));

        parameters.put("properties", properties);
        parameters.put("required", new String[]{"doc_token"});

        return new ToolEntry.Builder()
            .name("feishu_doc_read")
            .toolset("feishu")
            .schema(parameters)
            .handler(args -> {
                try {
                    FeishuDocTool tool = new FeishuDocTool();
                    String docToken = (String) args.get("doc_token");
                    String content = tool.readDocument(docToken);
                    return content != null ? content : "No content found";
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            })
            .description("Read the full content of a Feishu/Lark document as plain text. " +
                "Useful when you need more context beyond the quoted text in a comment.")
            .emoji("📄")
            .build();
    }

    /**
     * Read a Feishu document.
     *
     * @param docToken Document token
     * @return Document content
     */
    public String readDocument(String docToken) throws Exception {
        if (docToken == null || docToken.trim().isEmpty()) {
            throw new IllegalArgumentException("doc_token is required");
        }

        ensureTokenValid();

        // Build request
        String url = API_BASE_URL + "/docx/v1/documents/" + docToken + "/raw_content";

        Request request = new Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                logger.error("Feishu API error: {} - {}", response.code(), responseBody);
                throw new IOException("API error " + response.code() + ": " + responseBody);
            }

            JSONObject json = JSON.parseObject(responseBody);
            int code = json.getIntValue("code");

            if (code != 0) {
                throw new IOException("Feishu API error " + code + ": " + json.getString("msg"));
            }

            JSONObject data = json.getJSONObject("data");
            if (data == null) {
                return "";
            }

            return data.getString("content");
        }
    }

    /**
     * Read document blocks.
     *
     * @param docToken Document token
     * @return JSON blocks data
     */
    public JSONObject readDocumentBlocks(String docToken) throws Exception {
        if (docToken == null || docToken.trim().isEmpty()) {
            throw new IllegalArgumentException("doc_token is required");
        }

        ensureTokenValid();

        String url = API_BASE_URL + "/docx/v1/documents/" + docToken + "/blocks";

        Request request = new Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IOException("API error " + response.code() + ": " + responseBody);
            }

            JSONObject json = JSON.parseObject(responseBody);
            int code = json.getIntValue("code");

            if (code != 0) {
                throw new IOException("Feishu API error " + code + ": " + json.getString("msg"));
            }

            return json.getJSONObject("data");
        }
    }

    /**
     * Get document metadata.
     *
     * @param docToken Document token
     * @return Document metadata
     */
    public JSONObject getDocumentMeta(String docToken) throws Exception {
        ensureTokenValid();

        String url = API_BASE_URL + "/docx/v1/documents/" + docToken;

        Request request = new Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IOException("API error " + response.code() + ": " + responseBody);
            }

            JSONObject json = JSON.parseObject(responseBody);
            int code = json.getIntValue("code");

            if (code != 0) {
                throw new IOException("Feishu API error " + code + ": " + json.getString("msg"));
            }

            return json.getJSONObject("data");
        }
    }

    private void refreshToken() throws Exception {
        if (appId == null || appSecret == null) {
            throw new IllegalStateException("FEISHU_APP_ID and FEISHU_APP_SECRET must be set");
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("app_id", appId);
        requestBody.put("app_secret", appSecret);

        Request request = new Request.Builder()
            .url(API_BASE_URL + "/auth/v3/tenant_access_token/internal")
            .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IOException("Failed to get token: " + responseBody);
            }

            JSONObject json = JSON.parseObject(responseBody);
            int code = json.getIntValue("code");

            if (code != 0) {
                throw new IOException("Feishu auth error: " + json.getString("msg"));
            }

            tenantAccessToken = json.getString("tenant_access_token");
            int expire = json.getIntValue("expire", 7200);
            tokenExpiry = System.currentTimeMillis() + (expire * 1000L) - 60000;

            logger.debug("Feishu token refreshed, expires in {} seconds", expire);
        }
    }

    private void ensureTokenValid() throws Exception {
        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpiry) {
            refreshToken();
        }
    }
}
