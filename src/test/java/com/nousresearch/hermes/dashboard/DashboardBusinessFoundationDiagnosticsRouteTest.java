package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class DashboardBusinessFoundationDiagnosticsRouteTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void exposesReadOnlyBusinessFoundationDiagnosticsThroughDashboardApi() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(
            port,
            "127.0.0.1",
            new HermesConfig(),
            tenantManager,
            GatewayRuntimeStatus::disconnected
        );

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            HttpResponse<String> response = send(client, server.getSessionToken(), HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/diagnostics"))
                .GET());

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject diagnostics = body.getJSONObject("diagnostics");
            assertNotNull(diagnostics);
            assertEquals("BusinessPortalFoundationFacade", diagnostics.getString("boundary"));
            assertTrue(diagnostics.getBooleanValue("facadeReady"));
            assertTrue(diagnostics.getJSONArray("adapters").stream()
                .map(JSONObject.class::cast)
                .anyMatch(adapter -> "insightProjectionAdapter".equals(adapter.getString("name")) && adapter.getBooleanValue("present")));
            assertTrue(diagnostics.getJSONArray("nonGoals").contains("No generation API"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }


    @Test
    void diagnosticsRouteRequiresDashboardAuth() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(
            port,
            "127.0.0.1",
            new HermesConfig(),
            tenantManager,
            GatewayRuntimeStatus::disconnected
        );

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/diagnostics"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertEquals("Unauthorized", body.getString("detail"));
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
