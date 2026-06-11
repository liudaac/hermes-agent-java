package com.nousresearch.hermes.collaboration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalPatchExecutorTest {
    @TempDir Path tempDir;

    @Test
    void refusesWithoutPatch() throws Exception {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(envelope(), parentPolicy());
        Path repo = createRepo();

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "local_patch", policy(repo, null, parentPolicy()));

        assertFalse(result.executed());
        assertFalse(result.submitted());
        assertEquals("NO_PATCH_PROVIDED", result.status());
        assertEquals(DelegatedTask.Status.PENDING, task.status());
        assertEquals("hello\n", Files.readString(repo.resolve("src/main/java/App.java")));
    }

    @Test
    void refusesDisallowedPath() throws Exception {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(envelope(), parentPolicy());
        Path repo = createRepo();
        Files.writeString(repo.resolve("README.md"), "old\n", StandardCharsets.UTF_8);

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "local_patch", policy(repo, patch("README.md", "old", "new"), parentPolicy()));

        assertFalse(result.executed());
        assertFalse(result.submitted());
        assertEquals("SAFETY_DENIED", result.status());
        assertTrue(result.message().contains("PATH_NOT_ALLOWED"));
        assertEquals("old\n", Files.readString(repo.resolve("README.md")));
        assertEquals(DelegatedTask.Status.PENDING, task.status());
    }

    @Test
    void appliesSafePatchInSandboxWithoutTouchingMainWorkspaceAndReturnsChangedFiles() throws Exception {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(envelope(), parentPolicy());
        Path repo = createRepo();

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "local_patch", policy(repo, patch("src/main/java/App.java", "hello", "hello from sandbox"), parentPolicy()));

        assertTrue(result.executed());
        assertTrue(result.submitted());
        assertEquals("ACCEPTED", result.status());
        assertEquals(List.of("src/main/java/App.java"), result.delegatedTaskResult().changedFiles());
        assertEquals("hello\n", Files.readString(repo.resolve("src/main/java/App.java")), "parent checkout must not be mutated");
        Path sandboxRoot = sandboxRoot(result);
        assertNotNull(sandboxRoot);
        assertTrue(Files.exists(sandboxRoot.resolve("src/main/java/App.java")));
        assertEquals("hello from sandbox\n", Files.readString(sandboxRoot.resolve("src/main/java/App.java")));
        assertEquals(DelegatedTask.Status.ACCEPTED, task.status());
    }

    @Test
    void parentVerificationRejectsWhenRequiredTestsAreMissing() throws Exception {
        DelegatedTaskStore store = new DelegatedTaskStore();
        ParentVerificationPolicy strict = new ParentVerificationPolicy(true, true, List.of("src/main/java"));
        DelegatedTask task = store.createPending(envelope(), strict);
        Path repo = createRepo();

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "local_patch", policy(repo, patch("src/main/java/App.java", "hello", "verified"), strict, List.of()));

        assertTrue(result.executed());
        assertTrue(result.submitted());
        assertEquals("REJECTED", result.status());
        assertFalse(result.verificationResult().accepted());
        assertTrue(result.verificationResult().reasons().contains("no tests reported"));
        assertEquals(DelegatedTask.Status.REJECTED, task.status());
    }

    @Test
    void parentVerificationAcceptsWhenPolicySatisfied() throws Exception {
        DelegatedTaskStore store = new DelegatedTaskStore();
        ParentVerificationPolicy strict = new ParentVerificationPolicy(true, true, List.of("src/main/java"));
        DelegatedTask task = store.createPending(envelope(), strict);
        Path repo = createRepo();

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "local_patch", policy(repo, patch("src/main/java/App.java", "hello", "verified"), strict));

        assertEquals("ACCEPTED", result.status());
        assertTrue(result.verificationResult().accepted());
        assertEquals(DelegatedTask.Status.ACCEPTED, task.status());
    }

    @Test
    void registryIncludesLocalPatchExecutor() {
        DelegatedTaskExecutorRegistry registry = new DelegatedTaskExecutorRegistry();

        assertTrue(registry.find("local_patch").orElseThrow() instanceof LocalPatchExecutor);
    }

    private Path createRepo() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/App.java"), "hello\n", StandardCharsets.UTF_8);
        return repo;
    }

    private DelegatedTaskExecutionPolicy policy(Path repo, String patch, ParentVerificationPolicy parentPolicy) {
        return policy(repo, patch, parentPolicy, List.of(Map.of("name", "local-patch-test", "passed", true, "details", "reported")));
    }

    private DelegatedTaskExecutionPolicy policy(Path repo, String patch, ParentVerificationPolicy parentPolicy, List<Object> tests) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("repository_root", repo.toString());
        metadata.put("safety_policy", DelegatedExecutorSafetyPolicy.restrictiveDefault().toMap());
        metadata.put("tests_run", tests);
        if (patch != null) metadata.put("patch", patch);
        return new DelegatedTaskExecutionPolicy(
            false,
            true,
            false,
            Duration.ofSeconds(10),
            List.of("src/main/java"),
            parentPolicy,
            metadata
        );
    }

    private static ParentVerificationPolicy parentPolicy() {
        return new ParentVerificationPolicy(true, true, List.of("src/main/java"));
    }

    private static String patch(String file, String oldLine, String newLine) {
        return "--- a/" + file + "\n" +
            "+++ b/" + file + "\n" +
            "@@ -1,1 +1,1 @@\n" +
            "-" + oldLine + "\n" +
            "+" + newLine + "\n";
    }

    private static Path sandboxRoot(DelegatedTaskExecutionResult result) {
        return result.delegatedTaskResult().risks().stream()
            .filter(s -> s.startsWith("sandbox_root="))
            .map(s -> Path.of(s.substring("sandbox_root=".length())))
            .findFirst()
            .orElse(null);
    }

    private static DelegatedTaskEnvelope envelope() {
        ContextPressureReport report = new ContextPressureReport(
            List.of("compacted"),
            0.91,
            "HIGH",
            true,
            false,
            false,
            false,
            false,
            List.of("tool output compacted")
        );
        DelegationDecision decision = new DelegationDecision(
            true,
            "context pressure",
            report,
            "release",
            "specialist"
        );
        return DelegatedTaskEnvelope.of("apply safe patch", "run_local_patch", decision);
    }
}
