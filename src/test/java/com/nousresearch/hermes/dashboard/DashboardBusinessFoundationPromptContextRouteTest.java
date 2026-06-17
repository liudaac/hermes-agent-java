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

class DashboardBusinessFoundationPromptContextRouteTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void previewsPromptContextReadOnly() throws Exception {
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
                .POST(HttpRequest.BodyPublishers.ofString("{\"assetId\":\"base\",\"name\":\"Base Prompt\",\"purpose\":\"test\",\"content\":\"先判断工单类型，再匹配政策。\"}"))
                .header("Content-Type", "application/json")).statusCode());

            HttpResponse<String> preview = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/business/foundation/prompt-context/preview"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"workspaceId\":\"customer-service\",\"promptAssetRefs\":[\"prompt://base\"],\"taskContext\":\"refund ticket\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(200, preview.statusCode());
            JSONObject body = JSON.parseObject(preview.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("customer-service", body.getString("workspaceId"));
            assertTrue(body.getString("rendered").contains("Base Prompt #v1"));
            assertTrue(body.getString("rendered").contains("先判断工单类型"));
            JSONObject context = body.getJSONObject("promptContext");
            assertEquals("customer-service", context.getString("workspaceId"));
            assertEquals(1, context.getJSONArray("segments").size());
            assertEquals("business-prompt-asset", context.getJSONArray("segments").getJSONObject(0).getString("source"));

            HttpResponse<String> assets = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/customer-service/prompt-assets"))
                .GET());
            assertEquals(200, assets.statusCode());
            assertEquals(1, JSON.parseObject(assets.body()).getIntValue("total"), "prompt preview must not create/modify prompt assets");
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    void promptPreviewRequiresWorkspaceAndRefs() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager, GatewayRuntimeStatus::disconnected);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = send(client, server.getSessionToken(), HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/business/foundation/prompt-context/preview"))
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
