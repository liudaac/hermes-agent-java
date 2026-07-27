package com.nousresearch.hermes.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Hermes Java SDK - lightweight HTTP client for business systems.
 *
 * <p>Zero external dependencies (JDK 21+ only). Build, publish, use:</p>
 * <pre>{@code
 * HermesClient client = HermesClient.builder()
 *     .baseUrl("http://localhost:8080")
 *     .apiKey("ak_xxx")
 *     .build();
 *
 * // 1. Send message (direct mode)
 * MessageResponse reply = client.sendMessage("agent-1", "查询今天的订单");
 *
 * // 2. Send message (chain mode - planner -> executor -> reviewer)
 * MessageResponse result = client.sendMessageChain("agent-1", "分析日志并总结错误");
 * // result.chainMode == true
 * // result.traceId  -> use to query progress
 * // result.plan     -> ExecutionPlan JSON (goal, steps, successCriteria)
 *
 * // 3. Submit async task
 * String taskId = client.submitTask("agent-1", "生成月度报告").taskId();
 *
 * // 4. Interrupt a running chain
 * client.interruptTask(taskId);
 *
 * // 5. Query trace
 * String trace = client.getTrace(result.traceId);
 * }</pre>
 *
 * @author Hermes Team
 */
public class HermesClient {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    private HermesClient(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ============ Messages (synchronous) ============

    /**
     * Send a message to an agent (direct mode, single model call).
     */
    public MessageResponse sendMessage(String agentId, String message) {
        String body = "{\"message\":\"" + Json.esc(message) + "\"}";
        String json = post("/api/v1/agents/" + agentId + "/messages", body);
        return MessageResponse.parse(json);
    }

    /**
     * Send a message to an agent in chain mode (planner -> executor -> reviewer).
     * Convenience method: prepends [chain] to the message.
     */
    public MessageResponse sendMessageChain(String agentId, String message) {
        return sendMessage(agentId, "[chain] " + message);
    }

    public CompletableFuture<MessageResponse> sendMessageAsync(String agentId, String message) {
        return CompletableFuture.supplyAsync(() -> sendMessage(agentId, message));
    }

    // ============ Tasks (asynchronous) ============

    public TaskResponse submitTask(String agentId, String input) {
        return submitTask(agentId, input, 0, 300);
    }

    /**
     * Submit an async task in chain mode.
     */
    public TaskResponse submitTaskChain(String agentId, String input) {
        return submitTask(agentId, "[chain] " + input);
    }

    public TaskResponse submitTask(String agentId, String input, int priority, int timeoutSeconds) {
        String body = String.format(
            "{\"agentId\":\"%s\",\"input\":\"%s\",\"priority\":%d,\"timeoutSeconds\":%d}",
            Json.esc(agentId), Json.esc(input), priority, timeoutSeconds);
        String json = post("/api/v1/tasks", body);
        return TaskResponse.parse(json);
    }

    public TaskStatus getTask(String taskId) {
        String json = get("/api/v1/tasks/" + taskId);
        return TaskStatus.parse(json);
    }

    /** Cancel a task (DB mark + interrupt running chain). */
    public boolean cancelTask(String taskId) {
        String json = post("/api/v1/tasks/" + taskId + "/cancel", "{}");
        return json.contains("CANCELLED");
    }

    /** Interrupt a running chain (current step completes, then stops). */
    public InterruptResponse interruptTask(String taskId) {
        String json = post("/api/v1/tasks/" + taskId + "/interrupt", "{}");
        return InterruptResponse.parse(json);
    }

    /** Check if a chain is currently running for a task. */
    public boolean isChainRunning(String taskId) {
        String json = get("/api/v1/tasks/" + taskId + "/status");
        return Json.getBoolean(json, "chainRunning");
    }

    // ============ Traces ============

    /**
     * Get execution trace by ID (spans: planner/executor/reviewer/step/retry).
     */
    public String getTrace(String traceId) {
        return get("/api/traces/" + traceId);
    }

    // ============ Usage & Billing ============

    public String getUsage(String tenantId) {
        return get("/api/v1/tenants/" + tenantId + "/usage");
    }

    public String getBilling(String tenantId) {
        return get("/api/v1/tenants/" + tenantId + "/billing");
    }

    // ============ Webhooks ============

    public String registerWebhook(String url, String[] events, String secret) {
        StringBuilder eventsArr = new StringBuilder("[");
        for (int i = 0; i < events.length; i++) {
            if (i > 0) eventsArr.append(",");
            eventsArr.append("\"").append(Json.esc(events[i])).append("\"");
        }
        eventsArr.append("]");
        String body = String.format(
            "{\"url\":\"%s\",\"events\":%s,\"secret\":\"%s\"}",
            Json.esc(url), eventsArr, Json.esc(secret));
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

    // ============ Internal HTTP ============

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

    // ============ Response types ============

    public record MessageResponse(
            String reply,
            long durationMs,
            String workspaceId,
            boolean chainMode,
            String traceId,
            String planJson   // raw JSON of ExecutionPlan, null if not chain mode
    ) {
        public static MessageResponse parse(String json) {
            return new MessageResponse(
                Json.getString(json, "reply"),
                Json.getLong(json, "durationMs"),
                Json.getString(json, "workspaceId"),
                Json.getBoolean(json, "chainMode"),
                Json.getString(json, "traceId"),
                Json.getObject(json, "plan")
            );
        }

        /**
         * Get a specific field from the plan JSON.
         * e.g. planField("goal") returns the goal string.
         */
        public String planField(String field) {
            if (planJson == null) return null;
            return Json.getString(planJson, field);
        }
    }

    public record TaskResponse(String taskId, String status) {
        public static TaskResponse parse(String json) {
            return new TaskResponse(
                Json.getString(json, "taskId"),
                Json.getString(json, "status")
            );
        }
    }

    public record TaskStatus(String taskId, String status, String result, String error) {
        public static TaskStatus parse(String json) {
            return new TaskStatus(
                Json.getString(json, "taskId"),
                Json.getString(json, "status"),
                Json.getString(json, "result"),
                Json.getString(json, "error")
            );
        }

        public boolean isTerminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "INTERRUPTED".equals(status);
        }

        public boolean isCompleted() { return "COMPLETED".equals(status); }
        public boolean isFailed() { return "FAILED".equals(status); }
        public boolean isInterrupted() { return "INTERRUPTED".equals(status); }
    }

    public record InterruptResponse(String taskId, String status, String message) {
        public static InterruptResponse parse(String json) {
            return new InterruptResponse(
                Json.getString(json, "taskId"),
                Json.getString(json, "status"),
                Json.getString(json, "message")
            );
        }

        public boolean isInterrupting() { return "INTERRUPTING".equals(status); }
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
        private Duration timeout = Duration.ofSeconds(30);

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public HermesClient build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new HermesClient(baseUrl, apiKey, timeout);
        }
    }
}
