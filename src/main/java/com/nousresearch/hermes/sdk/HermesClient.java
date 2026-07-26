package com.nousresearch.hermes.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * D7: Hermes Java SDK - lightweight HTTP client for business systems.
 *
 * <p>Enables business systems to integrate with Hermes in 3 lines of code:</p>
 * <pre>{@code
 * HermesClient client = HermesClient.builder()
 *     .baseUrl("http://localhost:8080")
 *     .apiKey("ak_xxx")
 *     .build();
 *
 * // Send a message
 * String reply = client.sendMessage("agent-1", "查询今天的订单").reply();
 *
 * // Submit async task
 * String taskId = client.submitTask("agent-1", "生成月度报告").taskId();
 *
 * // Poll for result
 * TaskStatus status = client.getTask(taskId);
 * }</pre>
 *
 * @author Hermes Team
 * @version D7
 */
public class HermesClient {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    private HermesClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============ Messages ============

    public MessageResponse sendMessage(String agentId, String message) {
        String body = String.format("{\"message\":\"%s\"}", escape(message));
        String json = post("/api/v1/agents/" + agentId + "/messages", body);
        return MessageResponse.parse(json);
    }

    public CompletableFuture<MessageResponse> sendMessageAsync(String agentId, String message) {
        return CompletableFuture.supplyAsync(() -> sendMessage(agentId, message));
    }

    // ============ Tasks ============

    public TaskResponse submitTask(String agentId, String input) {
        return submitTask(agentId, input, 0, 300);
    }

    public TaskResponse submitTask(String agentId, String input, int priority, int timeoutSeconds) {
        String body = String.format(
            "{\"agentId\":\"%s\",\"input\":\"%s\",\"priority\":%d,\"timeoutSeconds\":%d}",
            escape(agentId), escape(input), priority, timeoutSeconds);
        String json = post("/api/v1/tasks", body);
        return TaskResponse.parse(json);
    }

    public TaskStatus getTask(String taskId) {
        String json = get("/api/v1/tasks/" + taskId);
        return TaskStatus.parse(json);
    }

    public boolean cancelTask(String taskId) {
        String json = post("/api/v1/tasks/" + taskId + "/cancel", "{}");
        return json.contains("\"CANCELLED\"");
    }

    // ============ Usage ============

    public String getUsage(String tenantId) {
        return get("/api/v1/tenants/" + tenantId + "/usage");
    }

    // ============ Webhooks ============

    public String registerWebhook(String url, String[] events, String secret) {
        StringBuilder eventsArr = new StringBuilder("[");
        for (int i = 0; i < events.length; i++) {
            if (i > 0) eventsArr.append(",");
            eventsArr.append("\"").append(escape(events[i])).append("\"");
        }
        eventsArr.append("]");
        String body = String.format(
            "{\"url\":\"%s\",\"events\":%s,\"secret\":\"%s\"}",
            escape(url), eventsArr, escape(secret));
        return post("/api/v1/webhooks", body);
    }

    // ============ Health ============

    public boolean isHealthy() {
        try {
            String json = get("/api/v1/health");
            return json.contains("\"status\":\"ok\"");
        } catch (Exception e) {
            return false;
        }
    }

    // ============ Internal ============

    private String get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new HermesApiException(response.statusCode(), response.body());
            }
            return response.body();
        } catch (HermesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new HermesApiException(0, "Request failed: " + e.getMessage());
        }
    }

    private String post(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new HermesApiException(response.statusCode(), response.body());
            }
            return response.body();
        } catch (HermesApiException e) {
            throw e;
        } catch (Exception e) {
            throw new HermesApiException(0, "Request failed: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // ============ Response types ============

    public record MessageResponse(String reply, long durationMs, String workspaceId) {
        public static MessageResponse parse(String json) {
            var obj = com.alibaba.fastjson2.JSON.parseObject(json);
            return new MessageResponse(
                obj.getString("reply"),
                obj.getLongValue("durationMs"),
                obj.getString("workspaceId"));
        }
    }

    public record TaskResponse(String taskId, String status) {
        public static TaskResponse parse(String json) {
            var obj = com.alibaba.fastjson2.JSON.parseObject(json);
            return new TaskResponse(obj.getString("taskId"), obj.getString("status"));
        }
    }

    public record TaskStatus(String taskId, String status, String result, String error) {
        public static TaskStatus parse(String json) {
            var obj = com.alibaba.fastjson2.JSON.parseObject(json);
            return new TaskStatus(
                obj.getString("taskId"),
                obj.getString("status"),
                obj.getString("result"),
                obj.getString("error"));
        }

        public boolean isTerminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }

        public boolean isCompleted() { return "COMPLETED".equals(status); }
        public boolean isFailed() { return "FAILED".equals(status); }
    }

    // ============ Exception ============

    public static class HermesApiException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public HermesApiException(int statusCode, String responseBody) {
            super("Hermes API error " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() { return statusCode; }
        public String getResponseBody() { return responseBody; }
    }

    // ============ Builder ============

    public static class Builder {
        private String baseUrl = "http://localhost:8080";
        private String apiKey;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public HermesClient build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new HermesClient(baseUrl, apiKey);
        }
    }
}
