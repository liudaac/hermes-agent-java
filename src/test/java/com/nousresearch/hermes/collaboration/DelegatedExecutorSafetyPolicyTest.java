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

}
