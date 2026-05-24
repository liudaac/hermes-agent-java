package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class OAuthProvidersHandlerTest {

    @Test
    @DisplayName("OAuth provider API should expose external providers for the dashboard UI")
    void listsExternalProviders() throws Exception {
        OAuthProvidersHandler handler = new OAuthProvidersHandler();
        int port = freePort();
        Javalin app = app(handler);

        try {
            app.start("127.0.0.1", port);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/providers/oauth")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            JSONArray providers = body.getJSONArray("providers");
            assertNotNull(providers);
            assertTrue(providers.size() >= 2);

            JSONObject claude = providers.stream()
                .map(JSONObject.class::cast)
                .filter(provider -> "claude-code".equals(provider.getString("id")))
                .findFirst()
                .orElseThrow();
            assertEquals("Claude Code", claude.getString("name"));
            assertEquals("external", claude.getString("flow"));
            assertNotNull(claude.getString("cli_command"));
            assertNotNull(claude.getString("docs_url"));
            assertNotNull(claude.getJSONObject("status"));
            assertTrue(claude.getJSONObject("status").containsKey("logged_in"));
        } finally {
            app.stop();
        }
    }

    @Test
    @DisplayName("OAuth provider actions should return structured unsupported responses instead of 404")
    void actionsReturnUnsupported() throws Exception {
        OAuthProvidersHandler handler = new OAuthProvidersHandler();
        int port = freePort();
        Javalin app = app(handler);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> start = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/providers/oauth/claude-code/start"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(501, start.statusCode());
            JSONObject unsupported = JSON.parseObject(start.body());
            assertFalse(unsupported.getBooleanValue("ok"));
            assertTrue(unsupported.getBooleanValue("unsupported"));
            assertEquals("claude-code", unsupported.getString("provider"));
            assertEquals("error", unsupported.getString("status"));

            HttpResponse<String> poll = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/providers/oauth/claude-code/poll/session-1"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(501, poll.statusCode());
            JSONObject polled = JSON.parseObject(poll.body());
            assertEquals("session-1", polled.getString("session_id"));
            assertEquals("error", polled.getString("status"));
            assertNotNull(polled.getString("error_message"));

            HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/providers/oauth/missing/start"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, missing.statusCode());
        } finally {
            app.stop();
        }
    }

    private static Javalin app(OAuthProvidersHandler handler) {
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/providers/oauth", handler::listProviders);
        app.delete("/api/providers/oauth/{providerId}", handler::disconnectProvider);
        app.post("/api/providers/oauth/{providerId}/start", handler::startLogin);
        app.post("/api/providers/oauth/{providerId}/submit", handler::submitCode);
        app.get("/api/providers/oauth/{providerId}/poll/{sessionId}", handler::pollSession);
        app.delete("/api/providers/oauth/sessions/{sessionId}", handler::cancelSession);
        return app;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
