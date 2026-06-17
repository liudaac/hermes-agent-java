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

class DashboardBusinessFoundationValidationRouteTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void validatesExistingTeamBlueprintReadOnly() throws Exception {
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

            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/prompt-assets"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"assetId\":\"base\",\"name\":\"Base\",\"purpose\":\"test\",\"content\":\"prompt\"}"))
                .header("Content-Type", "application/json")).statusCode());

            String teamJson = """
                {
                  "teamId":"after-sales",
                  "name":"售后团队",
                  "description":"处理售后",
                  "scenario":"售后",
                  "scenarioId":"after-sales-ticket",
                  "agents":[{"agentId":"classifier","displayName":"分类员","responsibility":"处理分类","allowedTools":["missing.tool"]}],
                  "promptAssetRefs":["prompt://base"],
                  "operatingManual":"manual"
                }
                """;
            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(teamJson))
                .header("Content-Type", "application/json")).statusCode());

            HttpResponse<String> validate = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/team-blueprints/validate"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"teamId\":\"after-sales\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(200, validate.statusCode());
            JSONObject body = JSON.parseObject(validate.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject validation = body.getJSONObject("validation");
            assertFalse(validation.getBooleanValue("valid"));
            assertEquals("after-sales", validation.getString("teamId"));
            assertTrue(validation.getJSONArray("findings").stream()
                .map(JSONObject.class::cast)
                .anyMatch(finding -> "requested_tool_unavailable".equals(finding.getString("code"))));

            HttpResponse<String> teams = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/team-blueprints"))
                .GET());
            assertEquals(200, teams.statusCode());
            assertEquals(1, JSON.parseObject(teams.body()).getIntValue("total"), "validation preview must not create/modify team blueprints");
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    void validationPreviewRequiresWorkspaceIdAndTeamId() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager, GatewayRuntimeStatus::disconnected);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = send(client, server.getSessionToken(), HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/business/foundation/team-blueprints/validate"))
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
