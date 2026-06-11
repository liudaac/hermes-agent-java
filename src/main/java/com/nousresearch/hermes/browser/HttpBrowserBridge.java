package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Generic local-daemon HTTP adapter for browser bridges.
 *
 * <p>Canonical daemon contract:</p>
 * <ul>
 *   <li>POST {endpoint}{actionPath} with normalized BrowserAction JSON</li>
 *   <li>GET {endpoint}{healthPath} for health</li>
 *   <li>GET {endpoint}{capabilitiesPath} for provider capabilities</li>
 * </ul>
 */
public class HttpBrowserBridge implements BrowserBridge {
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final String providerName;
    private final String endpoint;
    private final int timeoutMs;
    private final String actionPath;
    private final String healthPath;
    private final String capabilitiesPath;
    private final HttpClient client;
    private volatile Map<String, Object> lastHealth = Map.of();
    private volatile Map<String, Object> lastCapabilities = Map.of();

    public HttpBrowserBridge(String providerName, BrowserBridgeConfig config) {
        this.providerName = providerName;
        this.endpoint = config != null ? blankToNull(config.endpoint()) : null;
        this.timeoutMs = config != null ? config.timeoutMs() : 10000;
        this.actionPath = config != null ? BrowserBridgeConfig.normalizePath(config.actionPath()) : "/actions";
        this.healthPath = config != null ? BrowserBridgeConfig.normalizePath(config.healthPath()) : "/health";
        this.capabilitiesPath = config != null ? BrowserBridgeConfig.normalizePath(config.capabilitiesPath()) : "/capabilities";
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(this.timeoutMs))
            .build();
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", providerName);
        m.put("class", getClass().getName());
        m.put("endpoint", endpoint != null ? endpoint : "");
        m.put("timeout_ms", timeoutMs);
        m.put("action_path", actionPath);
        m.put("health_path", healthPath);
        m.put("capabilities_path", capabilitiesPath);
        m.put("healthy", endpoint != null && !endpoint.isBlank() && !Boolean.FALSE.equals(lastHealth.get("ok")));
        if (!lastHealth.isEmpty()) m.put("last_health", lastHealth);
        if (!lastCapabilities.isEmpty()) m.put("capabilities", lastCapabilities);
        return m;
    }

    @Override
    public BrowserActionResult healthCheck() {
        if (endpoint == null) {
            return BrowserActionResult.error(null, "endpoint_missing", providerName + " endpoint is not configured");
        }
        try {
            HttpResponse<String> response = sendGet(healthPath);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                var error = httpError(null, "health", response.statusCode(), response.body());
                lastHealth = error.toMap();
                return error;
            }
            Map<String, Object> parsed = parseMapOrText(response.body(), "content");
            parsed.putIfAbsent("ok", true);
            parsed.put("status_code", response.statusCode());
            parsed.put("provider", providerName);
            lastHealth = parsed;
            return BrowserActionResult.ok(null, endpoint, providerName, truncate(response.body()), providerName + " bridge is healthy", List.of(), parsed);
        } catch (Exception e) {
            var error = exceptionError(null, "health", e);
            lastHealth = error.toMap();
            return error;
        }
    }

    @Override
    public Map<String, Object> capabilities() {
        if (endpoint == null) return Map.of("ok", false, "error_code", "endpoint_missing", "message", providerName + " endpoint is not configured");
        try {
            HttpResponse<String> response = sendGet(capabilitiesPath);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Map<String, Object> error = httpError(null, "capabilities", response.statusCode(), response.body()).toMap();
                lastCapabilities = error;
                return error;
            }
            Map<String, Object> parsed = parseMapOrText(response.body(), "content");
            parsed.putIfAbsent("ok", true);
            parsed.putIfAbsent("provider", providerName);
            parsed.putIfAbsent("actions", List.of("open", "observe", "click", "type", "extract", "scroll", "press", "submit", "close"));
            parsed.put("status_code", response.statusCode());
            lastCapabilities = parsed;
            return parsed;
        } catch (Exception e) {
            Map<String, Object> error = exceptionError(null, "capabilities", e).toMap();
            lastCapabilities = error;
            return error;
        }
    }

    @Override
    public BrowserActionResult execute(BrowserAction action) {
        if (endpoint == null) {
            return BrowserActionResult.error(action.sessionId(), "endpoint_missing", providerName + " endpoint is not configured");
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
                return httpError(action.sessionId(), "action", response.statusCode(), response.body());
            }
            return parse(action, response.body());
        } catch (Exception e) {
            return exceptionError(action.sessionId(), "action", e);
        }
    }

    protected String actionPath() {
        return actionPath;
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
        payload.put("protocol", "hermes.browser.v1");
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
        String errorCode = text(json, "error_code", text(json, "code", ok ? null : "provider_error"));
        List<Map<String, Object>> actions = List.of();
        if (json.has("actions") && json.get("actions").isArray()) {
            actions = MAPPER.convertValue(json.get("actions"), new TypeReference<List<Map<String, Object>>>() {});
        }
        Map<String, Object> meta = json.has("meta") && json.get("meta").isObject()
            ? MAPPER.convertValue(json.get("meta"), new TypeReference<Map<String, Object>>() {})
            : new LinkedHashMap<>();
        meta.put("provider", providerName);
        meta.put("protocol", text(json, "protocol", "hermes.browser.v1"));
        return new BrowserActionResult(ok, sessionId, url, title, content, message, actions, errorCode, meta);
    }

    protected URI resolve(String path) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + suffix);
    }

    protected String providerName() { return providerName; }
    protected String endpoint() { return endpoint; }

    private HttpResponse<String> sendGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(resolve(path))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("X-Hermes-Browser-Bridge", providerName)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private BrowserActionResult httpError(String sessionId, String operation, int statusCode, String body) {
        String code = switch (statusCode) {
            case 401, 403 -> "auth_required";
            case 404 -> operation.equals("action") ? "selector_not_found" : "endpoint_not_found";
            case 408, 504 -> "navigation_timeout";
            case 409 -> "session_missing";
            default -> "provider_http_error";
        };
        return BrowserActionResult.error(sessionId, code, providerName + " " + operation + " HTTP " + statusCode + ": " + truncate(body), Map.of(
            "provider", providerName,
            "operation", operation,
            "status_code", statusCode,
            "body", truncate(body)
        ));
    }

    private BrowserActionResult exceptionError(String sessionId, String operation, Exception e) {
        String code = classifyException(e);
        return BrowserActionResult.error(sessionId, code, providerName + " " + operation + " unavailable: " + e.getMessage(), Map.of(
            "provider", providerName,
            "operation", operation,
            "exception", e.getClass().getSimpleName()
        ));
    }

    private static String classifyException(Exception e) {
        String name = e.getClass().getName().toLowerCase();
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (e instanceof ConnectException || message.contains("connect") || message.contains("refused")) return "daemon_unavailable";
        if (e instanceof TimeoutException || name.contains("timeout") || message.contains("timeout")) return "navigation_timeout";
        return "bridge_unavailable";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMapOrText(String body, String textField) {
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        try {
            JsonNode json = MAPPER.readTree(body);
            if (json.isObject()) return MAPPER.convertValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {}
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(textField, truncate(body));
        return map;
    }

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
