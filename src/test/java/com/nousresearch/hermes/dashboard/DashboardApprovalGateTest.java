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

class DashboardApprovalGateTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("POST execute with 'always' approval rule returns 202 + approvalId")
    void executeWithAlwaysRuleReturnsApprovalRequired() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            // Setup workspace + team with approval rule + scenario
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"cs\",\"name\":\"客服\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"approvalRules\":[\"always\"]}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"scenarioId\":\"refund\",\"name\":\"退款处理\",\"entryTeamId\":\"after-sales\"}"))
                .header("Content-Type", "application/json"));

            // Execute should return 202 with approvalId
            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios/refund/execute"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userInput\":\"客户要求退款\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(202, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("NEEDS_APPROVAL", body.getString("status"));
            assertNotNull(body.getString("approvalId"));

            // Verify approval was created
            HttpResponse<String> approvals = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/approvals"))
                .GET());
            JSONObject apvBody = JSON.parseObject(approvals.body());
            assertTrue(apvBody.getJSONArray("approvals").stream()
                .map(JSONObject.class::cast)
                .anyMatch(a -> body.getString("approvalId").equals(a.getString("approvalId"))));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("POST execute without approval rules proceeds normally")
    void executeWithoutRulesProceeds() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"cs\",\"name\":\"客服\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"scenarioId\":\"refund\",\"name\":\"退款处理\",\"entryTeamId\":\"after-sales\"}"))
                .header("Content-Type", "application/json"));

            // Execute without approval rules — may fail due to missing LLM but should not return 202
            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios/refund/execute"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userInput\":\"客户要求退款\"}"))
                .header("Content-Type", "application/json"));

            // Should be 201 (success) or 500 (LLM error), NOT 202 (approval blocked)
            assertNotEquals(202, response.statusCode());
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
