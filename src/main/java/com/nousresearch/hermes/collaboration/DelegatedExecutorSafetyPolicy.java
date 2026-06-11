package com.nousresearch.hermes.collaboration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Code-level safety boundary for future delegated executors.
 *
 * <p>The default is deliberately restrictive: patch sandboxing and parent
 * verification are required, commands/network/browser are denied, and auto
 * merge is disabled. This class performs policy checks only; it never executes
 * commands, opens sockets, drives browsers, or mutates files.</p>
 */
public record DelegatedExecutorSafetyPolicy(
    List<String> allowedChangedPathPrefixes,
    List<String> deniedChangedPathPrefixes,
    boolean allowCommands,
    boolean allowNetwork,
    boolean allowBrowser,
    boolean requirePatchSandbox,
    boolean requireParentVerification,
    boolean allowAutoMerge,
    Set<DelegatedExecutorCapability> defaultCapabilities,
    Map<String, Object> metadata
) {
    public DelegatedExecutorSafetyPolicy {
        allowedChangedPathPrefixes = normalizePathPrefixes(allowedChangedPathPrefixes);
        deniedChangedPathPrefixes = normalizePathPrefixes(deniedChangedPathPrefixes);
        defaultCapabilities = defaultCapabilities == null || defaultCapabilities.isEmpty()
            ? defaultCapabilitySet(allowCommands, allowNetwork, allowBrowser, allowAutoMerge)
            : Set.copyOf(defaultCapabilities);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static DelegatedExecutorSafetyPolicy restrictiveDefault() {
        return new DelegatedExecutorSafetyPolicy(
            List.of("src/main/java", "src/test/java", "docs"),
            List.of(".git", ".github", "target", "build", "out", "pom.xml", "gradle.properties", "settings.gradle"),
            false,
            false,
            false,
            true,
            true,
            false,
            EnumSet.of(DelegatedExecutorCapability.FILE_READ, DelegatedExecutorCapability.PATCH_WRITE),
            Map.of("mode", "restrictive_default")
        );
    }

    public static DelegatedExecutorSafetyPolicy fromExecutionPolicy(DelegatedTaskExecutionPolicy executionPolicy) {
        if (executionPolicy == null) return restrictiveDefault();
        return new DelegatedExecutorSafetyPolicy(
            executionPolicy.allowedChangedFilePrefixes(),
            restrictiveDefault().deniedChangedPathPrefixes(),
            executionPolicy.allowCommands(),
            false,
            false,
            true,
            true,
            false,
            defaultCapabilitySet(executionPolicy.allowCommands(), false, false, false),
            Map.of("source", "DelegatedTaskExecutionPolicy")
        );
    }

    @SuppressWarnings("unchecked")
    public static DelegatedExecutorSafetyPolicy fromMap(Map<String, Object> m) {
        if (m == null) return restrictiveDefault();
        DelegatedExecutorSafetyPolicy defaults = restrictiveDefault();
        return new DelegatedExecutorSafetyPolicy(
            listOfStrings(firstNonNull(firstNonNull(m.get("allowed_changed_path_prefixes"), m.get("allowed_changed_paths")), m.get("allowed_paths")), defaults.allowedChangedPathPrefixes()),
            listOfStrings(firstNonNull(firstNonNull(m.get("denied_changed_path_prefixes"), m.get("denied_changed_paths")), m.get("denied_paths")), defaults.deniedChangedPathPrefixes()),
            booleanOrDefault(firstNonNull(firstNonNull(m.get("allow_commands"), m.get("allow_command")), m.get("allowCommands")), defaults.allowCommands()),
            booleanOrDefault(firstNonNull(m.get("allow_network"), m.get("allowNetwork")), defaults.allowNetwork()),
            booleanOrDefault(firstNonNull(m.get("allow_browser"), m.get("allowBrowser")), defaults.allowBrowser()),
            booleanOrDefault(firstNonNull(m.get("require_patch_sandbox"), m.get("requirePatchSandbox")), defaults.requirePatchSandbox()),
            booleanOrDefault(firstNonNull(m.get("require_parent_verification"), m.get("requireParentVerification")), defaults.requireParentVerification()),
            booleanOrDefault(firstNonNull(m.get("allow_auto_merge"), m.get("allowAutoMerge")), defaults.allowAutoMerge()),
            capabilitiesFrom(m.get("default_capabilities")),
            m.get("metadata") instanceof Map<?, ?> meta ? new LinkedHashMap<>((Map<String, Object>) meta) : Map.of("source", "map")
        );
    }

    public List<ExecutorSafetyViolation> validateChangedFiles(List<String> changedFiles) {
        List<ExecutorSafetyViolation> violations = new ArrayList<>();
        if (changedFiles == null || changedFiles.isEmpty()) return violations;
        for (String file : changedFiles) {
            String normalized = normalizePath(file);
            if (normalized == null || normalized.isBlank()) {
                violations.add(ExecutorSafetyViolation.of("INVALID_PATH", "changed file path is blank or invalid", String.valueOf(file)));
                continue;
            }
            if (isAbsoluteOrEscaping(normalized)) {
                violations.add(ExecutorSafetyViolation.of("PATH_ESCAPE", "changed file must stay inside the repository", file));
                continue;
            }
            if (matchesAny(normalized, deniedChangedPathPrefixes)) {
                violations.add(ExecutorSafetyViolation.of("DENIED_PATH", "changed file is under a denied path", normalized));
            }
            if (!allowedChangedPathPrefixes.isEmpty() && !matchesAny(normalized, allowedChangedPathPrefixes)) {
                violations.add(ExecutorSafetyViolation.of("PATH_NOT_ALLOWED", "changed file is outside allowed paths", normalized));
            }
        }
        return violations;
    }

    public boolean areChangedFilesAllowed(List<String> changedFiles) {
        return validateChangedFiles(changedFiles).isEmpty();
    }

    public List<ExecutorSafetyViolation> validateRequestedCapabilities(Set<DelegatedExecutorCapability> requestedCapabilities) {
        List<ExecutorSafetyViolation> violations = new ArrayList<>();
        if (requestedCapabilities == null || requestedCapabilities.isEmpty()) return violations;
        for (DelegatedExecutorCapability capability : requestedCapabilities) {
            if (capability == null) continue;
            switch (capability) {
                case COMMAND_EXECUTION -> {
                    if (!allowCommands) violations.add(deniedCapability(capability, "command execution is disabled"));
                }
                case NETWORK_ACCESS -> {
                    if (!allowNetwork) violations.add(deniedCapability(capability, "network access is disabled"));
                }
                case BROWSER_ACCESS -> {
                    if (!allowBrowser) violations.add(deniedCapability(capability, "browser access is disabled"));
                }
                case AUTO_MERGE -> {
                    if (!allowAutoMerge) violations.add(deniedCapability(capability, "auto merge is disabled; parent verification is required"));
                }
                case PATCH_WRITE -> {
                    if (!requirePatchSandbox) violations.add(ExecutorSafetyViolation.of("PATCH_SANDBOX_REQUIRED", "patch writes require an explicit sandbox boundary", capability.name()));
                }
                case FILE_READ -> { /* allowed by policy metadata; real executors still constrain to repo-local reads. */ }
            }
        }
        return violations;
    }

    public List<ExecutorSafetyViolation> validateRequestedCapabilities(List<DelegatedExecutorCapability> requestedCapabilities) {
        return validateRequestedCapabilities(requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities));
    }

    public boolean areCapabilitiesAllowed(Set<DelegatedExecutorCapability> requestedCapabilities) {
        return validateRequestedCapabilities(requestedCapabilities).isEmpty();
    }

    public PatchSandboxPlan createPatchSandboxPlan(String sandboxId, String repositoryRoot, Set<DelegatedExecutorCapability> requestedCapabilities) {
        Set<DelegatedExecutorCapability> requested = requestedCapabilities == null ? defaultCapabilities : Set.copyOf(requestedCapabilities);
        return new PatchSandboxPlan(
            sandboxId,
            repositoryRoot,
            null,
            allowedChangedPathPrefixes,
            deniedChangedPathPrefixes,
            requested.stream().toList(),
            true,
            true,
            requireParentVerification,
            allowAutoMerge,
            null
        );
    }

    public DelegatedTaskExecutionPolicy toExecutionPolicy() {
        return new DelegatedTaskExecutionPolicy(
            false,
            false,
            allowCommands,
            null,
            allowedChangedPathPrefixes,
            ParentVerificationPolicy.allowChangedFilesUnder(allowedChangedPathPrefixes),
            Map.of(
                "safety_contract", "delegated_executor",
                "require_patch_sandbox", requirePatchSandbox,
                "require_parent_verification", requireParentVerification,
                "allow_network", allowNetwork,
                "allow_browser", allowBrowser,
                "allow_auto_merge", allowAutoMerge
            )
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowed_changed_path_prefixes", allowedChangedPathPrefixes);
        m.put("denied_changed_path_prefixes", deniedChangedPathPrefixes);
        m.put("allow_commands", allowCommands);
        m.put("allow_network", allowNetwork);
        m.put("allow_browser", allowBrowser);
        m.put("require_patch_sandbox", requirePatchSandbox);
        m.put("require_parent_verification", requireParentVerification);
        m.put("allow_auto_merge", allowAutoMerge);
        m.put("default_capabilities", defaultCapabilities.stream().map(Enum::name).toList());
        m.put("metadata", metadata);
        return m;
    }

    static List<String> normalizePathPrefixes(List<String> prefixes) {
        if (prefixes == null) return List.of();
        return prefixes.stream()
            .map(DelegatedExecutorSafetyPolicy::normalizePath)
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
            .distinct()
            .toList();
    }

    static String normalizePath(String value) {
        if (value == null) return null;
        String s = value.trim().replace('\\', '/');
        if (s.isBlank()) return s;
        boolean absolute = Path.of(s).isAbsolute();
        try { s = Path.of(s).normalize().toString().replace('\\', '/'); }
        catch (Exception ignored) {}
        while (s.startsWith("./")) s = s.substring(2);
        return absolute && !s.startsWith("/") ? "/" + s : s;
    }

    private static Set<DelegatedExecutorCapability> defaultCapabilitySet(boolean allowCommands, boolean allowNetwork, boolean allowBrowser, boolean allowAutoMerge) {
        EnumSet<DelegatedExecutorCapability> set = EnumSet.of(DelegatedExecutorCapability.FILE_READ, DelegatedExecutorCapability.PATCH_WRITE);
        if (allowCommands) set.add(DelegatedExecutorCapability.COMMAND_EXECUTION);
        if (allowNetwork) set.add(DelegatedExecutorCapability.NETWORK_ACCESS);
        if (allowBrowser) set.add(DelegatedExecutorCapability.BROWSER_ACCESS);
        if (allowAutoMerge) set.add(DelegatedExecutorCapability.AUTO_MERGE);
        return set;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static List<String> listOfStrings(Object value, List<String> fallback) {
        if (!(value instanceof List<?> list)) return fallback;
        List<String> values = list.stream().filter(x -> x != null && !String.valueOf(x).isBlank()).map(String::valueOf).toList();
        return values.isEmpty() ? fallback : values;
    }

    private static boolean booleanOrDefault(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim();
        return s.isBlank() ? fallback : ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s));
    }

    private static Set<DelegatedExecutorCapability> capabilitiesFrom(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return null;
        EnumSet<DelegatedExecutorCapability> set = EnumSet.noneOf(DelegatedExecutorCapability.class);
        for (Object item : list) {
            if (item == null) continue;
            try { set.add(DelegatedExecutorCapability.valueOf(String.valueOf(item).trim().toUpperCase(java.util.Locale.ROOT))); }
            catch (Exception ignored) {}
        }
        return set.isEmpty() ? null : set;
    }

    private static ExecutorSafetyViolation deniedCapability(DelegatedExecutorCapability capability, String message) {
        return ExecutorSafetyViolation.of("CAPABILITY_DENIED", message, capability.name());
    }

    private static boolean matchesAny(String normalizedPath, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"));
    }

    private static boolean isAbsoluteOrEscaping(String normalizedPath) {
        return normalizedPath.startsWith("/") || normalizedPath.equals("..") || normalizedPath.startsWith("../");
    }
}
