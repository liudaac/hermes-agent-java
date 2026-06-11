package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrgControlCenterBrowserApprovalTest {

    @Test
    void browserApprovalQueueCanApproveOnceAndExecute() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "browser-approval-" + System.nanoTime());
        TenantManager manager = new TenantManager(dir, TenantManagerConfig.builder().enableIdleCleanup(false).autoLoadExisting(false).build());
        String tenantId = "browser-approval-" + System.nanoTime();
        TenantContext tenant = manager.createTenant(new TenantProvisioningRequest(tenantId, "test"));

        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);
        String opened = dispatcher.dispatch("browser_bridge", Map.of(
            "action", "open",
            "url", "https://example.com/settings",
            "actor", "agent",
            "reason", "prepare approval execution"
        ));
        String sessionId = JSON.parseObject(opened).getString("session_id");
        String denied = dispatcher.dispatch("browser_bridge", Map.of(
            "action", "click",
            "session_id", sessionId,
            "target", "Delete draft button",
            "actor", "agent",
            "reason", "needs human approval"
        ));
        JSONObject deniedJson = JSON.parseObject(denied);
        String approvalId = deniedJson.getString("approval_id");
        assertNotNull(approvalId);

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        OrgControlCenterHandler handler = new OrgControlCenterHandler(manager);
        app.get("/api/org/control/browser/approvals", handler::browserApprovals);
        app.post("/api/org/control/browser/approvals/{tenantId}/{approvalId}/approve", handler::approveBrowserApproval);
        app.post("/api/org/control/browser/approvals/{tenantId}/{approvalId}/reject", handler::rejectBrowserApproval);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/approvals?n=10")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, list.statusCode());
            JSONArray approvals = JSON.parseObject(list.body()).getJSONArray("approvals");
            assertTrue(approvals.stream().map(o -> (JSONObject) o).anyMatch(row -> approvalId.equals(row.getString("id")) && "PENDING".equals(row.getString("status"))));

            HttpResponse<String> approve = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/approvals/" + tenantId + "/" + approvalId + "/approve"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"approve once test\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, approve.statusCode());
            JSONObject body = JSON.parseObject(approve.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals(approvalId, body.getString("approval_id"));
            assertTrue(body.getJSONObject("execution").getBooleanValue("ok"));
            assertEquals("EXECUTED", tenant.getBrowserApprovalQueue().get(approvalId).status().name());
        } finally {
            app.stop();
            manager.shutdown();
        }
    }

    @Test
    void browserApprovalQueueCanReject() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "browser-approval-reject-" + System.nanoTime());
        TenantManager manager = new TenantManager(dir, TenantManagerConfig.builder().enableIdleCleanup(false).autoLoadExisting(false).build());
        String tenantId = "browser-approval-reject-" + System.nanoTime();
        TenantContext tenant = manager.createTenant(new TenantProvisioningRequest(tenantId, "test"));
        var request = tenant.getBrowserApprovalQueue().create(
            new com.nousresearch.hermes.browser.BrowserAction("click", null, null, "Delete account", null, null, "agent", "reject test"),
            Map.of("action", "click", "target", "Delete account"),
            "requires explicit confirmation"
        );

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        OrgControlCenterHandler handler = new OrgControlCenterHandler(manager);
        app.post("/api/org/control/browser/approvals/{tenantId}/{approvalId}/reject", handler::rejectBrowserApproval);
        try {
            app.start("127.0.0.1", port);
            HttpResponse<String> reject = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/org/control/browser/approvals/" + tenantId + "/" + request.id() + "/reject"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"reject test\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, reject.statusCode());
            assertEquals("REJECTED", tenant.getBrowserApprovalQueue().get(request.id()).status().name());
        } finally {
            app.stop();
            manager.shutdown();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
