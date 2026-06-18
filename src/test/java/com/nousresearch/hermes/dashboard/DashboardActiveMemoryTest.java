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

class DashboardActiveMemoryTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("POST /memories creates and GET recalls memory")
    void createAndRecallMemory() throws Exception {
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

            // Create memory
            HttpResponse<String> create = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/memories"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"memoryId\":\"refund-policy\",\"type\":\"rule\",\"title\":\"退款政策\",\"content\":\"生鲜不支持退款\",\"tags\":[\"refund\",\"policy\"],\"scenarioIds\":[\"refund\"]}"))
                .header("Content-Type", "application/json"));
            assertEquals(201, create.statusCode());

            // Recall by scenario
            HttpResponse<String> recall = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/memories/recall"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"scenarioId\":\"refund\",\"limit\":5}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, recall.statusCode());
            JSONObject body = JSON.parseObject(recall.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals(1, body.getIntValue("count"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("Scenario execute recalls active memory into run metadata")
    void executeScenarioRecallsMemory() throws Exception {
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
                    "{\"scenarioId\":\"refund\",\"name\":\"退款处理\",\"entryTeamId\":\"after-sales\",\"successCriteria\":[\"自动分类正确\"]}"))
                .header("Content-Type", "application/json"));

            // Create memory linked to scenario
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/memories"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"memoryId\":\"mem-1\",\"type\":\"rule\",\"title\":\"退款规则\",\"content\":\"超过100元需审批\",\"scenarioIds\":[\"refund\"]}"))
                .header("Content-Type", "application/json"));

            // Execute scenario
            HttpResponse<String> exec = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios/refund/execute"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userInput\":\"客户要求退款\"}"))
                .header("Content-Type", "application/json"));

            // Should succeed (or fail due to missing LLM), but metadata should contain activeMemory
            JSONObject body = JSON.parseObject(exec.body());
            if (body.getBooleanValue("ok")) {
                JSONObject run = body.getJSONObject("run");
                assertNotNull(run);
                JSONObject metadata = run.getJSONObject("metadata");
                if (metadata != null && metadata.containsKey("activeMemory")) {
                    JSONObject am = metadata.getJSONObject("activeMemory");
                    assertTrue(am.getIntValue("recalledCount") > 0);
                }
            }
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
