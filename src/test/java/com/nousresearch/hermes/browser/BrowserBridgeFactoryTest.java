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


}
