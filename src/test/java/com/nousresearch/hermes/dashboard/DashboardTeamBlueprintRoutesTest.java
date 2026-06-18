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

class DashboardTeamBlueprintRoutesTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    @DisplayName("POST team-blueprints creates v1 ACTIVE automatically")
    void createTeamBlueprintCreatesV1Active() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            // Create workspace first
            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"workspaceId\":\"cs\",\"name\":\"客服\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            // Create team blueprint
            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"description\":\"处理售后工单\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"}]}"))
                .header("Content-Type", "application/json"));

            assertEquals(201, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals("after-sales", body.getString("teamId"));
            JSONObject team = body.getJSONObject("team");
            assertEquals(1, team.getIntValue("activeVersion"));
            JSONArray versions = team.getJSONArray("versions");
            assertEquals(1, versions.size());
            assertEquals("ACTIVE", versions.getJSONObject(0).getString("status"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("POST versions creates v2 DRAFT without affecting activeVersion")
    void createDraftVersionCreatesV2Draft() throws Exception {
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
                    "{\"workspaceId\":\"cs2\",\"name\":\"客服2\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs2/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"}]}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> draft = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs2/team-blueprints/after-sales/versions"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"changeSummary\":\"新增专家\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"},{\"agentId\":\"expert\",\"displayName\":\"专家\"}]}"))
                .header("Content-Type", "application/json"));

            assertEquals(201, draft.statusCode());
            JSONObject body = JSON.parseObject(draft.body());
            assertTrue(body.getBooleanValue("ok"));
            JSONObject version = body.getJSONObject("version");
            assertEquals(2, version.getIntValue("version"));
            assertEquals("DRAFT", version.getString("status"));

            // activeVersion should still be 1
            HttpResponse<String> get = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs2/team-blueprints/after-sales"))
                .GET());
            JSONObject team = JSON.parseObject(get.body()).getJSONObject("team");
            assertEquals(1, team.getIntValue("activeVersion"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("POST activate switches active version from v1 to v2")
    void activateVersionSwitchesActive() throws Exception {
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
                    "{\"workspaceId\":\"cs3\",\"name\":\"客服3\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs3/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"after-sales\",\"name\":\"售后团队\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"}]}"))
                .header("Content-Type", "application/json"));

            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs3/team-blueprints/after-sales/versions"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"changeSummary\":\"新增专家\",\"agents\":[{\"agentId\":\"classifier\",\"displayName\":\"分类员\"},{\"agentId\":\"expert\",\"displayName\":\"专家\"}]}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> activate = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs3/team-blueprints/after-sales/versions/2/activate"))
                .POST(HttpRequest.BodyPublishers.noBody()));

            assertEquals(200, activate.statusCode());
            JSONObject body = JSON.parseObject(activate.body());
            assertTrue(body.getBooleanValue("ok"));
            assertEquals(2, body.getIntValue("activeVersion"));

            JSONObject team = body.getJSONObject("team");
            JSONArray versions = team.getJSONArray("versions");
            assertEquals("INACTIVE", versions.getJSONObject(0).getString("status"));
            assertEquals("ACTIVE", versions.getJSONObject(1).getString("status"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("GET list and GET single team-blueprint work")
    void listAndGetTeamBlueprints() throws Exception {
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
                    "{\"workspaceId\":\"cs4\",\"name\":\"客服4\",\"owner\":\"ops\"}"))
                .header("Content-Type", "application/json"));

            send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs4/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"sales\",\"name\":\"销售团队\",\"agents\":[{\"agentId\":\"lead\",\"displayName\":\"线索员\"}]}"))
                .header("Content-Type", "application/json"));

            HttpResponse<String> list = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs4/team-blueprints"))
                .GET());

            assertEquals(200, list.statusCode());
            JSONObject listBody = JSON.parseObject(list.body());
            assertTrue(listBody.getBooleanValue("ok"));
            assertEquals(1, listBody.getIntValue("total"));

            HttpResponse<String> get = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/cs4/team-blueprints/sales"))
                .GET());

            assertEquals(200, get.statusCode());
            JSONObject getBody = JSON.parseObject(get.body());
            assertTrue(getBody.getBooleanValue("ok"));
            assertEquals("sales", getBody.getString("teamId"));
        } finally {
            server.stop();
            tenantManager.shutdown();
        }
    }

    @Test
    @DisplayName("Creating blueprint for missing workspace returns 404")
    void missingWorkspaceReturns404() throws Exception {
        int port = freePort();
        TenantManager tenantManager = new TenantManager();
        DashboardServer server = new DashboardServer(port, "127.0.0.1", new HermesConfig(), tenantManager);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;
            String token = server.getSessionToken();

            HttpResponse<String> response = send(client, token, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/workspaces/missing/team-blueprints"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"teamId\":\"t\",\"name\":\"T\",\"agents\":[]}"))
                .header("Content-Type", "application/json"));

            assertEquals(404, response.statusCode());
            JSONObject body = JSON.parseObject(response.body());
            assertFalse(body.getBooleanValue("ok"));
            assertEquals("missing", body.getString("workspaceId"));
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
