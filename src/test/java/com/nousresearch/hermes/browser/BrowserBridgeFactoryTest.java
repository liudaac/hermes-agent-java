package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeFactoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void factoryDefaultsToMock() {
        BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("mock", "", 1000));
        assertInstanceOf(MockBrowserBridge.class, bridge);
        var result = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "test", "default mock"));
        assertTrue(result.ok());
        assertTrue(result.sessionId().startsWith("mock-"));
    }

    @Test
    void factoryCreatesKimiHttpAdapter() {
        BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("kimi", "http://127.0.0.1:1", 1000));
        assertInstanceOf(KimiWebBridgeAdapter.class, bridge);
    }

    @Test
    void unknownProviderReturnsStructuredError() {
        BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("weird", "", 1000));
        var result = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "test", "unknown"));
        assertFalse(result.ok());
        assertTrue(result.message().contains("Unknown browser bridge provider"));
    }

    @Test
    void httpAdapterPostsCanonicalBrowserAction() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] received = new String[1];
        server.createContext("/actions", exchange -> {
            received[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = MAPPER.writeValueAsBytes(Map.of(
                "ok", true,
                "session_id", "daemon-session-1",
                "url", "https://example.com",
                "title", "Daemon Page",
                "content", "Hello from daemon",
                "message", "opened"
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("kimi", endpoint, 3000));
            var result = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "operator", "integration test"));
            assertTrue(result.ok());
            assertEquals("daemon-session-1", result.sessionId());
            assertEquals("Daemon Page", result.title());
            assertNotNull(received[0]);
            assertTrue(received[0].contains("\"action\":\"open\""));
            assertTrue(received[0].contains("\"actor\":\"operator\""));
        } finally {
            server.stop(0);
        }
    }
}
