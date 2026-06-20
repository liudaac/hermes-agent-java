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

class DashboardApprovalResumeTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("APPROVED approval + resume-execution proceeds with execution")
    void approvedApprovalCanResumeExecution() throws Exception {
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
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"approvalRules\":[\"always\"]}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"scenarioId\":\"refund\",\"name\":\"退款处理\",\"entryTeamId\":\"after-sales\"}"))
                .header("Content-Type", "application/json"));

            // Execute → blocked by approval
            HttpResponse<String> exec = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/scenarios/refund/execute"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userInput\":\"客户要求退款\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(202, exec.statusCode());
            String approvalId = JSON.parseObject(exec.body()).getString("approvalId");

            // Approve the approval — should auto-resume execution since scenario is linked
            HttpResponse<String> approve = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/approvals/" + approvalId + "/approve"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"owner\",\"reason\":\"approved\"}"))
                .header("Content-Type", "application/json"));

            // Approve may return 200 (approval resolved) or 201 (auto-resumed execution)
            int approveStatus = approve.statusCode();
            assertTrue(approveStatus == 200 || approveStatus == 201,
                "Approve should return 200 or 201, got: " + approveStatus);
            JSONObject approveBody = JSON.parseObject(approve.body());
            assertTrue(approveBody.getBooleanValue("ok"));
            assertEquals("APPROVED", approveBody.getString("status"));

            // If auto-resumed, we should have a runId
            if (approveStatus == 201) {
                assertTrue(approveBody.getBooleanValue("autoResumed"),
                    "201 response should have autoResumed=true");
                assertNotNull(approveBody.getString("runId"),
                    "Auto-resumed response should have runId");
                return; // No need to test manual resume
            }

            // Resume execution (only if auto-resume didn't happen)
            HttpResponse<String> resume = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/approvals/" + approvalId + "/resume-execution"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"scenarioId\":\"refund\",\"userInput\":\"客户要求退款\"}"))
                .header("Content-Type", "application/json"));

            // Should succeed (201) or fail due to missing LLM (500), but NOT 409
            assertNotEquals(409, resume.statusCode(), "Resume should not be blocked");
            JSONObject body = JSON.parseObject(resume.body());
            if (resume.statusCode() == 201) {
                assertTrue(body.getBooleanValue("ok"));
                assertNotNull(body.getString("runId"));
            }
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("PENDING approval cannot resume-execution")
    void pendingApprovalCannotResume() throws Exception {
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

            // Create a manual approval
            HttpResponse<String> create = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/approvals"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"teamId\":\"t\",\"title\":\"test\",\"summary\":\"test\"}"))
                .header("Content-Type", "application/json"));
            String approvalId = JSON.parseObject(create.body()).getString("approvalId");

            // Try to resume without approving
            HttpResponse<String> resume = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/approvals/" + approvalId + "/resume-execution"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"scenarioId\":\"s\",\"userInput\":\"test\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(409, resume.statusCode());
            assertFalse(JSON.parseObject(resume.body()).getBooleanValue("ok"));
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
