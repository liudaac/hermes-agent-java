package com.nousresearch.hermes.collaboration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative plan for a patch-only delegated execution sandbox.
 *
 * <p>This is intentionally not an executor. It describes where a future
 * executor should isolate work, which paths may be changed there, and which
 * artifacts the parent should inspect before accepting or rejecting a patch.</p>
 */
public record PatchSandboxPlan(
    String sandboxId,
    String repositoryRoot,
    String sandboxRoot,
    List<String> allowedChangedPathPrefixes,
    List<String> deniedChangedPathPrefixes,
    List<DelegatedExecutorCapability> requestedCapabilities,
    boolean collectDiff,
    boolean collectTestResults,
    boolean parentVerificationRequired,
    boolean autoMergeAllowed,
    Instant createdAt
) {
    public PatchSandboxPlan {
        sandboxId = sandboxId == null || sandboxId.isBlank() ? "patch-sandbox" : sandboxId;
        repositoryRoot = normalize(repositoryRoot);
        sandboxRoot = sandboxRoot == null || sandboxRoot.isBlank()
            ? defaultSandboxRoot(repositoryRoot, sandboxId)
            : normalize(sandboxRoot);
        allowedChangedPathPrefixes = DelegatedExecutorSafetyPolicy.normalizePathPrefixes(allowedChangedPathPrefixes);
        deniedChangedPathPrefixes = DelegatedExecutorSafetyPolicy.normalizePathPrefixes(deniedChangedPathPrefixes);
        requestedCapabilities = requestedCapabilities == null ? List.of() : requestedCapabilities.stream().distinct().toList();
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static PatchSandboxPlan forPolicy(String sandboxId, String repositoryRoot, DelegatedExecutorSafetyPolicy policy) {
        DelegatedExecutorSafetyPolicy p = policy == null ? DelegatedExecutorSafetyPolicy.restrictiveDefault() : policy;
        return new PatchSandboxPlan(
            sandboxId,
            repositoryRoot,
            null,
            p.allowedChangedPathPrefixes(),
            p.deniedChangedPathPrefixes(),
            p.defaultCapabilities().stream().toList(),
            true,
            true,
            p.requireParentVerification(),
            p.allowAutoMerge(),
            Instant.now()
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sandbox_id", sandboxId);
        m.put("repository_root", repositoryRoot);
        m.put("sandbox_root", sandboxRoot);
        m.put("allowed_changed_path_prefixes", allowedChangedPathPrefixes);
        m.put("denied_changed_path_prefixes", deniedChangedPathPrefixes);
        m.put("requested_capabilities", requestedCapabilities.stream().map(Enum::name).toList());
        m.put("collect_diff", collectDiff);
        m.put("collect_test_results", collectTestResults);
        m.put("parent_verification_required", parentVerificationRequired);
        m.put("auto_merge_allowed", autoMergeAllowed);
        m.put("created_at", createdAt.toString());
        return m;
    }

    private static String defaultSandboxRoot(String repositoryRoot, String sandboxId) {
        String root = repositoryRoot == null || repositoryRoot.isBlank() ? "." : repositoryRoot;
        return normalize(root + "/.hermes/patch-sandboxes/" + (sandboxId == null || sandboxId.isBlank() ? "patch-sandbox" : sandboxId));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String s = value.trim().replace('\\', '/');
        if (s.isBlank()) return s;
        try { s = Path.of(s).normalize().toString().replace('\\', '/'); }
        catch (Exception ignored) {}
        return s;
    }
}
