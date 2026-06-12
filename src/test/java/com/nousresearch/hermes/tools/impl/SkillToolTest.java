package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final String oldHermesHome = System.getProperty("hermes.home");
    private final String oldSkillPaths = System.getProperty("hermes.skills.paths");

    @AfterEach
    void restoreProperties() {
        restore("hermes.home", oldHermesHome);
        restore("hermes.skills.paths", oldSkillPaths);
    }

    @Test
    void skillInvokeRoutesKimiWebBridgeToLocalDaemon() throws Exception {
        Path hermesHome = tempDir.resolve("hermes-home");
        Path skills = tempDir.resolve("skills");
        Path skillDir = skills.resolve("kimi-webbridge");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: kimi-webbridge
            description: Kimi WebBridge official skill
            ---

            # Kimi WebBridge
            Use POST /command.
            """, StandardCharsets.UTF_8);
        System.setProperty("hermes.home", hermesHome.toString());
        System.setProperty("hermes.skills.paths", skills.toString());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/command", exchange -> {
            Map<String, Object> body = MAPPER.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            byte[] response = MAPPER.writeValueAsBytes(Map.of(
                "ok", true,
                "echo_action", body.get("action"),
                "echo_session", body.get("session")
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ToolRegistry registry = ToolRegistry.getInstance();
            SkillTool.register(registry);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            String resultJson = registry.dispatch("skill_invoke", Map.of(
                "skill_name", "kimi-webbridge",
                "action", "list_tabs",
                "session", "unit-test",
                "endpoint", endpoint,
                "args", Map.of()
            ));
            Map<String, Object> result = MAPPER.readValue(resultJson, new TypeReference<>() {});
            assertEquals("kimi-webbridge", result.get("skill"));
            assertEquals("kimi-webbridge", result.get("adapter"));
            assertEquals("list_tabs", result.get("action"));
            assertEquals(200, result.get("http_status"));
            assertEquals(skillDir.toAbsolutePath().normalize().toString(), result.get("skill_path"));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) result.get("response");
            assertEquals(true, response.get("ok"));
            assertEquals("list_tabs", response.get("echo_action"));
            assertEquals("unit-test", response.get("echo_session"));
        } finally {
            server.stop(0);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
