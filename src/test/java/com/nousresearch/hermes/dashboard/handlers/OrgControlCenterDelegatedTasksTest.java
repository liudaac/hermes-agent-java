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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            assertTrue(noopBody.getJSONObject("safety").getBooleanValue("accepted"));
            assertTrue(noopBody.getJSONObject("safety").getJSONObject("summary").getBooleanValue("require_patch_sandbox"));
            assertFalse(noopBody.getJSONObject("safety").getJSONObject("summary").getBooleanValue("allow_auto_merge"));

            var deniedCapabilityTask = tenant.getDelegatedTaskStore().createPending(envelope());
            String deniedCapabilityJson = """
                {"actor":"dashboard","executor":"noop","requested_capabilities":["NETWORK_ACCESS"],"allow_network":false}
                """;
            HttpResponse<String> deniedCapability = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + deniedCapabilityTask.taskId() + "/execute"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(deniedCapabilityJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(deniedCapability.statusCode() >= 400, deniedCapability.body());
            assertEquals("PENDING", tenant.getDelegatedTaskStore().get(deniedCapabilityTask.taskId()).status().name());

            var deniedPathTask = tenant.getDelegatedTaskStore().createPending(envelope());
            String deniedPathJson = """
                {"actor":"dashboard","executor":"noop","allowed_changed_paths":["src"],"denied_changed_paths":["src/main/resources/secrets"],"changed_files":["src/main/resources/secrets/key.txt"]}
                """;
            HttpResponse<String> deniedPath = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + deniedPathTask.taskId() + "/execute"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(deniedPathJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(deniedPath.statusCode() >= 400, deniedPath.body());
            assertEquals("PENDING", tenant.getDelegatedTaskStore().get(deniedPathTask.taskId()).status().name());

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
            assertTrue(mockBody.getJSONObject("safety").getBooleanValue("accepted"));
            assertEquals(0, mockBody.getJSONObject("safety").getIntValue("violation_count"));
            assertTrue(mockBody.getJSONObject("execution").getBooleanValue("executed"));
            assertTrue(mockBody.getJSONObject("execution").getBooleanValue("submitted"));
            assertEquals("ACCEPTED", mockBody.getJSONObject("task").getString("status"));
            assertEquals("ACCEPTED", mockBody.getJSONObject("execution").getJSONObject("verification_result").getString("status"));


            var localPatchTask = tenant.getDelegatedTaskStore().createPending(envelope());
            Path repo = dir.resolve("local-patch-repo");
            Files.createDirectories(repo.resolve("src/main/java"));
            Files.writeString(repo.resolve("src/main/java/App.java"), "hello\n", StandardCharsets.UTF_8);
            String patch = "--- a/src/main/java/App.java\n" +
                "+++ b/src/main/java/App.java\n" +
                "@@ -1,1 +1,1 @@\n" +
                "-hello\n" +
                "+hello from handler sandbox\n";
            JSONObject localPatchRequest = new JSONObject();
            localPatchRequest.put("actor", "dashboard");
            localPatchRequest.put("executor", "local_patch");
            localPatchRequest.put("allow_file_changes", true);
            localPatchRequest.put("repository_root", repo.toString());
            localPatchRequest.put("patch", patch);
            localPatchRequest.put("require_tests", true);
            localPatchRequest.put("require_all_tests_passed", true);
            localPatchRequest.put("allowed_changed_file_prefixes", List.of("src/main/java"));
            localPatchRequest.put("requested_capabilities", List.of("FILE_READ", "PATCH_WRITE"));
            localPatchRequest.put("tests_run", List.of(JSONObject.of("name", "reported", "passed", true)));
            String localPatchJson = localPatchRequest.toJSONString();
            HttpResponse<String> localPatch = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/org/control/delegated-tasks/" + tenantId + "/" + localPatchTask.taskId() + "/execute"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(localPatchJson))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, localPatch.statusCode(), localPatch.body());
            JSONObject localPatchBody = JSON.parseObject(localPatch.body());
            assertEquals("ACCEPTED", localPatchBody.getJSONObject("execution").getString("status"));
            assertTrue(localPatchBody.getJSONObject("execution").getBooleanValue("executed"));
            assertTrue(localPatchBody.getJSONObject("execution").getBooleanValue("submitted"));
            assertEquals("ACCEPTED", localPatchBody.getJSONObject("task").getString("status"));
            assertEquals("hello\n", Files.readString(repo.resolve("src/main/java/App.java")), "local_patch must not mutate parent checkout");

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
