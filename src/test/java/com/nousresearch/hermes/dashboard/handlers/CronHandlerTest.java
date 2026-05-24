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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CronHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Cron dashboard API should support UI CRUD contract")
    void supportsDashboardCrudContract() throws Exception {
        CronHandler handler = new CronHandler(tempDir.resolve("cron-jobs.json"));
        int port = freePort();
        Javalin app = app(handler);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> empty = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs")).GET());
            assertEquals(200, empty.statusCode());
            assertEquals(0, JSON.parseArray(empty.body()).size());

            HttpResponse<String> createdResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"Say hi\",\"schedule\":\"0 9 * * *\",\"name\":\"Morning\",\"deliver\":\"local\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(201, createdResponse.statusCode());
            JSONObject created = JSON.parseObject(createdResponse.body());
            String id = created.getString("id");
            assertNotNull(id);
            assertEquals("Morning", created.getString("name"));
            assertEquals("Say hi", created.getString("prompt"));
            assertEquals("cron", created.getJSONObject("schedule").getString("kind"));
            assertEquals("0 9 * * *", created.getString("schedule_display"));
            assertTrue(created.getBooleanValue("enabled"));
            assertEquals("scheduled", created.getString("state"));

            HttpResponse<String> listedResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs")).GET());
            JSONArray listed = JSON.parseArray(listedResponse.body());
            assertEquals(1, listed.size());
            assertEquals(id, listed.getJSONObject(0).getString("id"));

            HttpResponse<String> pausedResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs/" + id + "/pause"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, pausedResponse.statusCode());
            JSONObject paused = JSON.parseObject(pausedResponse.body()).getJSONObject("job");
            assertFalse(paused.getBooleanValue("enabled"));
            assertEquals("paused", paused.getString("state"));

            HttpResponse<String> resumedResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs/" + id + "/resume"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, resumedResponse.statusCode());
            JSONObject resumed = JSON.parseObject(resumedResponse.body()).getJSONObject("job");
            assertTrue(resumed.getBooleanValue("enabled"));
            assertEquals("scheduled", resumed.getString("state"));

            HttpResponse<String> triggerResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs/" + id + "/trigger"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(501, triggerResponse.statusCode());
            JSONObject trigger = JSON.parseObject(triggerResponse.body());
            assertFalse(trigger.getBooleanValue("ok"));
            assertTrue(trigger.getBooleanValue("unsupported"));
            assertEquals(id, trigger.getString("id"));
            assertNotNull(trigger.getJSONObject("job").getString("last_run_at"));
            assertNotNull(trigger.getJSONObject("job").getString("last_error"));

            HttpResponse<String> deleteResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs/" + id))
                .DELETE());
            assertEquals(200, deleteResponse.statusCode());
            assertTrue(JSON.parseObject(deleteResponse.body()).getBooleanValue("ok"));

            HttpResponse<String> afterDelete = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs")).GET());
            assertEquals(0, JSON.parseArray(afterDelete.body()).size());
        } finally {
            app.stop();
        }
    }

    @Test
    @DisplayName("Cron dashboard API should persist jobs between handler instances")
    void persistsJobs() throws Exception {
        Path store = tempDir.resolve("cron-jobs.json");
        CronHandler handler = new CronHandler(store);
        int port = freePort();
        Javalin app = app(handler);
        String id;

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            HttpResponse<String> createdResponse = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"Relative\",\"schedule\":\"5m\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(201, createdResponse.statusCode());
            JSONObject created = JSON.parseObject(createdResponse.body());
            id = created.getString("id");
            assertEquals("relative", created.getJSONObject("schedule").getString("kind"));
        } finally {
            app.stop();
        }

        CronHandler reloadedHandler = new CronHandler(store);
        int secondPort = freePort();
        Javalin secondApp = app(reloadedHandler);
        try {
            secondApp.start("127.0.0.1", secondPort);
            HttpResponse<String> listedResponse = send(HttpClient.newHttpClient(),
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + secondPort + "/api/cron/jobs")).GET());
            JSONArray listed = JSON.parseArray(listedResponse.body());
            assertEquals(1, listed.size());
            assertEquals(id, listed.getJSONObject(0).getString("id"));
            assertEquals("Relative", listed.getJSONObject(0).getString("prompt"));
        } finally {
            secondApp.stop();
        }
    }

    private static Javalin app(CronHandler handler) {
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/cron/jobs", handler::listJobs);
        app.post("/api/cron/jobs", handler::createJob);
        app.post("/api/cron/jobs/{id}/pause", handler::pauseJob);
        app.post("/api/cron/jobs/{id}/resume", handler::resumeJob);
        app.post("/api/cron/jobs/{id}/trigger", handler::triggerJob);
        app.delete("/api/cron/jobs/{id}", handler::deleteJob);
        return app;
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder)
        throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
