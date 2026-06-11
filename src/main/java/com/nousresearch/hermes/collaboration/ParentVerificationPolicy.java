package com.nousresearch.hermes.collaboration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple parent-side acceptance policy for delegated task simulations.
 *
 * <p>Default policy requires at least one reported test and all tests passing.
 * If allowedChangedFilePrefixes is non-empty, every changed file must live
 * under one of those normalized prefixes.</p>
 */
public record ParentVerificationPolicy(
    boolean requireTests,
    boolean requireAllTestsPassed,
    List<String> allowedChangedFilePrefixes
) {
    public ParentVerificationPolicy {
        allowedChangedFilePrefixes = normalizePrefixes(allowedChangedFilePrefixes);
    }

    public static ParentVerificationPolicy strict() {
        return new ParentVerificationPolicy(true, true, List.of());
    }

    public static ParentVerificationPolicy allowChangedFilesUnder(List<String> allowedChangedFilePrefixes) {
        return new ParentVerificationPolicy(true, true, allowedChangedFilePrefixes);
    }

    public ParentVerificationResult verify(DelegatedTask task, DelegatedTaskResult result) {
        List<String> reasons = new ArrayList<>();
        if (result == null) {
            reasons.add("missing delegated task result");
            return ParentVerificationResult.rejected(reasons);
        }
        if (requireTests && result.testsRun().isEmpty()) {
            reasons.add("no tests reported");
        }
        if (requireAllTestsPassed) {
            result.testsRun().stream()
                .filter(t -> !t.passed())
                .forEach(t -> reasons.add("test failed: " + safeName(t.name())));
        }
        if (!allowedChangedFilePrefixes.isEmpty()) {
            for (String file : result.changedFiles()) {
                if (!isAllowed(file)) reasons.add("changed file outside allowed paths: " + file);
            }
        }
        if (reasons.isEmpty()) return ParentVerificationResult.accept();
        return ParentVerificationResult.rejected(reasons);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("require_tests", requireTests);
        m.put("require_all_tests_passed", requireAllTestsPassed);
        m.put("allowed_changed_file_prefixes", allowedChangedFilePrefixes);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ParentVerificationPolicy fromMap(Map<String, Object> m) {
        if (m == null) return strict();
        List<String> prefixes = m.get("allowed_changed_file_prefixes") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        return new ParentVerificationPolicy(
            Boolean.parseBoolean(String.valueOf(m.getOrDefault("require_tests", "true"))),
            Boolean.parseBoolean(String.valueOf(m.getOrDefault("require_all_tests_passed", "true"))),
            prefixes
        );
    }

    private boolean isAllowed(String file) {
        String normalized = normalizePath(file);
        if (normalized == null || normalized.isBlank()) return false;
        return allowedChangedFilePrefixes.stream().anyMatch(prefix -> normalized.equals(prefix) || normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"));
    }

    private static List<String> normalizePrefixes(List<String> prefixes) {
        if (prefixes == null) return List.of();
        return prefixes.stream()
            .map(ParentVerificationPolicy::normalizePath)
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
            .distinct()
            .toList();
    }

    private static String normalizePath(String value) {
        if (value == null) return null;
        String s = value.trim().replace('\\', '/');
        if (s.isBlank()) return s;
        try { s = Path.of(s).normalize().toString().replace('\\', '/'); }
        catch (Exception ignored) {}
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "(unnamed)" : value;
    }
}
