package com.nousresearch.hermes.collaboration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable safety contract attached to a future delegated executor run.
 *
 * <p>The contract combines policy, requested capabilities, and the sandbox plan
 * the parent expects. It is a validation primitive only: producing a contract
 * never performs execution or applies patches.</p>
 */
public record DelegatedExecutorSafetyContract(
    String executorName,
    DelegatedExecutorSafetyPolicy policy,
    PatchSandboxPlan sandboxPlan,
    Set<DelegatedExecutorCapability> requestedCapabilities
) {
    public DelegatedExecutorSafetyContract {
        executorName = executorName == null || executorName.isBlank() ? "delegated-executor" : executorName;
        policy = policy == null ? DelegatedExecutorSafetyPolicy.restrictiveDefault() : policy;
        requestedCapabilities = requestedCapabilities == null ? policy.defaultCapabilities() : Set.copyOf(requestedCapabilities);
        sandboxPlan = sandboxPlan == null
            ? policy.createPatchSandboxPlan(executorName + "-sandbox", ".", requestedCapabilities)
            : sandboxPlan;
    }

    public static DelegatedExecutorSafetyContract restrictive(String executorName, String repositoryRoot) {
        DelegatedExecutorSafetyPolicy policy = DelegatedExecutorSafetyPolicy.restrictiveDefault();
        return new DelegatedExecutorSafetyContract(
            executorName,
            policy,
            policy.createPatchSandboxPlan(executorName + "-sandbox", repositoryRoot, policy.defaultCapabilities()),
            policy.defaultCapabilities()
        );
    }

    public List<ExecutorSafetyViolation> validateChangedFiles(List<String> changedFiles) {
        return policy.validateChangedFiles(changedFiles);
    }

    public List<ExecutorSafetyViolation> validateRequestedCapabilities() {
        return policy.validateRequestedCapabilities(requestedCapabilities);
    }

    public List<ExecutorSafetyViolation> validate(List<String> changedFiles) {
        List<ExecutorSafetyViolation> violations = new ArrayList<>();
        violations.addAll(validateRequestedCapabilities());
        violations.addAll(validateChangedFiles(changedFiles));
        if (policy.requirePatchSandbox() && sandboxPlan == null) {
            violations.add(ExecutorSafetyViolation.of("MISSING_PATCH_SANDBOX", "patch sandbox is required", executorName));
        }
        if (policy.requireParentVerification() && policy.allowAutoMerge()) {
            violations.add(ExecutorSafetyViolation.of("AUTO_MERGE_CONFLICT", "auto merge cannot be enabled when parent verification is required", executorName));
        }
        return violations;
    }

    public boolean isSatisfiedBy(List<String> changedFiles) {
        return validate(changedFiles).isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executor_name", executorName);
        m.put("policy", policy.toMap());
        m.put("sandbox_plan", sandboxPlan != null ? sandboxPlan.toMap() : null);
        m.put("requested_capabilities", requestedCapabilities.stream().map(Enum::name).toList());
        return m;
    }
}
