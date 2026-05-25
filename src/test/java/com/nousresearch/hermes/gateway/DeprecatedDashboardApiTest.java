package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSON;
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

class DeprecatedDashboardApiTest {

    @Test
    @DisplayName("Gateway dashboard-style routes should respond with 410 Gone pointing at DashboardServer")
    void deprecatedRoutesReturnGone() throws Exception {
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        DeprecatedDashboardApi.register(app);

        int port = freePort();
        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> tenants = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/tenants")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(410, tenants.statusCode());
            JSONObject body = JSON.parseObject(tenants.body());
            assertTrue(body.getBooleanValue("deprecated"));
            assertEquals("/api/tenants", body.getString("path"));
            assertTrue(body.getString("error").contains("Dashboard"));
            assertTrue(body.getString("canonical").contains("DashboardServer"));

            HttpResponse<String> cron = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/cron")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(410, cron.statusCode());

            HttpResponse<String> restart = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/actions/restart-gateway"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(410, restart.statusCode());
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
