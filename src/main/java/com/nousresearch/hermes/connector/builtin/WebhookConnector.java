package com.nousresearch.hermes.connector.builtin;

import com.nousresearch.hermes.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * E3: Webhook Connector.
 *
 * <p>Allows Hermes agents to send outgoing webhooks to external systems.
 * Useful for triggering CI/CD pipelines, sending notifications to Slack/DingTalk,
 * or calling any external webhook endpoint.</p>
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>send - POST JSON payload to a URL</li>
 *   <li>notify - Send a simple notification (title + message) to a URL</li>
 * </ul>
 */
public class WebhookConnector implements Connector {

    private static final Logger logger = LoggerFactory.getLogger(WebhookConnector.class);

    private String defaultUrl;
    private String defaultSecret;
    private final java.net.http.HttpClient httpClient;

    public WebhookConnector() {
        this.httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String getName() { return "webhook"; }

    @Override
    public String getLabel() { return "Webhook"; }

    @Override
    public String getDescription() {
        return "Send outgoing webhooks to external systems. Supports custom payloads and HMAC signing.";
    }

    @Override
    public boolean testConnection() {
        return defaultUrl != null && !defaultUrl.isBlank();
    }

    @Override
    public Map<String, Object> execute(String operation, Map<String, Object> params) {
        String url = (String) params.getOrDefault("url", defaultUrl);
        if (url == null || url.isBlank()) {
            return Map.of("success", false, "error", "URL is required");
        }

        try {
            String payload;
            if ("send".equals(operation)) {
                payload = params.containsKey("payload")
                    ? com.alibaba.fastjson2.JSON.toJSONString(params.get("payload"))
                    : com.alibaba.fastjson2.JSON.toJSONString(params);
            } else if ("notify".equals(operation)) {
                String title = (String) params.getOrDefault("title", "Notification");
                String message = (String) params.getOrDefault("message", "");
                payload = com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                    "title", title, "message", message, "source", "hermes"));
            } else {
                return Map.of("success", false, "error", "Unknown operation: " + operation);
            }

            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .timeout(java.time.Duration.ofSeconds(15));

            // HMAC signing
            String secret = (String) params.getOrDefault("secret", defaultSecret);
            if (secret != null && !secret.isBlank()) {
                String signature = hmacSha256(secret, payload);
                builder.header("X-Hermes-Signature", "sha256=" + signature);
            }

            java.net.http.HttpResponse<String> response = httpClient.send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

            return Map.of(
                "success", response.statusCode() >= 200 && response.statusCode() < 300,
                "statusCode", response.statusCode(),
                "response", response.body()
            );

        } catch (Exception e) {
            logger.error("Webhook send failed: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Override
    public List<ConnectorOperation> getSupportedOperations() {
        return List.of(
            new ConnectorOperation("send", "Send Webhook", "Send a custom JSON payload to a URL",
                Map.of("url", Map.of("type", "string"), "payload", Map.of("type", "object")),
                Map.of("success", Map.of("type", "boolean"), "statusCode", Map.of("type", "integer"))),
            new ConnectorOperation("notify", "Send Notification", "Send a title+message notification",
                Map.of("url", Map.of("type", "string"), "title", Map.of("type", "string"),
                       "message", Map.of("type", "string")),
                Map.of("success", Map.of("type", "boolean")))
        );
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("defaultUrl", Map.of("type", "string", "required", false, "label", "Default Webhook URL"));
        schema.put("defaultSecret", Map.of("type", "string", "required", false, "label", "Default Signing Secret"));
        return schema;
    }

    @Override
    public void configure(Map<String, Object> config) {
        this.defaultUrl = (String) config.get("defaultUrl");
        this.defaultSecret = (String) config.get("defaultSecret");
    }

    @Override
    public boolean isHealthy() {
        return testConnection();
    }

    private static String hmacSha256(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }
}
