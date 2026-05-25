package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTenantRoutesTest {

    @Test
    @DisplayName("Dashboard tenant API should use tenantId as canonical response field")
    void tenantRoutesUseCanonicalTenantIdField() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(
            port,
            "127.0.0.1",
            new HermesConfig(),
            tenantManager,
            GatewayRuntimeStatus::disconnected
        );
        String tenantId = "tenant-api-test-" + UUID.randomUUID();

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            HttpResponse<String> create = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + tenantId + "\",\"createdBy\":\"test\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, create.statusCode());
            JSONObject created = JSON.parseObject(create.body());
            assertTrue(created.getBooleanValue("success"));
            assertEquals(tenantId, created.getString("tenantId"));
            assertFalse(created.containsKey("id"), "responses should not expose legacy id field");

            HttpResponse<String> list = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants"))
                .GET());
            assertEquals(200, list.statusCode());
            JSONObject listed = JSON.parseObject(list.body());
            JSONArray tenants = listed.getJSONArray("tenants");
            JSONObject listedTenant = tenants.stream()
                .map(JSONObject.class::cast)
                .filter(t -> tenantId.equals(t.getString("tenantId")))
                .findFirst()
                .orElseThrow();
            assertFalse(listedTenant.containsKey("id"), "tenant list should not mix id and tenantId");
            assertTrue(listed.getIntValue("total") >= 1);

            HttpResponse<String> detail = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId))
                .GET());
            assertEquals(200, detail.statusCode());
            JSONObject details = JSON.parseObject(detail.body());
            assertEquals(tenantId, details.getString("tenantId"));
            assertTrue(details.containsKey("quota"));
            assertFalse(details.containsKey("id"), "tenant detail should not mix id and tenantId");

            HttpResponse<String> tenantSkills = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/skills"))
                .GET());
            assertEquals(200, tenantSkills.statusCode());
            JSONObject skills = JSON.parseObject(tenantSkills.body());
            assertEquals(tenantId, skills.getString("tenantId"));
            assertEquals("tenant", skills.getString("scope"));
            assertTrue(skills.containsKey("skills"));
            assertTrue(skills.containsKey("installedSkills"));
            assertTrue(skills.containsKey("total"));
            assertTrue(skills.containsKey("totalSkills"));
        } finally {
            try {
                if (tenantManager.getTenant(tenantId) != null) {
                    tenantManager.destroyTenant(tenantId, false);
                }
            } finally {
                server.stop();
                tenantManager.shutdown();
            }
        }
    }

    @Test
    @DisplayName("Dashboard tenant API should include tenantId in action and error responses")
    void tenantActionsAndErrorsIncludeTenantId() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(
            port,
            "127.0.0.1",
            new HermesConfig(),
            tenantManager,
            GatewayRuntimeStatus::disconnected
        );
        String tenantId = "tenant-action-test-" + UUID.randomUUID();

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            HttpResponse<String> create = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + tenantId + "\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, create.statusCode());

            HttpResponse<String> suspend = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/suspend"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, suspend.statusCode());
            JSONObject suspended = JSON.parseObject(suspend.body());
            assertEquals(tenantId, suspended.getString("tenantId"));
            assertEquals("SUSPENDED", suspended.getString("state"));
            assertFalse(suspended.containsKey("id"));

            HttpResponse<String> resume = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/resume"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, resume.statusCode());
            JSONObject resumed = JSON.parseObject(resume.body());
            assertEquals(tenantId, resumed.getString("tenantId"));
            assertEquals("ACTIVE", resumed.getString("state"));
            assertFalse(resumed.containsKey("id"));

            String missingTenantId = "missing-" + UUID.randomUUID();
            HttpResponse<String> missing = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + missingTenantId))
                .GET());
            assertEquals(404, missing.statusCode());
            JSONObject notFound = JSON.parseObject(missing.body());
            assertEquals(missingTenantId, notFound.getString("tenantId"));
            assertFalse(notFound.containsKey("id"));
        } finally {
            try {
                if (tenantManager.getTenant(tenantId) != null) {
                    tenantManager.destroyTenant(tenantId, false);
                }
            } finally {
                server.stop();
                tenantManager.shutdown();
            }
        }
    }


    @Test
    @DisplayName("Dashboard tenant quota and security updates should be reflected by read APIs")
    void tenantQuotaAndSecurityUpdatesRoundTrip() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(
            port,
            "127.0.0.1",
            new HermesConfig(),
            tenantManager,
            GatewayRuntimeStatus::disconnected
        );
        String tenantId = "tenant-settings-test-" + UUID.randomUUID();

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            HttpResponse<String> create = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + tenantId + "\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, create.statusCode());

            HttpResponse<String> quotaUpdate = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/quota"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"maxDailyRequests\":123,\"maxDailyTokens\":4567,\"maxConcurrentSessions\":8,\"maxMemoryBytes\":2048}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, quotaUpdate.statusCode());
            JSONObject quotaUpdated = JSON.parseObject(quotaUpdate.body());
            assertTrue(quotaUpdated.getBooleanValue("ok"));
            assertEquals(123, quotaUpdated.getJSONObject("quota").getIntValue("maxDailyRequests"));

            HttpResponse<String> quotaRead = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/quota"))
                .GET());
            assertEquals(200, quotaRead.statusCode());
            JSONObject quota = JSON.parseObject(quotaRead.body());
            assertEquals(123, quota.getIntValue("maxDailyRequests"));
            assertEquals(4567L, quota.getLongValue("maxDailyTokens"));
            assertEquals(8, quota.getIntValue("maxConcurrentSessions"));
            assertEquals(2048L, quota.getLongValue("maxMemoryBytes"));

            HttpResponse<String> securityUpdate = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/security"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"allowNetworkAccess\":true,\"allowFileWrite\":false,\"allowedLanguages\":[\"python\",\"java\"],\"deniedPaths\":[\"/etc\"]}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, securityUpdate.statusCode());
            assertTrue(JSON.parseObject(securityUpdate.body()).getBooleanValue("ok"));

            HttpResponse<String> securityRead = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tenants/" + tenantId + "/security"))
                .GET());
            assertEquals(200, securityRead.statusCode());
            JSONObject security = JSON.parseObject(securityRead.body());
            assertTrue(security.getBooleanValue("allowNetworkAccess"));
            assertFalse(security.getBooleanValue("allowFileWrite"));
            assertTrue(security.getJSONArray("allowedLanguages").contains("java"));
            assertTrue(security.getJSONArray("deniedPaths").contains("/etc"));
        } finally {
            try {
                if (tenantManager.getTenant(tenantId) != null) {
                    tenantManager.destroyTenant(tenantId, false);
                }
            } finally {
                server.stop();
                tenantManager.shutdown();
            }
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
