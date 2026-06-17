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

class DashboardBusinessFoundationEvolutionProposalPreviewRouteTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void previewsEvolutionProposalGovernanceReadOnly() throws Exception {
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

            String proposalJson = """
                {
                  "proposalId":"evp-1",
                  "scenarioId":"after-sales-ticket",
                  "teamId":"after-sales",
                  "sourceInsightId":"insight-1",
                  "title":"补强售后政策上下文",
                  "finding":"失败集中在上下文不足导致政策边界识别错误",
                  "proposedChange":"补充签收时间与退款阈值检查步骤",
                  "expectedBenefit":"降低售后误判率",
                  "evidence":{"failedRuns":3},
                  "metadata":{"rootCause":"INSUFFICIENT_CONTEXT","severity":"MEDIUM"}
                }
                """;
            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/evolution-proposals"))
                .POST(HttpRequest.BodyPublishers.ofString(proposalJson))
                .header("Content-Type", "application/json")).statusCode());

            HttpResponse<String> preview = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/evolution-proposals/preview"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"proposalId\":\"evp-1\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(200, preview.statusCode());
            JSONObject body = JSON.parseObject(preview.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("evp-1", body.getString("proposalId"));
            assertEquals("evp-1", body.getJSONObject("failureCase").getString("id"));
            assertEquals("foundation:evolution-proposal-approval", body.getJSONObject("approvalCard").getJSONObject("metadata").getString("source"));
            assertEquals("evolution-proposal:evp-1", body.getJSONObject("delegatedTaskEnvelope").getString("run_id"));

            var tenant = tenantManager.getTenant("customer-service");
            assertEquals(0, tenant.getEvolutionEngine().getTotalFailures(), "preview must not record failure learning");
            assertTrue(tenant.getDelegatedTaskStore().list().stream()
                .noneMatch(task -> "evolution-proposal:evp-1".equals(task.envelope().runId())),
                "preview must not create delegated tasks");
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    void evolutionProposalPreviewRequiresWorkspaceAndProposalId() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager, GatewayRuntimeStatus::disconnected);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = send(client, server.getSessionToken(), HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/business/foundation/evolution-proposals/preview"))
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
