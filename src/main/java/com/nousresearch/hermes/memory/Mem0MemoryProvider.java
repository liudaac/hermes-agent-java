package com.nousresearch.hermes.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * S4-3: mem0 HTTP API 适配器。
 *
 * <p>mem0 是一个开源记忆层服务（https://docs.mem0.ai）。
 * 通过 HTTP API 提供 store / search / retrieve / delete 能力。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * memory:
 *   external:
 *     provider: mem0
 *     base-url: http://localhost:8080
 *     api-key: ${MEM0_API_KEY}
 * }</pre>
 */
public class Mem0MemoryProvider implements ExternalMemoryProvider {
    private static final Logger logger = LoggerFactory.getLogger(Mem0MemoryProvider.class);

    private String baseUrl;
    private String apiKey;
    private HttpClient httpClient;

    @Override
    public String name() { return "mem0"; }

    @Override
    public void initialize(Map<String, String> config) {
        this.baseUrl = config.getOrDefault("base-url", "http://localhost:8080");
        this.apiKey = config.get("api-key");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        logger.info("Mem0 provider initialized: baseUrl={}", baseUrl);
    }

    @Override
    public String store(String tenantId, String agentId, String content, Map<String, String> metadata) {
        try {
            JSONObject body = new JSONObject();
            body.put("tenant_id", tenantId);
            body.put("agent_id", agentId);
            body.put("content", content);
            if (metadata != null && !metadata.isEmpty()) {
                body.put("metadata", new JSONObject(metadata));
            }

            HttpResponse<String> resp = sendRequest("POST", "/api/v1/memories/", body);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JSONObject json = JSON.parseObject(resp.body());
                return json.getString("id");
            }
            logger.error("Mem0 store failed: status={}", resp.statusCode());
            return null;
        } catch (Exception e) {
            logger.error("Mem0 store error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<MemoryRecord> search(String tenantId, String query, int limit) {
        try {
            JSONObject body = new JSONObject();
            body.put("tenant_id", tenantId);
            body.put("query", query);
            body.put("limit", limit);

            HttpResponse<String> resp = sendRequest("POST", "/api/v1/memories/search/", body);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JSONArray results = JSON.parseArray(resp.body());
                List<MemoryRecord> records = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    records.add(new MemoryRecord(
                        item.getString("id"),
                        item.getString("agent_id"),
                        item.getString("content"),
                        item.containsKey("metadata") ? toMap(item.getJSONObject("metadata")) : Map.of(),
                        item.getLongValue("created_at", System.currentTimeMillis()),
                        item.containsKey("score") ? item.getFloat("score").doubleValue() : 0
                    ));
                }
                return records;
            }
            return List.of();
        } catch (Exception e) {
            logger.error("Mem0 search error: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public MemoryRecord retrieve(String tenantId, String memoryId) {
        try {
            HttpResponse<String> resp = sendRequest("GET",
                "/api/v1/memories/" + memoryId + "/?tenant_id=" + tenantId, null);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JSONObject item = JSON.parseObject(resp.body());
                return new MemoryRecord(
                    item.getString("id"),
                    item.getString("agent_id"),
                    item.getString("content"),
                    item.containsKey("metadata") ? toMap(item.getJSONObject("metadata")) : Map.of(),
                    item.getLongValue("created_at", 0),
                    0
                );
            }
            return null;
        } catch (Exception e) {
            logger.error("Mem0 retrieve error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean delete(String tenantId, String memoryId) {
        try {
            HttpResponse<String> resp = sendRequest("DELETE",
                "/api/v1/memories/" + memoryId + "/?tenant_id=" + tenantId, null);
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            logger.error("Mem0 delete error: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<MemoryRecord> list(String tenantId, int offset, int limit) {
        try {
            HttpResponse<String> resp = sendRequest("GET",
                "/api/v1/memories/?tenant_id=" + tenantId +
                "&offset=" + offset + "&limit=" + limit, null);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JSONArray results = JSON.parseArray(resp.body());
                List<MemoryRecord> records = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    records.add(new MemoryRecord(
                        item.getString("id"),
                        item.getString("agent_id"),
                        item.getString("content"),
                        Map.of(),
                        item.getLongValue("created_at", 0),
                        0
                    ));
                }
                return records;
            }
            return List.of();
        } catch (Exception e) {
            logger.error("Mem0 list error: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpResponse<String> resp = sendRequest("GET", "/health", null);
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<String> sendRequest(String method, String path, JSONObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toMap(JSONObject obj) {
        Map<String, String> map = new HashMap<>();
        if (obj != null) {
            for (String key : obj.keySet()) {
                Object val = obj.get(key);
                map.put(key, val != null ? val.toString() : null);
            }
        }
        return map;
    }
}
