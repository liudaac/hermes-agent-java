package com.nousresearch.hermes.browser.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.browser.BrowserAction;
import com.nousresearch.hermes.browser.MockBrowserBridge;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal local daemon implementing BrowserBridge contract v1 for provider testing.
 */
public class BrowserBridgeMockDaemon implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final MockBrowserBridge bridge = new MockBrowserBridge();

    private BrowserBridgeMockDaemon(HttpServer server) {
        this.server = server;
        registerHandlers();
    }

    public static BrowserBridgeMockDaemon start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        BrowserBridgeMockDaemon daemon = new BrowserBridgeMockDaemon(server);
        server.start();
        return daemon;
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void registerHandlers() {
        server.createContext("/health", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("ok", false, "error_code", "method_not_allowed", "message", "GET required"));
                return;
            }
            send(exchange, 200, Map.of("ok", true, "provider", "hermes-contract-mock", "protocol", "hermes.browser.v1", "status", "ready"));
        });
        server.createContext("/capabilities", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("ok", false, "error_code", "method_not_allowed", "message", "GET required"));
                return;
            }
            send(exchange, 200, Map.of(
                "ok", true,
                "provider", "hermes-contract-mock",
                "protocol", "hermes.browser.v1",
                "actions", List.of("open", "observe", "click", "type", "extract", "scroll", "press", "submit", "close"),
                "features", List.of("actions", "health", "capabilities", "mock-sessions")
            ));
        });
        server.createContext("/actions", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("ok", false, "error_code", "method_not_allowed", "message", "POST required"));
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode json = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
                BrowserAction action = new BrowserAction(
                    text(json, "action", "observe"),
                    text(json, "session_id", text(json, "sessionId", null)),
                    text(json, "url", null),
                    text(json, "target", null),
                    text(json, "text", null),
                    text(json, "instruction", null),
                    text(json, "actor", "contract-client"),
                    text(json, "reason", "")
                );
                var result = bridge.execute(action);
                Map<String, Object> payload = new LinkedHashMap<>(result.toMap());
                payload.put("protocol", "hermes.browser.v1");
                if (!result.ok() && "Browser session not found".equals(result.message())) payload.put("error_code", "session_missing");
                send(exchange, result.ok() ? 200 : 409, payload);
            } catch (Exception e) {
                send(exchange, 500, Map.of("ok", false, "error_code", "provider_error", "message", e.getMessage()));
            }
        });
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
