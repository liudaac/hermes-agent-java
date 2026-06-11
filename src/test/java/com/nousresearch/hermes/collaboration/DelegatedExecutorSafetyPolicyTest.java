package com.nousresearch.hermes.collaboration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DelegatedExecutorSafetyPolicyTest {

    @Test
    void defaultPolicyIsRestrictive() {
        DelegatedExecutorSafetyPolicy policy = DelegatedExecutorSafetyPolicy.restrictiveDefault();

        assertFalse(policy.allowCommands());
        assertFalse(policy.allowNetwork());
        assertFalse(policy.allowBrowser());
        assertTrue(policy.requirePatchSandbox());
        assertTrue(policy.requireParentVerification());
        assertFalse(policy.allowAutoMerge());
        assertTrue(policy.defaultCapabilities().contains(DelegatedExecutorCapability.FILE_READ));
        assertTrue(policy.defaultCapabilities().contains(DelegatedExecutorCapability.PATCH_WRITE));
        assertFalse(policy.defaultCapabilities().contains(DelegatedExecutorCapability.COMMAND_EXECUTION));
        assertFalse(policy.defaultCapabilities().contains(DelegatedExecutorCapability.NETWORK_ACCESS));
        assertFalse(policy.defaultCapabilities().contains(DelegatedExecutorCapability.BROWSER_ACCESS));
        assertFalse(policy.defaultCapabilities().contains(DelegatedExecutorCapability.AUTO_MERGE));
    }

    @Test
    void validatesAllowedAndDeniedChangedPaths() {
        DelegatedExecutorSafetyPolicy policy = DelegatedExecutorSafetyPolicy.restrictiveDefault();

        assertTrue(policy.validateChangedFiles(List.of(
            "src/main/java/com/nousresearch/hermes/collaboration/NewContract.java",
            "src/test/java/com/nousresearch/hermes/collaboration/NewContractTest.java",
            "docs/delegated-executor-safety-contract.md"
        )).isEmpty());

        List<ExecutorSafetyViolation> violations = policy.validateChangedFiles(List.of(
            "README.md",
            ".git/config",
            "../outside.txt",
            "/tmp/escape.txt"
        ));

        assertTrue(violations.stream().anyMatch(v -> v.code().equals("PATH_NOT_ALLOWED") && v.subject().equals("README.md")));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("DENIED_PATH") && v.subject().equals(".git/config")));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("PATH_ESCAPE") && v.subject().equals("../outside.txt")));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("PATH_ESCAPE") && v.subject().equals("/tmp/escape.txt")));
    }

    @Test
    void deniesCommandNetworkBrowserAndAutoMergeCapabilitiesByDefault() {
        DelegatedExecutorSafetyPolicy policy = DelegatedExecutorSafetyPolicy.restrictiveDefault();

        List<ExecutorSafetyViolation> violations = policy.validateRequestedCapabilities(Set.of(
            DelegatedExecutorCapability.COMMAND_EXECUTION,
            DelegatedExecutorCapability.NETWORK_ACCESS,
            DelegatedExecutorCapability.BROWSER_ACCESS,
            DelegatedExecutorCapability.AUTO_MERGE
        ));

        assertEquals(4, violations.size());
        assertTrue(violations.stream().allMatch(v -> v.code().equals("CAPABILITY_DENIED")));
        assertTrue(violations.stream().anyMatch(v -> v.subject().equals("COMMAND_EXECUTION")));
        assertTrue(violations.stream().anyMatch(v -> v.subject().equals("NETWORK_ACCESS")));
        assertTrue(violations.stream().anyMatch(v -> v.subject().equals("BROWSER_ACCESS")));
        assertTrue(violations.stream().anyMatch(v -> v.subject().equals("AUTO_MERGE")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchSandboxPlanProducesStableMapOutput() {
        DelegatedExecutorSafetyPolicy policy = DelegatedExecutorSafetyPolicy.restrictiveDefault();
        PatchSandboxPlan plan = policy.createPatchSandboxPlan(
            "task-123",
            "/repo/hermes-agent-java",
            Set.of(DelegatedExecutorCapability.FILE_READ, DelegatedExecutorCapability.PATCH_WRITE)
        );

        Map<String, Object> map = plan.toMap();

        assertEquals("task-123", map.get("sandbox_id"));
        assertEquals("/repo/hermes-agent-java", map.get("repository_root"));
        assertEquals("/repo/hermes-agent-java/.hermes/patch-sandboxes/task-123", map.get("sandbox_root"));
        assertEquals(List.of("src/main/java", "src/test/java", "docs"), map.get("allowed_changed_path_prefixes"));
        assertEquals(List.of(".git", ".github", "target", "build", "out", "pom.xml", "gradle.properties", "settings.gradle"), map.get("denied_changed_path_prefixes"));
        assertTrue((Boolean) map.get("collect_diff"));
        assertTrue((Boolean) map.get("collect_test_results"));
        assertTrue((Boolean) map.get("parent_verification_required"));
        assertFalse((Boolean) map.get("auto_merge_allowed"));
        assertTrue(((List<String>) map.get("requested_capabilities")).contains("FILE_READ"));
        assertTrue(((List<String>) map.get("requested_capabilities")).contains("PATCH_WRITE"));
    }

    @Test
    void executionPolicyConversionKeepsBackwardCompatibleAllowedPrefixes() {
        DelegatedTaskExecutionPolicy executionPolicy = new DelegatedTaskExecutionPolicy(
            false,
            false,
            true,
            null,
            List.of("src/main/java"),
            ParentVerificationPolicy.allowChangedFilesUnder(List.of("src/main/java")),
            Map.of()
        );

        DelegatedExecutorSafetyPolicy safetyPolicy = executionPolicy.toExecutorSafetyPolicy();

        assertEquals(List.of("src/main/java"), safetyPolicy.allowedChangedPathPrefixes());
        assertTrue(safetyPolicy.allowCommands());
        assertFalse(safetyPolicy.allowNetwork());
        assertFalse(safetyPolicy.allowBrowser());
        assertFalse(safetyPolicy.allowAutoMerge());
        assertTrue(safetyPolicy.requirePatchSandbox());
        assertTrue(safetyPolicy.requireParentVerification());
    }
}
