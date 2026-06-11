package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.collaboration.ContextPressureReport;
import com.nousresearch.hermes.collaboration.DelegationDecision;
import com.nousresearch.hermes.collaboration.DelegatedTaskEnvelope;
import com.nousresearch.hermes.tenant.core.TenantContext;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrgControlCenterDelegatedTasksTest {

    @Test
    void listsSubmitsAndVerifiesDelegatedTasksAcrossTenants() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "delegated-task-api-" + System.nanoTime());
        TenantManagerConfig cfg = TenantManagerConfig.builder().enableIdleCleanup(false).autoLoadExisting(false).build();
        TenantManager manager = new TenantManager(dir, cfg);
        String tenantId = "delegated-api-" + System.nanoTime();
        TenantContext tenant = manager.createTenant(new TenantProvisioningRequest(tenantId, "test"));
        var task = tenant.getDelegatedTaskStore().createPending(envelope());

        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        OrgControlCenterHandler handler = new OrgControlCenterHandler(manager);
        app.get("/api/org/control/delegated-tasks", handler::delegatedTasks);
        app.post("/api/org/control/delegated-tasks/{tenantId}/{taskId}/submit", handler::submitDelegatedTask);
        app.post("/api/org/control/delegated-tasks/{tenantId}/{taskId}/verify", handler::verifyDelegatedTask);
        app.post("/api/org/control/delegated-tasks/{tenantId}/{taskId}/execute", handler::executeDelegatedTask);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks?n=10")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, list.statusCode());
            JSONArray rows = JSON.parseObject(list.body()).getJSONArray("delegated_tasks");
            assertTrue(rows.stream().map(o -> (JSONObject) o).anyMatch(row -> tenantId.equals(row.getString("tenant_id")) && task.taskId().equals(row.getString("task_id"))));


            var noopTask = tenant.getDelegatedTaskStore().createPending(envelope());
            String noopJson = """
                {"actor":"dashboard","executor":"noop","require_tests":true,"require_all_tests_passed":true}
                """;
            HttpResponse<String> noop = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + noopTask.taskId() + "/execute"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(noopJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, noop.statusCode());
            JSONObject noopBody = JSON.parseObject(noop.body());
            assertEquals("EXTERNAL_EXECUTOR_REQUIRED", noopBody.getJSONObject("execution").getString("status"));
            assertFalse(noopBody.getJSONObject("execution").getBooleanValue("executed"));
            assertFalse(noopBody.getJSONObject("execution").getBooleanValue("submitted"));
            assertEquals("PENDING", noopBody.getJSONObject("task").getString("status"));

            var mockTask = tenant.getDelegatedTaskStore().createPending(envelope());
            String mockJson = """
                {"actor":"dashboard","executor":"mock","require_tests":true,"require_all_tests_passed":true,"allowed_changed_file_prefixes":[]}
                """;
            HttpResponse<String> mock = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + mockTask.taskId() + "/execute"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mockJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, mock.statusCode());
            JSONObject mockBody = JSON.parseObject(mock.body());
            assertEquals("ACCEPTED", mockBody.getJSONObject("execution").getString("status"));
            assertTrue(mockBody.getJSONObject("execution").getBooleanValue("executed"));
            assertTrue(mockBody.getJSONObject("execution").getBooleanValue("submitted"));
            assertEquals("ACCEPTED", mockBody.getJSONObject("task").getString("status"));
            assertEquals("ACCEPTED", mockBody.getJSONObject("execution").getJSONObject("verification_result").getString("status"));

            String submitJson = """
                {"actor":"dashboard","summary":"simulated result","changed_files":["src/main/java/App.java"],"tests_run":[{"name":"mvn test","passed":true}],"risks":["none"]}
                """;
            HttpResponse<String> submit = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + task.taskId() + "/submit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(submitJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, submit.statusCode());
            JSONObject submitted = JSON.parseObject(submit.body());
            assertTrue(submitted.getJSONObject("verification").getBooleanValue("accepted"));
            assertEquals("ACCEPTED", submitted.getJSONObject("task").getString("status"));

            String verifyJson = """
                {"actor":"dashboard","require_tests":true,"require_all_tests_passed":true,"allowed_changed_file_prefixes":["docs"]}
                """;
            HttpResponse<String> verify = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + task.taskId() + "/verify"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(verifyJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, verify.statusCode());
            JSONObject verified = JSON.parseObject(verify.body());
            assertFalse(verified.getJSONObject("verification").getBooleanValue("accepted"));
            assertEquals("REJECTED", verified.getJSONObject("task").getString("status"));
        } finally {
            app.stop();
            manager.shutdown();
        }
    }

    private static DelegatedTaskEnvelope envelope() {
        ContextPressureReport report = new ContextPressureReport(
            List.of("compacted"), 0.9, "HIGH", true, true, false, false, false, List.of("needs fresh context")
        );
        DelegationDecision decision = new DelegationDecision(true, "needs fresh context", report, "release", "reviewer");
        return DelegatedTaskEnvelope.of("ship release safely", "run_42", decision);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
