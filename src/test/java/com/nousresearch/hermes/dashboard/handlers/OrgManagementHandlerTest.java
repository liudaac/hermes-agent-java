package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OrgManagementHandlerTest {

    @Test
    void orgManagementCanUpsertListAndDeleteAgentRoles() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "org-manage-" + System.nanoTime());
        TenantManager manager = new TenantManager(dir, TenantManagerConfig.builder().enableIdleCleanup(false).autoLoadExisting(false).build());
        String tenantId = "org-manage-tenant-" + System.nanoTime();
        manager.createTenant(new TenantProvisioningRequest(tenantId, "test"));

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        OrgManagementHandler handler = new OrgManagementHandler(manager);
        app.get("/api/org/manage/summary", handler::summary);
        app.get("/api/org/manage/roles", handler::listRoles);
        app.post("/api/org/manage/roles", handler::upsertRole);
        app.delete("/api/org/manage/roles/{tenantId}/{agentId}", handler::deleteRole);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;

            HttpResponse<String> create = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/org/manage/roles"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"tenant_id":"%s","agent_id":"planner","role_name":"Planner","description":"Plans work","level":"LEAD","skills":["planning","routing"],"responsibilities":["break down tasks"],"allowed_tools":["orchestrate_intent"]}
                        """.formatted(tenantId)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, create.statusCode());
            JSONObject created = JSON.parseObject(create.body());
            assertTrue(created.getBooleanValue("ok"));
            assertEquals("Planner", created.getString("name"));

            HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/org/manage/roles?tenantId=" + tenantId)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, list.statusCode());
            JSONArray roles = JSON.parseObject(list.body()).getJSONArray("roles");
            assertEquals(1, roles.size());
            assertEquals("planner", roles.getJSONObject(0).getString("agent_id"));
            assertTrue(roles.getJSONObject(0).getJSONArray("skills").contains("planning"));

            HttpResponse<String> summary = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/org/manage/summary")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, summary.statusCode());
            assertTrue(JSON.parseObject(summary.body()).getLongValue("agent_roles") >= 1);

            HttpResponse<String> delete = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/org/manage/roles/" + tenantId + "/planner"))
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, delete.statusCode());
            assertTrue(JSON.parseObject(delete.body()).getBooleanValue("ok"));
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
