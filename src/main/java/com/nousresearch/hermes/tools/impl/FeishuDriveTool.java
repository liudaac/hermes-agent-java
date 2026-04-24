package com.nousresearch.hermes.tools.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
 * Feishu Drive Tool -- cloud storage operations via Feishu/Lark API.
 * Mirrors Python tools/feishu_drive_tool.py
 *
 * Provides tools for listing, uploading, and managing files in Feishu Drive.
 */
public class FeishuDriveTool {
    private static final Logger logger = LoggerFactory.getLogger(FeishuDriveTool.class);

    private static final String API_BASE_URL = "https://open.feishu.cn/open-apis";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private String tenantAccessToken;
    private String appId;
    private String appSecret;
    private long tokenExpiry;

    public FeishuDriveTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

        this.appId = System.getenv("FEISHU_APP_ID");
        this.appSecret = System.getenv("FEISHU_APP_SECRET");
    }

    /**
     * Get tool definitions.
     */
    public static ToolEntry[] getToolDefinitions() {
        return new ToolEntry[] {
            getListFilesTool(),
            getUploadFileTool(),
            getDownloadFileTool(),
            getCreateFolderTool(),
            getDeleteFileTool()
        };
    }

    private static ToolEntry getListFilesTool() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> props = new HashMap<>();
        props.put("folder_token", Map.of(
            "type", "string",
            "description", "Folder token to list files from. Use root for root folder."
        ));

        params.put("properties", props);
        params.put("required", new String[]{"folder_token"});

        return new ToolEntry.Builder()
            .name("feishu_drive_list")
            .toolset("feishu")
            .schema(params)
            .handler(args -> "Feishu Drive list files: not yet implemented")
            .description("List files in a Feishu Drive folder.")
            .emoji("📁")
            .build();
    }

    private static ToolEntry getUploadFileTool() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> props = new HashMap<>();
        props.put("folder_token", Map.of(
            "type", "string",
            "description", "Target folder token."
        ));
        props.put("file_name", Map.of(
            "type", "string",
            "description", "Name of the file to upload."
        ));
        props.put("file_path", Map.of(
            "type", "string",
            "description", "Local file path to upload."
        ));

        params.put("properties", props);
        params.put("required", new String[]{"folder_token", "file_name", "file_path"});

        return new ToolEntry.Builder()
            .name("feishu_drive_upload")
            .toolset("feishu")
            .schema(params)
            .handler(args -> "Feishu Drive upload: not yet implemented")
            .description("Upload a file to Feishu Drive.")
            .emoji("📤")
            .build();
    }

    private static ToolEntry getDownloadFileTool() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> props = new HashMap<>();
        props.put("file_token", Map.of(
            "type", "string",
            "description", "File token to download."
        ));
        props.put("save_path", Map.of(
            "type", "string",
            "description", "Local path to save the file."
        ));

        params.put("properties", props);
        params.put("required", new String[]{"file_token", "save_path"});

        return new ToolEntry.Builder()
            .name("feishu_drive_download")
            .toolset("feishu")
            .schema(params)
            .handler(args -> "Feishu Drive download: not yet implemented")
            .description("Download a file from Feishu Drive.")
            .emoji("📥")
            .build();
    }

    private static ToolEntry getCreateFolderTool() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> props = new HashMap<>();
        props.put("parent_token", Map.of(
            "type", "string",
            "description", "Parent folder token."
        ));
        props.put("folder_name", Map.of(
            "type", "string",
            "description", "Name of the new folder."
        ));

        params.put("properties", props);
        params.put("required", new String[]{"parent_token", "folder_name"});

        return new ToolEntry.Builder()
            .name("feishu_drive_create_folder")
            .toolset("feishu")
            .schema(params)
            .handler(args -> "Feishu Drive create folder: not yet implemented")
            .description("Create a folder in Feishu Drive.")
            .emoji("📂")
            .build();
    }

    private static ToolEntry getDeleteFileTool() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");

        Map<String, Object> props = new HashMap<>();
        props.put("file_token", Map.of(
            "type", "string",
            "description", "File or folder token to delete."
        ));

        params.put("properties", props);
        params.put("required", new String[]{"file_token"});

        return new ToolEntry.Builder()
            .name("feishu_drive_delete")
            .toolset("feishu")
            .schema(params)
            .handler(args -> "Feishu Drive delete: not yet implemented")
            .description("Delete a file or folder from Feishu Drive.")
            .emoji("🗑️")
            .build();
    }

    /**
     * List files in a folder.
     */
    public JSONArray listFiles(String folderToken) throws Exception {
        ensureTokenValid();

        String url = API_BASE_URL + "/drive/v1/files?folder_token=" + 
            (folderToken != null ? folderToken : "");

        Request request = new Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IOException("API error: " + body);
            }

            JSONObject json = JSON.parseObject(body);
            int code = json.getIntValue("code");
            if (code != 0) {
                throw new IOException("Feishu error " + code + ": " + json.getString("msg"));
            }

            JSONObject data = json.getJSONObject("data");
            return data != null ? data.getJSONArray("files") : new JSONArray();
        }
    }

    /**
     * Create a folder.
     */
    public JSONObject createFolder(String parentToken, String folderName) throws Exception {
        ensureTokenValid();

        JSONObject requestBody = new JSONObject();
        requestBody.put("name", folderName);
        requestBody.put("folder_token", parentToken);
        requestBody.put("type", "folder");

        Request request = new Request.Builder()
            .url(API_BASE_URL + "/drive/v1/files")
            .post(RequestBody.create(requestBody.toJSONString(), JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(body);
            int code = json.getIntValue("code");
            if (code != 0) {
                throw new IOException("Feishu error " + code + ": " + json.getString("msg"));
            }
            return json.getJSONObject("data");
        }
    }

    /**
     * Delete a file.
     */
    public void deleteFile(String fileToken) throws Exception {
        ensureTokenValid();

        Request request = new Request.Builder()
            .url(API_BASE_URL + "/drive/v1/files/" + fileToken)
            .delete()
            .header("Authorization", "Bearer " + tenantAccessToken)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(body);
            int code = json.getIntValue("code");
            if (code != 0) {
                throw new IOException("Feishu error " + code + ": " + json.getString("msg"));
            }
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
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(body);
            int code = json.getIntValue("code");
            if (code != 0) {
                throw new IOException("Auth error: " + json.getString("msg"));
            }
            tenantAccessToken = json.getString("tenant_access_token");
            int expire = json.getIntValue("expire", 7200);
            tokenExpiry = System.currentTimeMillis() + (expire * 1000L) - 60000;
        }
    }

    private void ensureTokenValid() throws Exception {
        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpiry) {
            refreshToken();
        }
    }
}
