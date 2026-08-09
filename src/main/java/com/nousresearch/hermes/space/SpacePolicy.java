package com.nousresearch.hermes.space;

import java.util.*;

/**
 * Space-level policies - approval rules, security boundaries,
 * sandbox configuration. These operate within org-level compliance
 * constraints (never looser).
 */
public class SpacePolicy {

    // Approval mode per operation type: "auto" | "prompt" | "require" | "deny"
    private final Map<String, String> approvalModes;
    // Paths that are write-restricted
    private final Set<String> protectedPaths;
    // Whether sandbox (cgroup/network) is enforced
    private boolean sandboxEnforced;
    // Max concurrent runs
    private int maxConcurrentRuns;
    // Memory decay policy: "aggressive" | "standard" | "longRunning" | "archival"
    private String decayPolicy;
    // Whether to allow users to override approval modes for themselves
    private boolean allowUserOverride;

    public SpacePolicy() {
        this.approvalModes = new LinkedHashMap<>();
        this.protectedPaths = new LinkedHashSet<>();
        this.sandboxEnforced = true;
        this.maxConcurrentRuns = 5;
        this.decayPolicy = "standard";
        this.allowUserOverride = false;

        // Sensible defaults
        approvalModes.put("terminal_command", "prompt");
        approvalModes.put("file_write", "prompt");
        approvalModes.put("file_delete", "require");
        approvalModes.put("code_execution", "prompt");
        approvalModes.put("browser_action", "auto");
        approvalModes.put("subagent_spawn", "auto");
    }

    public Map<String, String> approvalModes() { return approvalModes; }
    public Set<String> protectedPaths() { return protectedPaths; }
    public boolean sandboxEnforced() { return sandboxEnforced; }
    public int maxConcurrentRuns() { return maxConcurrentRuns; }
    public String decayPolicy() { return decayPolicy; }
    public boolean allowUserOverride() { return allowUserOverride; }

    public void setApprovalMode(String type, String mode) { approvalModes.put(type, mode); }
    public void addProtectedPath(String path) { protectedPaths.add(path); }
    public void setSandboxEnforced(boolean v) { this.sandboxEnforced = v; }
    public void setMaxConcurrentRuns(int n) { this.maxConcurrentRuns = n; }
    public void setDecayPolicy(String policy) { this.decayPolicy = policy; }
    public void setAllowUserOverride(boolean v) { this.allowUserOverride = v; }

    public String getApprovalMode(String type) {
        return approvalModes.getOrDefault(type, "prompt");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approvalModes", new LinkedHashMap<>(approvalModes));
        m.put("protectedPaths", new ArrayList<>(protectedPaths));
        m.put("sandboxEnforced", sandboxEnforced);
        m.put("maxConcurrentRuns", maxConcurrentRuns);
        m.put("decayPolicy", decayPolicy);
        m.put("allowUserOverride", allowUserOverride);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SpacePolicy fromMap(Map<String, Object> m) {
        if (m == null) return new SpacePolicy();
        SpacePolicy p = new SpacePolicy();
        p.approvalModes.clear();
        p.approvalModes.putAll((Map<String, String>) m.getOrDefault("approvalModes", Map.of()));
        p.protectedPaths.addAll((List<String>) m.getOrDefault("protectedPaths", List.of()));
        p.sandboxEnforced = (Boolean) m.getOrDefault("sandboxEnforced", true);
        p.maxConcurrentRuns = ((Number) m.getOrDefault("maxConcurrentRuns", 5)).intValue();
        p.decayPolicy = (String) m.getOrDefault("decayPolicy", "standard");
        p.allowUserOverride = (Boolean) m.getOrDefault("allowUserOverride", false);
        return p;
    }
}
