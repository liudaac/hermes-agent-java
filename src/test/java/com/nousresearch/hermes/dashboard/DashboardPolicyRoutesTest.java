package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
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

class DashboardPolicyRoutesTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("GET policy returns workspace policy")
    void getPolicyReturnsRecord() throws Exception {
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

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy"))
                .GET());

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("cs", body.getString("workspaceId"));
            JSONObject policy = body.getJSONObject("policy");
            assertEquals("cs", policy.getString("workspaceId"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("PUT policy updates allowed/denied lists")
    void updatePolicyModifiesLists() throws Exception {
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

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy"))
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"allowedSkills\":[\"weather\",\"search\"],\"deniedSkills\":[\"exec\"],\"allowedTools\":[\"file\",\"browser\"],\"deniedTools\":[\"terminal\"]}"))
                .header("Content-Type", "application/json"));

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject policy = body.getJSONObject("policy");
            assertTrue(policy.getJSONArray("allowedSkills").contains("weather"));
            assertTrue(policy.getJSONArray("deniedSkills").contains("exec"));
            assertTrue(policy.getJSONArray("allowedTools").contains("browser"));
            assertTrue(policy.getJSONArray("deniedTools").contains("terminal"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("GET allowed-skills resolves workspace + agent policy")
    void allowedSkillsResolvesPolicy() throws Exception {
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
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"allowedSkills\":[\"search\",\"classify\"],\"allowedTools\":[\"file\"]}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy"))
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"allowedSkills\":[\"weather\",\"search\",\"classify\"],\"deniedSkills\":[\"exec\"],\"allowedTools\":[\"file\",\"browser\"],\"deniedTools\":[\"terminal\"]}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/teams/after-sales/agents/classifier/allowed-skills"))
                .GET());

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            // Workspace allowed: weather, search, classify; Agent allowed: search, classify
            // Intersection = search, classify
            var skills = body.getJSONArray("allowedSkills");
            assertTrue(skills.contains("search"));
            assertTrue(skills.contains("classify"));
            assertFalse(skills.contains("weather"));
            assertFalse(skills.contains("exec"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("GET allowed-tools resolves workspace + agent policy")
    void allowedToolsResolvesPolicy() throws Exception {
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
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"allowedSkills\":[],\"allowedTools\":[\"file\"]}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy"))
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"allowedTools\":[\"file\",\"browser\"],\"deniedTools\":[\"terminal\"]}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/teams/after-sales/agents/classifier/allowed-tools"))
                .GET());

            assertEquals(200, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            // Workspace allowed: file, browser; Agent allowed: file
            // Intersection = file
            var tools = body.getJSONArray("allowedTools");
            assertTrue(tools.contains("file"));
            assertFalse(tools.contains("browser"));
            assertFalse(tools.contains("terminal"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("POST check-skill returns permitted flag")
    void checkSkillReturnsPermitted() throws Exception {
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
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"allowedSkills\":[\"search\"],\"allowedTools\":[\"file\"]}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy"))
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"allowedSkills\":[\"search\",\"weather\"],\"deniedSkills\":[\"exec\"]}"))
                .header("Content-Type", "application/json"));

            // Permitted skill
            HttpResponse<String> permitted = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy/check-skill"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"agentId\":\"classifier\",\"skillName\":\"search\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, permitted.statusCode());
            assertTrue(JSON.parseObject(permitted.body()).getBooleanValue("permitted"));

            // Denied skill (not in agent allowed list)
            HttpResponse<String> denied = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy/check-skill"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"agentId\":\"classifier\",\"skillName\":\"weather\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, denied.statusCode());
            assertFalse(JSON.parseObject(denied.body()).getBooleanValue("permitted"));

            // Denied skill (in workspace denied list)
            HttpResponse<String> denied2 = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy/check-skill"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"agentId\":\"classifier\",\"skillName\":\"exec\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, denied2.statusCode());
            assertFalse(JSON.parseObject(denied2.body()).getBooleanValue("permitted"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("POST check-tool returns permitted flag")
    void checkToolReturnsPermitted() throws Exception {
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
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\",\"allowedTools\":[\"file\"]}]}"))
                .header("Content-Type", "application/json"));
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy"))
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"allowedTools\":[\"file\",\"browser\"],\"deniedTools\":[\"terminal\"]}"))
                .header("Content-Type", "application/json"));

            // Permitted tool
            HttpResponse<String> permitted = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy/check-tool"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"agentId\":\"classifier\",\"toolName\":\"file\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, permitted.statusCode());
            assertTrue(JSON.parseObject(permitted.body()).getBooleanValue("permitted"));

            // Denied tool (not in agent allowed list)
            HttpResponse<String> denied = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy/check-tool"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"agentId\":\"classifier\",\"toolName\":\"browser\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, denied.statusCode());
            assertFalse(JSON.parseObject(denied.body()).getBooleanValue("permitted"));

            // Denied tool (in workspace denied list)
            HttpResponse<String> denied2 = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/policy/check-tool"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"agentId\":\"classifier\",\"toolName\":\"terminal\"}"))
                .header("Content-Type", "application/json"));
            assertEquals(200, denied2.statusCode());
            assertFalse(JSON.parseObject(denied2.body()).getBooleanValue("permitted"));
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
