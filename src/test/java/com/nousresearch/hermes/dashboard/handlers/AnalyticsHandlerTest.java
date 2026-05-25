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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Analytics API should aggregate session data")
    void aggregatesSessionData() throws Exception {
        Path dbPath = tempDir.resolve("sessions.db");
        createSchema(dbPath);
        seedSessions(dbPath);

        AnalyticsHandler handler = new AnalyticsHandler(dbPath);
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/analytics/usage", handler::getUsage);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String url = "http://127.0.0.1:" + port + "/api/analytics/usage?days=7";

            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());

            JSONArray daily = body.getJSONArray("daily");
            assertFalse(daily.isEmpty(), "daily should contain aggregated rows");
            JSONObject firstDay = daily.getJSONObject(0);
            assertNotNull(firstDay.getString("day"));
            assertTrue(firstDay.getLongValue("input_tokens") >= 0);
            assertTrue(firstDay.getLongValue("output_tokens") >= 0);
            assertTrue(firstDay.getIntValue("sessions") >= 1);

            JSONArray models = body.getJSONArray("by_model");
            assertFalse(models.isEmpty(), "by_model should contain aggregated rows");
            JSONObject firstModel = models.getJSONObject(0);
            assertNotNull(firstModel.getString("model"));
            assertTrue(firstModel.getIntValue("sessions") >= 1);
            assertTrue(firstModel.getDoubleValue("estimated_cost") > 0,
                "estimated_cost should be > 0 for known model with tokens");

            for (int i = 0; i < daily.size(); i++) {
                assertTrue(daily.getJSONObject(i).getDoubleValue("estimated_cost") >= 0);
            }

            JSONObject totals = body.getJSONObject("totals");
            assertTrue(totals.getIntValue("total_sessions") >= 2);
            assertTrue(totals.getLongValue("total_input") > 0);
            assertTrue(totals.getLongValue("total_output") > 0);
            assertTrue(totals.getDoubleValue("total_estimated_cost") > 0,
                "total_estimated_cost should aggregate model pricing");

            JSONObject skills = body.getJSONObject("skills");
            assertNotNull(skills.getJSONObject("summary"));
            assertTrue(skills.getJSONArray("top_skills").isEmpty());
        } finally {
            app.stop();
        }
    }

    private void createSchema(Path dbPath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            conn.createStatement().execute("""
                CREATE TABLE sessions (
                    id TEXT PRIMARY KEY,
                    source TEXT,
                    model TEXT,
                    title TEXT,
                    started_at INTEGER,
                    ended_at INTEGER,
                    last_active INTEGER,
                    is_active INTEGER DEFAULT 1,
                    message_count INTEGER DEFAULT 0,
                    tool_call_count INTEGER DEFAULT 0,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    preview TEXT
                )
            """);
        }
    }

    private void seedSessions(Path dbPath) throws Exception {
        long now = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO sessions (id, source, model, title, started_at, last_active, input_tokens, output_tokens)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """);
            stmt.setString(1, "s1");
            stmt.setString(2, "cli");
            stmt.setString(3, "gpt-4o");
            stmt.setString(4, "Test 1");
            stmt.setLong(5, now - 86400000);
            stmt.setLong(6, now - 3600000);
            stmt.setInt(7, 120);
            stmt.setInt(8, 80);
            stmt.executeUpdate();

            stmt.setString(1, "s2");
            stmt.setString(2, "telegram");
            stmt.setString(3, "gpt-4o-mini");
            stmt.setString(4, "Test 2");
            stmt.setLong(5, now - 172800000);
            stmt.setLong(6, now - 7200000);
            stmt.setInt(7, 200);
            stmt.setInt(8, 150);
            stmt.executeUpdate();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
