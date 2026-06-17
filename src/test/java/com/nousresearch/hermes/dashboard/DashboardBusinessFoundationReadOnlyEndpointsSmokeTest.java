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

class DashboardBusinessFoundationReadOnlyEndpointsSmokeTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void allReadOnlyFoundationEndpointsShareSameWorkspaceFixtureWithoutMutation() throws Exception {
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
                .POST(HttpRequest.BodyPublishers.ofString("{\"assetId\":\"base\",\"name\":\"Base Prompt\",\"purpose\":\"smoke\",\"content\":\"先判断工单类型，再匹配政策。\"}"))
                .header("Content-Type", "application/json")).statusCode());

            String teamJson = "{\n"
                + "  \"teamId\":\"after-sales\",\n"
                + "  \"name\":\"售后团队\",\n"
                + "  \"description\":\"处理售后\",\n"
                + "  \"scenario\":\"售后\",\n"
                + "  \"scenarioId\":\"after-sales-ticket\",\n"
                + "  \"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"responsibility\":\"处理分类\",\"allowedTools\":[\"missing.tool\"]}],\n"
                + "  \"promptAssetRefs\":[\"prompt://base\"],\n"
                + "  \"operatingManual\":\"manual\"\n"
                + "}";
            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(teamJson))
                .header("Content-Type", "application/json")).statusCode());

            String scenarioJson = "{\n"
                + "  \"scenarioId\":\"after-sales-ticket\",\n"
                + "  \"name\":\"售后工单处理\",\n"
                + "  \"description\":\"自动分析售后退款工单\",\n"
                + "  \"entryTeamId\":\"after-sales\",\n"
                + "  \"successCriteria\":[\"正确识别退款类型\"],\n"
                + "  \"approvalRules\":[\"高风险退款人工审批\"]\n"
                + "}";
            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/scenarios"))
                .POST(HttpRequest.BodyPublishers.ofString(scenarioJson))
                .header("Content-Type", "application/json")).statusCode());

            String proposalJson = "{\n"
                + "  \"proposalId\":\"evp-1\",\n"
                + "  \"scenarioId\":\"after-sales-ticket\",\n"
                + "  \"teamId\":\"after-sales\",\n"
                + "  \"title\":\"补强售后政策上下文\",\n"
                + "  \"finding\":\"上下文不足\",\n"
                + "  \"proposedChange\":\"补充政策检查步骤\",\n"
                + "  \"expectedBenefit\":\"降低误判率\",\n"
                + "  \"metadata\":{\"rootCause\":\"INSUFFICIENT_CONTEXT\",\"severity\":\"MEDIUM\"}\n"
                + "}";
            assertEquals(201, send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/evolution-proposals"))
                .POST(HttpRequest.BodyPublishers.ofString(proposalJson))
                .header("Content-Type", "application/json")).statusCode());

            var run = tenantManager.getTenant("customer-service")
                .getIntentOrchestrator()
                .execute("Scenario: 售后工单处理\nUser request: refund order", "after-sales");

            int teamCountBefore = totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/team-blueprints")));
            int scenarioCountBefore = totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/scenarios")));
            int promptCountBefore = totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/prompt-assets")));
            int proposalCountBefore = totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/evolution-proposals")));
            int runCountBefore = totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/runs")));

            JSONObject diagnostics = okBody(send(client, token, get(baseUrl + "/api/v1/business/foundation/diagnostics")));
            assertEquals("BusinessPortalFoundationFacade", diagnostics.getJSONObject("diagnostics").getString("boundary"));

            JSONObject validation = okBody(send(client, token, post(baseUrl + "/api/v1/business/foundation/team-blueprints/validate",
                "{\"workspaceId\":\"customer-service\",\"teamId\":\"after-sales\"}")));
            assertEquals("after-sales", validation.getJSONObject("validation").getString("teamId"));

            JSONObject promptContext = okBody(send(client, token, post(baseUrl + "/api/v1/business/foundation/prompt-context/preview",
                "{\"workspaceId\":\"customer-service\",\"promptAssetRefs\":[\"prompt://base\"],\"taskContext\":\"refund ticket\"}")));
            assertTrue(promptContext.getString("rendered").contains("Base Prompt"));

            JSONObject plan = okBody(send(client, token, post(baseUrl + "/api/v1/business/foundation/scenarios/plan",
                "{\"workspaceId\":\"customer-service\",\"scenarioId\":\"after-sales-ticket\",\"userInput\":\"refund order\"}")));
            assertEquals("after-sales", plan.getJSONObject("intentRequest").getString("preferredTeamId"));

            JSONObject runProjection = okBody(send(client, token, post(baseUrl + "/api/v1/business/foundation/runs/project",
                "{\"workspaceId\":\"customer-service\",\"intentRunId\":\"" + run.runId + "\",\"scenarioId\":\"after-sales-ticket\",\"scenarioName\":\"售后工单处理\"}")));
            assertEquals("intent://" + run.runId, runProjection.getJSONObject("projection").getString("technicalTraceRef"));

            JSONObject insights = okBody(send(client, token, post(baseUrl + "/api/v1/business/foundation/insights/project",
                "{\"workspaceId\":\"customer-service\",\"limit\":10}")));
            assertEquals("customer-service", insights.getJSONObject("summary").getString("workspaceId"));

            JSONObject proposalPreview = okBody(send(client, token, post(baseUrl + "/api/v1/business/foundation/evolution-proposals/preview",
                "{\"workspaceId\":\"customer-service\",\"proposalId\":\"evp-1\"}")));
            assertEquals("evp-1", proposalPreview.getJSONObject("failureCase").getString("id"));

            assertEquals(teamCountBefore, totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/team-blueprints"))),
                "read-only endpoints must not alter team blueprints");
            assertEquals(scenarioCountBefore, totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/scenarios"))),
                "read-only endpoints must not alter scenarios");
            assertEquals(promptCountBefore, totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/prompt-assets"))),
                "read-only endpoints must not alter prompt assets");
            assertEquals(proposalCountBefore, totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/evolution-proposals"))),
                "read-only endpoints must not alter evolution proposals");
            assertEquals(runCountBefore, totalCount(send(client, token, get(baseUrl + "/api/v1/workspaces/customer-service/runs"))),
                "read-only endpoints must not create BusinessRun records");

            var tenant = tenantManager.getTenant("customer-service");
            assertEquals(0, tenant.getEvolutionEngine().getTotalFailures(),
                "evolution-proposals/preview must not record failure learning");
            assertTrue(tenant.getDelegatedTaskStore().list().stream()
                .noneMatch(task -> "evolution-proposal:evp-1".equals(task.envelope().runId())),
                "evolution-proposals/preview must not create delegated tasks");
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    private static JSONObject okBody(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        JSONObject body = JSON.parseObject(response.body());
        assertTrue(body.getBooleanValue("ok"), response.body());
        return body;
    }

    private static int totalCount(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.parseObject(response.body()).getIntValue("total");
    }

    private static HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).GET();
    }

    private static HttpRequest.Builder post(String url, String body) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
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
