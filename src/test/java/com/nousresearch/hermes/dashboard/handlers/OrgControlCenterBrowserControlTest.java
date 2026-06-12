package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.browser.contract.BrowserBridgeMockDaemon;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OrgControlCenterBrowserControlTest {

    @Test
    void browserBridgeControlsExposeStatusHealthAndProviderOverride() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "browser-control-" + System.nanoTime());
        TenantManager manager = new TenantManager(dir, TenantManagerConfig.builder().enableIdleCleanup(false).autoLoadExisting(false).build());
        String tenantId = "browser-control-" + System.nanoTime();
        TenantContext tenant = manager.createTenant(new TenantProvisioningRequest(tenantId, "test"));

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        OrgControlCenterHandler handler = new OrgControlCenterHandler(manager);
        app.get("/api/org/control/browser/status", handler::browserStatus);
        app.post("/api/org/control/browser/{tenantId}/health", handler::browserHealth);
        app.get("/api/org/control/browser/{tenantId}/config", handler::browserBridgeConfig);
        app.post("/api/org/control/browser/{tenantId}/reset", handler::resetBrowserBridge);
        app.post("/api/org/control/browser/{tenantId}/clear-config", handler::clearBrowserBridgeConfig);
        app.post("/api/org/control/browser/{tenantId}/provider", handler::configureBrowserProvider);
        app.post("/api/org/control/browser/{tenantId}/contract", handler::browserContractTest);
        app.post("/api/org/control/browser/{tenantId}/probe", handler::browserProviderProbe);
        app.post("/api/org/control/browser/{tenantId}/probe/apply", handler::applyBrowserProbeRecommendation);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> status = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, status.statusCode());
            JSONArray bridges = JSON.parseObject(status.body()).getJSONArray("browser_bridges");
            assertTrue(bridges.stream().map(o -> (JSONObject) o).anyMatch(row -> tenantId.equals(row.getString("tenant_id")) && "mock".equals(row.getString("provider"))));

            HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/health"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"test health\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, health.statusCode());
            assertTrue(JSON.parseObject(health.body()).getBooleanValue("ok"));

            HttpResponse<String> configure = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/provider"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"provider\":\"webbridge\",\"endpoint\":\"http://127.0.0.1:9\",\"reason\":\"test provider\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, configure.statusCode());
            JSONObject body = JSON.parseObject(configure.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("webbridge-plugin", body.getString("provider"));
            assertTrue(tenant.getBrowserBridge().describe().get("provider").toString().contains("webbridge"));

            HttpResponse<String> contract = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/contract"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"test contract\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, contract.statusCode());
            JSONObject report = JSON.parseObject(contract.body());
            assertFalse(report.getJSONArray("checks").isEmpty());
            assertFalse(tenant.getBrowserContractReport().isEmpty());

            try (BrowserBridgeMockDaemon daemon = BrowserBridgeMockDaemon.start(0)) {
                HttpResponse<String> probe = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/probe"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"endpoint\":\"" + daemon.endpoint() + "\",\"reason\":\"test probe\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, probe.statusCode());
                JSONObject probeBody = JSON.parseObject(probe.body());
                assertTrue(probeBody.containsKey("recommended_config"));
                assertTrue(probeBody.getIntValue("score") > 0);
                assertFalse(tenant.getBrowserProbeReport().isEmpty());

                HttpResponse<String> apply = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/probe/apply"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"apply probe\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, apply.statusCode());
                JSONObject applyBody = JSON.parseObject(apply.body());
                assertTrue(applyBody.getBooleanValue("ok"));
                assertTrue(applyBody.getJSONObject("contract_report").getBooleanValue("ok"));
                assertFalse(tenant.getBrowserContractReport().isEmpty());

                HttpResponse<String> config = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, config.statusCode());
                assertTrue(JSON.parseObject(config.body()).getBooleanValue("has_persisted_config"));

                HttpResponse<String> reset = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/reset"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"reset test\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, reset.statusCode());
                assertEquals("mock", JSON.parseObject(reset.body()).getString("provider"));

                HttpResponse<String> clear = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/browser/" + tenantId + "/clear-config"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"actor\":\"dashboard\",\"reason\":\"clear test\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, clear.statusCode());
                JSONObject clearBody = JSON.parseObject(clear.body());
                assertTrue(clearBody.getBooleanValue("ok"));
                assertTrue(clearBody.getJSONObject("provider").getString("provider").contains("mock"));
            }
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
