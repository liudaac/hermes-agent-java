package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void factoryCreatesOfficialKimiWebBridgeDiscoveryAdapter() {
        BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("webbridge", "http://127.0.0.1:1", 1000));
        assertInstanceOf(KimiOfficialWebBridgeAdapter.class, bridge);
        assertEquals("kimi-webbridge", bridge.describe().get("provider"));
        assertEquals("skill-backed", bridge.describe().get("mode"));
    }

    @Test
    void factoryCreatesHermesWebBridgeContractHttpAdapter() {
        BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("webbridge-contract", "http://127.0.0.1:1", 1000));
        assertInstanceOf(WebBridgePluginBrowserBridge.class, bridge);
        assertEquals("webbridge-contract", bridge.describe().get("provider"));
    }


    @Test
    void officialKimiWebBridgeReadsStatusAndReportsSkillBackedMode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status", exchange -> {
            byte[] response = MAPPER.writeValueAsBytes(Map.of(
                "running", true,
                "extension_connected", false,
                "port", 10086,
                "version", "v-test"
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("webbridge", endpoint, 3000));
            var health = bridge.healthCheck();
            assertTrue(health.ok());
            assertEquals("skill-backed", health.meta().get("mode"));
            assertEquals(false, health.meta().get("extension_connected"));
            Path skills = Files.createTempDirectory("kimi-skill");
            Path skillDir = skills.resolve("kimi-webbridge");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), "Kimi WebBridge skill", StandardCharsets.UTF_8);
            String oldSkillPaths = System.getProperty("hermes.skills.paths");
            Map<String, Object> capabilities;
            try {
                System.setProperty("hermes.skills.paths", skills.toString());
                capabilities = bridge.capabilities();
            } finally {
                if (oldSkillPaths == null) System.clearProperty("hermes.skills.paths"); else System.setProperty("hermes.skills.paths", oldSkillPaths);
            }
            assertEquals("skill-backed", capabilities.get("mode"));
            assertEquals(false, capabilities.get("usable"));
            assertEquals(true, capabilities.get("skill_installed"));
            assertEquals(skillDir.toAbsolutePath().normalize().toString(), capabilities.get("skill_path"));
            var execute = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "test", "should route to skill"));
            assertFalse(execute.ok());
            assertEquals("skill_backed_provider", execute.errorCode());
        } finally {
            server.stop(0);
        }
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
            BrowserBridge bridge = BrowserBridgeFactory.create(new BrowserBridgeConfig("webbridge-contract", endpoint, 3000));
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
