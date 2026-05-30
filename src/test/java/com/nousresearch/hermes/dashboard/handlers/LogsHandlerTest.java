package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class LogsHandlerTest {

    private Javalin app;
    private LogsHandler handler;
    private String originalHome;
    private Path tempHome;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        originalHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("hermes-logs-test");
        System.setProperty("user.home", tempHome.toString());

        Path logsDir = tempHome.resolve(".hermes/logs");
        Files.createDirectories(logsDir);

        handler = new LogsHandler();
        app = Javalin.create()
            .get("/api/logs/aggregate", handler::getAggregate)
            .delete("/api/logs", handler::deleteLog)
            .sse("/api/logs/tail", handler::tail)
            .start(0);
        port = app.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) app.stop();
        if (handler != null) handler.shutdown();
        System.setProperty("user.home", originalHome);
        // best-effort cleanup
        if (tempHome != null && Files.exists(tempHome)) {
            try (var s = Files.walk(tempHome)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    @DisplayName("aggregate merges lines across files sorted by timestamp prefix")
    void aggregateMergesAndSorts() throws Exception {
        Path logs = tempHome.resolve(".hermes/logs");
        Files.writeString(logs.resolve("a.log"),
            "2026-01-01 00:00:01 INFO  msg-from-a-1\n" +
            "2026-01-01 00:00:03 INFO  msg-from-a-2\n");
        Files.writeString(logs.resolve("b.log"),
            "2026-01-01 00:00:02 INFO  msg-from-b-1\n" +
            "2026-01-01 00:00:04 INFO  msg-from-b-2\n");

        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder()
            .url("http://localhost:" + port + "/api/logs/aggregate?files=a.log,b.log&lines=10")
            .build();
        try (Response resp = client.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            int idxA1 = body.indexOf("msg-from-a-1");
            int idxB1 = body.indexOf("msg-from-b-1");
            int idxA2 = body.indexOf("msg-from-a-2");
            int idxB2 = body.indexOf("msg-from-b-2");
            assertTrue(idxA1 >= 0 && idxB1 >= 0 && idxA2 >= 0 && idxB2 >= 0,
                "All four lines should appear in body: " + body);
            assertTrue(idxA1 < idxB1 && idxB1 < idxA2 && idxA2 < idxB2,
                "Lines must be ordered by leading timestamp; got body: " + body);
        }
    }

    @Test
    @DisplayName("tail SSE emits new lines appended to the file")
    void tailEmitsAppendedLines() throws Exception {
        Path logs = tempHome.resolve(".hermes/logs");
        Path file = logs.resolve("tail.log");
        Files.writeString(file, "2026-01-01 00:00:00 INFO seed\n");

        OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        Request req = new Request.Builder()
            .url("http://localhost:" + port + "/api/logs/tail?file=tail.log")
            .header("Accept", "text/event-stream")
            .build();

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch lineLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

        EventSource source = EventSources.createFactory(client).newEventSource(req, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                events.add(type + ":" + data);
                if ("ready".equals(type)) ready.countDown();
                if ("line".equals(type)) lineLatch.countDown();
            }
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS), "Should receive ready event");

        // Append a new line; tail loop polls every 500ms.
        Files.writeString(file,
            "2026-01-01 00:00:05 INFO appended-line\n",
            StandardOpenOption.APPEND);

        assertTrue(lineLatch.await(5, TimeUnit.SECONDS),
            "Should receive a line event after append; got: " + events);
        source.cancel();

        boolean sawAppended = events.stream().anyMatch(e -> e.contains("appended-line"));
        assertTrue(sawAppended, "Appended line should be delivered; events=" + events);
    }

    @Test
    @DisplayName("deleteLog removes a log file and returns ok")
    void deleteLogRemovesFile() throws Exception {
        Path logs = tempHome.resolve(".hermes/logs");
        Path file = logs.resolve("deleteme.log");
        Files.writeString(file, "2026-01-01 00:00:00 INFO deleteme\n");

        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder()
            .url("http://localhost:" + port + "/api/logs?file=deleteme.log")
            .delete()
            .build();
        try (Response resp = client.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"ok\":true"), body);
            assertTrue(body.contains("\"file\":\"deleteme.log\""), body);
        }
        assertFalse(Files.exists(file), "File should be deleted");
    }

    @Test
    @DisplayName("deleteLog returns 404 for missing file")
    void deleteLogMissingReturns404() throws Exception {
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder()
            .url("http://localhost:" + port + "/api/logs?file=missing.log")
            .delete()
            .build();
        try (Response resp = client.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }
}
