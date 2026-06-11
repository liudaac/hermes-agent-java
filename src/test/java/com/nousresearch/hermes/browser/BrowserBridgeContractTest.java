package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void httpBridgeSupportsCustomPathsHealthAndCapabilities() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] received = new String[1];
        server.createContext("/v1/action", exchange -> {
            received[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = MAPPER.writeValueAsBytes(Map.of(
                "ok", true,
                "protocol", "hermes.browser.v1",
                "session_id", "s-contract",
                "url", "https://example.com",
                "message", "opened",
                "meta", Map.of("engine", "test-daemon")
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/health", exchange -> {
            byte[] response = MAPPER.writeValueAsBytes(Map.of("ok", true, "status", "ready"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/capabilities", exchange -> {
            byte[] response = MAPPER.writeValueAsBytes(Map.of(
                "ok", true,
                "provider", "test-daemon",
                "actions", List.of("open", "observe"),
                "features", List.of("cookies", "real-browser")
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("kimi", endpoint, 3000, "/v1/action", "/v1/health", "/v1/capabilities"));

            var health = bridge.healthCheck();
            assertTrue(health.ok());
            assertEquals("ready", health.meta().get("status"));

            var capabilities = bridge.capabilities();
            assertEquals(true, capabilities.get("ok"));
            assertEquals("test-daemon", capabilities.get("provider"));
            assertTrue(capabilities.get("actions").toString().contains("observe"));

            var result = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "operator", "contract test"));
            assertTrue(result.ok());
            assertEquals("s-contract", result.sessionId());
            assertEquals("test-daemon", result.meta().get("engine"));
            assertNotNull(received[0]);
            assertTrue(received[0].contains("\"protocol\":\"hermes.browser.v1\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpBridgeClassifiesProviderErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actions", exchange -> {
            byte[] response = "missing session".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("openclaw", endpoint, 3000));
            var result = bridge.execute(new BrowserAction("click", "missing", null, "button", null, null, "operator", "contract error"));
            assertFalse(result.ok());
            assertEquals("session_missing", result.errorCode());
            assertEquals(409, result.meta().get("status_code"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpBridgeClassifiesDaemonUnavailable() {
        BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("kimi", "http://127.0.0.1:9", 2000));
        var result = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "operator", "daemon down"));
        assertFalse(result.ok());
        assertTrue(List.of("daemon_unavailable", "bridge_unavailable").contains(result.errorCode()));
    }
}
