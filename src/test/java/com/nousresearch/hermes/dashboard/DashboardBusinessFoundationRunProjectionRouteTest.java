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

class DashboardBusinessFoundationRunProjectionRouteTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void projectsExistingIntentRunReadOnly() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager, GatewayRuntimeStatus::disconnected);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String token = server.getSessionToken();
            String baseUrl = "http://127.0.0.1:" + port;

            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"name\":\"客服业务空间\"}"))
                .header("Content-Type", "application/json")).statusCode());

            var run = tenantManager.getTenant("customer-service")
                .getIntentOrchestrator()
                .execute("Scenario: 售后工单处理\nUser request: refund order", "after-sales");
            Thread.sleep(100);

            HttpResponse<String> projectionResponse = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/runs/project"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"intentRunId\":\"" + run.runId + "\",\"scenarioId\":\"after-sales-ticket\",\"scenarioName\":\"售后工单处理\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(200, projectionResponse.statusCode());
            JSONObject body = JSON.parseObject(projectionResponse.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject projection = body.getJSONObject("projection");
            assertEquals("intent://" + run.runId, projection.getString("technicalTraceRef"));
            assertEquals("customer-service", projection.getString("workspaceId"));
            assertEquals("after-sales-ticket", projection.getString("scenarioId"));
            assertEquals("foundation:intent-run", projection.getJSONObject("metadata").getString("source"));

            HttpResponse<String> runs = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/runs"))
                .GET());
            assertEquals(200, runs.statusCode());
            assertEquals(0, JSON.parseObject(runs.body()).getIntValue("total"), "projection preview must not create BusinessRun records");
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    void runProjectionRequiresWorkspaceAndIntentRunId() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager, GatewayRuntimeStatus::disconnected);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = send(client, server.getSessionToken(), HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/business/foundation/runs/project"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json"));

            assertEquals(400, response.statusCode());
            assertFalse(JSON.parseObject(response.body()).getBooleanValue("ok"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    private static HttpResponse<String> send(HttpClient client, String token, HttpRequest.Builder builder)
        throws IOException, InterruptedException {
        return client.send(builder.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
