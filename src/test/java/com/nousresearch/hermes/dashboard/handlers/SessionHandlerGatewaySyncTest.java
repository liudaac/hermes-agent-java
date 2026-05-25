package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionHandlerGatewaySyncTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Dashboard sessions should import persisted gateway JSON sessions")
    void importsGatewaySessionsIntoDashboardDatabase() throws Exception {
        Path gatewaySessions = tempDir.resolve("memory").resolve("sessions");
        Files.createDirectories(gatewaySessions);
        Files.writeString(gatewaySessions.resolve("cli_test.json"), """
            {
              "id": "cli_test",
              "lastActivity": 1700000003000,
              "platform": "cli",
              "chat_type": "dm",
              "chat_name": "CLI Test Session",
              "metadata": { "model": "test-model" },
              "messages": [
                { "role": "user", "content": "hello dashboard", "timestamp": 1700000001000 },
                { "role": "assistant", "content": "hello from assistant", "timestamp": 1700000002000 }
              ]
            }
            """);

        SessionHandler handler = new SessionHandler(tempDir.resolve("sessions.db"), gatewaySessions);
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/sessions", handler::getSessions);
        app.get("/api/sessions/search", handler::searchSessions);
        app.get("/api/sessions/{id}/messages", handler::getSessionMessages);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> sessionsResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/sessions?limit=10")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, sessionsResponse.statusCode());
            JSONObject sessionsBody = JSON.parseObject(sessionsResponse.body());
            assertEquals(1, sessionsBody.getIntValue("total"));
            JSONObject session = sessionsBody.getJSONArray("sessions").getJSONObject(0);
            assertEquals("cli_test", session.getString("id"));
            assertEquals("cli", session.getString("source"));
            assertEquals("test-model", session.getString("model"));
            assertEquals("CLI Test Session", session.getString("title"));
            assertEquals(2, session.getIntValue("messageCount"));
            assertTrue(session.getString("preview").contains("assistant"));

            HttpResponse<String> messagesResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/sessions/cli_test/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, messagesResponse.statusCode());
            JSONArray messages = JSON.parseObject(messagesResponse.body()).getJSONArray("messages");
            assertEquals(2, messages.size());
            assertEquals("user", messages.getJSONObject(0).getString("role"));
            assertEquals("hello dashboard", messages.getJSONObject(0).getString("content"));

            HttpResponse<String> searchResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/sessions/search?q=dashboard")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, searchResponse.statusCode());
            JSONArray results = JSON.parseObject(searchResponse.body()).getJSONArray("results");
            assertFalse(results.isEmpty());
            assertEquals("cli_test", results.getJSONObject(0).getString("sessionId"));
        } finally {
            app.stop();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
