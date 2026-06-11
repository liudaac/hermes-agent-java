package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
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

class OrgControlCenterBrowserTimelineTest {

    @Test
    void browserTimelineSurfacesAllowedAndDeniedBrowserActions() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "browser-timeline-" + System.nanoTime());
        TenantManagerConfig cfg = TenantManagerConfig.builder().enableIdleCleanup(false).autoLoadExisting(false).build();
        TenantManager manager = new TenantManager(dir, cfg);

        String tenantId = "browser-timeline-" + System.nanoTime();
        TenantProvisioningRequest request = new TenantProvisioningRequest(tenantId, "test");
        TenantContext tenant = manager.createTenant(request);

        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);
        dispatcher.dispatch("browser_bridge", Map.of(
            "action", "open",
            "url", "https://example.com",
            "actor", "dashboard",
            "reason", "timeline allowed action"
        ));
        dispatcher.dispatch("browser_bridge", Map.of(
            "action", "click",
            "target", "Delete account button",
            "actor", "dashboard",
            "reason", "timeline denied action"
        ));

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        OrgControlCenterHandler handler = new OrgControlCenterHandler(manager);
        app.get("/api/org/control/browser", handler::browserTimeline);
        app.get("/api/org/control/overview", handler::overview);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser?n=20")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            JSONArray rows = body.getJSONArray("browser_timeline");
            assertNotNull(rows);
            assertTrue(rows.stream().map(o -> (JSONObject) o).anyMatch(
                row -> tenantId.equals(row.getString("tenant_id")) && "open".equals(row.getString("action")) && !row.getBooleanValue("denied")));
            assertTrue(rows.stream().map(o -> (JSONObject) o).anyMatch(
                row -> tenantId.equals(row.getString("tenant_id")) && "click".equals(row.getString("action")) && row.getBooleanValue("denied") && row.getString("deny_reason").contains("requires explicit confirmation")));

            HttpResponse<String> overview = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/overview")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, overview.statusCode());
            assertTrue(JSON.parseObject(overview.body()).getLongValue("browser_actions") >= 2);
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