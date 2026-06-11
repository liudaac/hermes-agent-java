package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic local-daemon HTTP adapter for browser bridges.
 *
 * <p>The canonical request is POST {endpoint}/actions with the normalized
 * BrowserAction JSON payload. Provider-specific subclasses only name the provider
 * and may override actionPath() when their daemon uses a different endpoint.</p>
 */
public class HttpBrowserBridge implements BrowserBridge {
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final String providerName;
    private final String endpoint;
    private final int timeoutMs;
    private final HttpClient client;

    public HttpBrowserBridge(String providerName, BrowserBridgeConfig config) {
        this.providerName = providerName;
        this.endpoint = config != null ? blankToNull(config.endpoint()) : null;
        this.timeoutMs = config != null ? config.timeoutMs() : 10000;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(this.timeoutMs))
            .build();
    }

    @Override
    public BrowserActionResult execute(BrowserAction action) {
        if (endpoint == null) {
            return BrowserActionResult.error(action.sessionId(), providerName + " endpoint is not configured");
        }

        try {
            Map<String, Object> payload = toPayload(action);
            String body = MAPPER.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(resolve(actionPath()))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-Hermes-Browser-Bridge", providerName)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return BrowserActionResult.error(action.sessionId(), providerName + " HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            return parse(action, response.body());
        } catch (Exception e) {
            return BrowserActionResult.error(action.sessionId(), providerName + " bridge unavailable: " + e.getMessage());
        }
    }

    protected String actionPath() {
        return "/actions";
    }

    protected Map<String, Object> toPayload(BrowserAction action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action.action());
        if (action.sessionId() != null) payload.put("session_id", action.sessionId());
        if (action.url() != null) payload.put("url", action.url());
        if (action.target() != null) payload.put("target", action.target());
        if (action.text() != null) payload.put("text", action.text());
        if (action.instruction() != null) payload.put("instruction", action.instruction());
        if (action.actor() != null) payload.put("actor", action.actor());
        if (action.reason() != null) payload.put("reason", action.reason());
        return payload;
    }

    protected BrowserActionResult parse(BrowserAction action, String body) throws Exception {
        JsonNode json = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        boolean ok = !json.has("ok") || json.get("ok").asBoolean();
        if (json.has("error")) ok = false;

        String sessionId = text(json, "session_id", text(json, "sessionId", action.sessionId()));
        String url = text(json, "url", action.url());
        String title = text(json, "title", null);
        String content = text(json, "content", text(json, "text", null));
        String message = text(json, "message", text(json, "error", ok ? providerName + " action completed" : providerName + " action failed"));
        List<Map<String, Object>> actions = List.of();
        if (json.has("actions") && json.get("actions").isArray()) {
            actions = MAPPER.convertValue(json.get("actions"), new TypeReference<List<Map<String, Object>>>() {});
        }
        return new BrowserActionResult(ok, sessionId, url, title, content, message, actions);
    }

    protected URI resolve(String path) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + suffix);
    }

    protected String providerName() { return providerName; }
    protected String endpoint() { return endpoint; }

    private static String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
