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

class DashboardBusinessFoundationScenarioPlanRouteTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void previewsScenarioIntentPlanReadOnly() throws Exception {
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

            String scenarioJson = """
                {
                  "scenarioId":"after-sales-ticket",
                  "name":"售后工单处理",
                  "description":"自动分析售后退款工单并生成建议",
                  "entryTeamId":"after-sales",
                  "successCriteria":["正确识别退款类型"],
                  "approvalRules":["高风险退款人工审批"]
                }
                """;
            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/scenarios"))
                .POST(HttpRequest.BodyPublishers.ofString(scenarioJson))
                .header("Content-Type", "application/json")).statusCode());

            HttpResponse<String> preview = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/scenarios/plan"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"scenarioId\":\"after-sales-ticket\",\"userInput\":\"refund order\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(200, preview.statusCode());
            JSONObject body = JSON.parseObject(preview.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject request = body.getJSONObject("intentRequest");
            assertEquals("after-sales", request.getString("preferredTeamId"));
            assertTrue(request.getString("intent").contains("refund order"));
            JSONObject plan = body.getJSONObject("plan");
            assertEquals("after-sales", plan.getString("preferred_team_id"));
            assertTrue(plan.getString("status").contains("planned") || plan.getString("status").contains("no teammates"));

            HttpResponse<String> runs = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/runs"))
                .GET());
            assertEquals(200, runs.statusCode());
            assertEquals(0, JSON.parseObject(runs.body()).getIntValue("total"), "plan preview must not create BusinessRun records");
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    void scenarioPlanPreviewRequiresWorkspaceAndScenario() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager, GatewayRuntimeStatus::disconnected);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = send(client, server.getSessionToken(), HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/business/foundation/scenarios/plan"))
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
