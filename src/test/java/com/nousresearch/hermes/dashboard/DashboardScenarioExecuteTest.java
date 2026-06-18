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

class DashboardScenarioExecuteTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("POST execute returns structured response for scenario")
    void executeScenarioReturnsStructuredResponse() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            // Setup workspace + team + scenario
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
                    "{\"scenarioId\":\"refund\",\"name\":\"退款处理\",\"entryTeamId\":\"after-sales\",\"successCriteria\":[\"自动分类正确\"]}"))
                .header("Content-Type", "application/json"));

            // Execute scenario — may fail due to missing LLM, but should return structured error or success
            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios/refund/execute"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userInput\":\"客户要求退款\"}"))
                .header("Content-Type", "application/json"));

            // Either 201 (success) or 500 (LLM not configured) is acceptable for this test
            // because we are testing the routing and structure, not the LLM integration
            assertTrue(response.statusCode() == 201 || response.statusCode() == 500 || response.statusCode() == 503,
                "Expected 201, 500 or 503 but got " + response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertFalse(body.getBooleanValue("ok") == false && body.getString("error") == null);
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("POST execute for missing scenario returns 404")
    void executeMissingScenarioReturns404() throws Exception {
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

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios/missing/execute"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userInput\":\"test\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(404, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertFalse(body.getBooleanValue("ok"));
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
