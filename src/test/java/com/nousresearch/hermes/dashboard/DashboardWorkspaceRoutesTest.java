package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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

class DashboardWorkspaceRoutesTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("POST /api/v1/workspaces creates workspace and underlying tenant")
    void createWorkspaceAlsoCreatesTenant() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"workspaceId\":\"customer-service\",\"name\":\"客服业务空间\",\"description\":\"售后工单处理\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            assertEquals(201, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("customer-service", body.getString("workspaceId"));
            assertEquals("customer-service", body.getString("tenantId"));
            assertTrue(tenantManager.exists("customer-service"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("GET /api/v1/workspaces returns created workspaces")
    void listWorkspacesReturnsCreatedRecords() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            // Create workspace
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"workspaceId\":\"sales-space\",\"name\":\"销售空间\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            // List workspaces
            HttpResponse<String> list = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .GET());

            assertEquals(200, list.statusCode());
            JSONObject body = JSON.parseObject(list.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONArray workspaces = body.getJSONArray("workspaces");
            assertNotNull(workspaces);
            assertTrue(workspaces.stream()
                .map(JSONObject.class::cast)
                .anyMatch(w -> "sales-space".equals(w.getString("workspaceId"))));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("GET /api/v1/workspaces/{id} returns single workspace")
    void getWorkspaceReturnsRecord() throws Exception {
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
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"workspaceId\":\"get-test\",\"name\":\"测试空间\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> get = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/get-test"))
                .GET());

            assertEquals(200, get.statusCode());
            JSONObject body = JSON.parseObject(get.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject workspace = body.getJSONObject("workspace");
            assertEquals("get-test", workspace.getString("workspaceId"));
            assertEquals("测试空间", workspace.getString("name"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("Duplicate workspace returns 409")
    void duplicateWorkspaceReturns409() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            String payload = "{\"workspaceId\":\"dup-test\",\"name\":\"重复测试\",\"owner\":\"ops\"}";

            HttpResponse<String> first = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json"));
            assertEquals(201, first.statusCode());

            HttpResponse<String> second = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json"));
            assertEquals(409, second.statusCode());
            JSONObject body = JSON.parseObject(second.body());
            assertFalse(body.getBooleanValue("ok"));
            assertEquals("dup-test", body.getString("workspaceId"));
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
