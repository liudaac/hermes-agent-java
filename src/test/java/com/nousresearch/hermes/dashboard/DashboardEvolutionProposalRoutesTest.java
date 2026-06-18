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

class DashboardEvolutionProposalRoutesTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("Full lifecycle: DRAFT → EVALUATING → NEEDS_APPROVAL → APPROVED → APPLIED")
    void fullLifecycleCreatesTeamBlueprintDraft() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            // Setup workspace + team
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"cs\",\"name\":\"客服\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"}]}"))
                .header("Content-Type", "application/json"));

            // 1. Create proposal → DRAFT
            HttpResponse<String> create = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"proposalId\":\"evp-1\",\"teamId\":\"after-sales\",\"title\":\"优化售后\",\"finding\":\"失败集中\",\"proposedChange\":\"补充检查步骤\",\"expectedBenefit\":\"降低误判\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(201, create.statusCode());
            JSONObject created = JSON.parseObject(create.body());
            assertTrue(created.getBooleanValue("ok"));
            assertEquals("DRAFT", created.getJSONObject("proposal").getString("status"));

            // 2. Evaluate → EVALUATING
            HttpResponse<String> eval = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-1/evaluate"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, eval.statusCode());
            assertEquals("EVALUATING", JSON.parseObject(eval.body()).getJSONObject("proposal").getString("status"));

            // 3. Request approval → NEEDS_APPROVAL
            HttpResponse<String> reqApv = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-1/request-approval"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"approvalId\":\"apv-1\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, reqApv.statusCode());
            JSONObject reqApvBody = JSON.parseObject(reqApv.body());
            assertEquals("NEEDS_APPROVAL", reqApvBody.getJSONObject("proposal").getString("status"));
            assertEquals("apv-1", reqApvBody.getJSONObject("proposal").getString("approvalId"));

            // 4. Approve → APPROVED
            HttpResponse<String> approve = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-1/approve"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"owner\",\"reason\":\"looks good\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, approve.statusCode());
            JSONObject approved = JSON.parseObject(approve.body());
            assertEquals("APPROVED", approved.getJSONObject("proposal").getString("status"));
            assertEquals("owner", approved.getJSONObject("proposal").getJSONObject("metadata").getString("approvedBy"));

            // 5. Apply → APPLIED + team blueprint draft created
            HttpResponse<String> apply = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-1/apply"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"targetTeamId\":\"after-sales\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, apply.statusCode());
            JSONObject applied = JSON.parseObject(apply.body());
            assertEquals("APPLIED", applied.getJSONObject("proposal").getString("status"));
            assertNotNull(applied.getJSONObject("proposal").getString("appliedAt"));
            assertEquals("after-sales", applied.getJSONObject("proposal").getString("targetTeamId"));
            assertEquals(2, applied.getJSONObject("proposal").getIntValue("targetDraftVersion"));

            // Verify team blueprint now has v2 DRAFT
            HttpResponse<String> team = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints/after-sales"))
                .GET());
            JSONObject teamBody = JSON.parseObject(team.body()).getJSONObject("team");
            assertEquals(2, teamBody.getJSONArray("versions").size());
            assertEquals("DRAFT", teamBody.getJSONArray("versions").getJSONObject(1).getString("status"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("Reject proposal prevents apply")
    void rejectPreventsApply() throws Exception {
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
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"proposalId\":\"evp-r\",\"title\":\"优化\",\"finding\":\"发现\",\"proposedChange\":\"变更\",\"expectedBenefit\":\"收益\"}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> reject = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-r/reject"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"owner\",\"reason\":\"not now\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, reject.statusCode());
            assertEquals("REJECTED", JSON.parseObject(reject.body()).getJSONObject("proposal").getString("status"));

            HttpResponse<String> apply = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-r/apply"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(409, apply.statusCode());
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("Invalid transition returns 409")
    void invalidTransitionReturns409() throws Exception {
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
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"proposalId\":\"evp-i\",\"title\":\"优化\",\"finding\":\"发现\",\"proposedChange\":\"变更\",\"expectedBenefit\":\"收益\"}"))
                .header("Content-Type", "application/json"));

            // Try to apply DRAFT directly
            HttpResponse<String> apply = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/evolution-proposals/evp-i/apply"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(409, apply.statusCode());
            assertFalse(JSON.parseObject(apply.body()).getBooleanValue("ok"));
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
