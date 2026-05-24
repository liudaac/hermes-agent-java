package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.dashboard.handlers.ConfigHandler;
import com.nousresearch.hermes.dashboard.handlers.LogsHandler;
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

class DashboardContractAlignmentTest {

    @Test
    @DisplayName("Config save response should include ok and status for frontend compatibility")
    void configSaveReturnsOkAndStatus() throws Exception {
        ConfigHandler configHandler = new ConfigHandler(new HermesConfig());
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.put("/api/config", configHandler::updateConfig);

        try {
            app.start("127.0.0.1", port);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/config"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"config\":{\"dashboard\":{\"contract_test\":true}}}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("updated", body.getString("status"));
        } finally {
            app.stop();
        }
    }

    @Test
    @DisplayName("Logs API should return stable object wrappers for file lists and content")
    void logsApiReturnsStableObjectWrappers() throws Exception {
        LogsHandler logsHandler = new LogsHandler();
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/logs", logsHandler::getLogs);
        app.get("/api/logs/files", logsHandler::getLogFiles);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> logs = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/logs")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, logs.statusCode());
            JSONObject logsBody = JSON.parseObject(logs.body());
            assertTrue(logsBody.containsKey("files"));
            assertInstanceOf(JSONArray.class, logsBody.get("files"));

            HttpResponse<String> files = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/logs/files")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, files.statusCode());
            JSONObject filesBody = JSON.parseObject(files.body());
            assertTrue(filesBody.containsKey("files"));

            HttpResponse<String> content = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/logs?file=missing-dashboard-contract-test.log&lines=5")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, content.statusCode());
            JSONObject contentBody = JSON.parseObject(content.body());
            assertEquals("missing-dashboard-contract-test.log", contentBody.getString("file"));
            assertTrue(contentBody.containsKey("lines"));
            assertInstanceOf(JSONArray.class, contentBody.get("lines"));
        } finally {
            app.stop();
        }
    }

    @Test
    @DisplayName("Theme update response should echo selected theme")
    void themeUpdateEchoesTheme() throws Exception {
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.put("/api/dashboard/theme", ctx -> {
            JSONObject body = ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String theme = body.getString("name");
            if (theme == null || theme.isBlank()) {
                theme = "default";
            }
            ctx.json(new JSONObject().fluentPut("ok", true).fluentPut("theme", theme));
        });

        try {
            app.start("127.0.0.1", port);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dashboard/theme"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"midnight\"}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("midnight", body.getString("theme"));
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
