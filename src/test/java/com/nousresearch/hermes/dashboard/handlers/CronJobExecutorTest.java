package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CronJobExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Cron parser should compute delays for relative and cron schedules")
    void parsesScheduleExpressions() {
        assertEquals(60, CronJobExecutor.relativeDelaySeconds("1m"));
        assertEquals(3600, CronJobExecutor.relativeDelaySeconds("1h"));
        assertEquals(-1, CronJobExecutor.relativeDelaySeconds("nope"));

        long delay = CronJobExecutor.cronDelaySeconds("* * * * *");
        assertTrue(delay > 0 && delay <= 60, "wildcard cron should fire within next minute, got " + delay);
    }

    @Test
    @DisplayName("Manual trigger should call the runner and persist last_run_at")
    void manualTriggerRunsRunner() throws Exception {
        CronHandler handler = new CronHandler(tempDir.resolve("cron-jobs.json"), false);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        java.lang.reflect.Field execField = CronHandler.class.getDeclaredField("executor");
        execField.setAccessible(true);
        execField.set(handler, new CronJobExecutor(
            job -> {
                calls.incrementAndGet();
                latch.countDown();
                return "ran";
            },
            id -> Optional.empty()));

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.post("/api/cron/jobs", handler::createJob);
        app.post("/api/cron/jobs/{id}/trigger", handler::triggerJob);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> created = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"Say hi\",\"schedule\":\"0 9 * * *\"}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(201, created.statusCode());
            String id = JSON.parseObject(created.body()).getString("id");

            HttpResponse<String> trigger = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron/jobs/" + id + "/trigger"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(latch.await(2, TimeUnit.SECONDS), "runner should have been invoked");
            assertEquals(200, trigger.statusCode());
            JSONObject body = JSON.parseObject(trigger.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals(1, calls.get());
            assertNotNull(body.getJSONObject("job").getString("last_run_at"));
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
