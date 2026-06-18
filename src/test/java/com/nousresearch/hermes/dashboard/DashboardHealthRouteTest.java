package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class DashboardHealthRouteTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("GET /health returns structured health status")
    void healthReturnsStructuredStatus() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET());

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertEquals("UP", body.getString("status"));
            assertEquals("0.1.0-SNAPSHOT", body.getString("version"));
            assertNotNull(body.getString("timestamp"));
            assertTrue(body.getLongValue("uptimeMs") >= 0);
            assertEquals(0, body.getIntValue("tenants"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("GET /health reflects tenant count")
    void healthReflectsTenantCount() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            // Create a workspace (which creates a tenant)
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"cs\",\"name\":\"客服\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET());

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertEquals("UP", body.getString("status"));
            assertEquals(1, body.getIntValue("tenants"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    private static HttpResponse<String> send(HttpClient client, String token, HttpRequest.Builder builder)
        throws IOException, InterruptedException {
        return client.send(
            builder.header("Authorization", "Bearer " + token).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
