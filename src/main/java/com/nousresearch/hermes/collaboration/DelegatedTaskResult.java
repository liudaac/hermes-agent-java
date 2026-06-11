package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Specialist-supplied result for a delegated task simulation.
 *
 * <p>This is a structured handoff artifact only. It represents what an
 * external/specialist session would report back, but does not execute or spawn
 * that session.</p>
 */
public record DelegatedTaskResult(
    String summary,
    List<String> changedFiles,
    List<TestRun> testsRun,
    List<String> risks,
    Instant submittedAt
) {
    public DelegatedTaskResult {
        changedFiles = copyStrings(changedFiles);
        testsRun = testsRun == null ? List.of() : List.copyOf(testsRun);
        risks = copyStrings(risks);
        submittedAt = submittedAt != null ? submittedAt : Instant.now();
    }

    public static DelegatedTaskResult of(String summary, List<String> changedFiles, List<TestRun> testsRun, List<String> risks) {
        return new DelegatedTaskResult(summary, changedFiles, testsRun, risks, Instant.now());
    }

    public boolean allTestsPassed() {
        return testsRun.stream().allMatch(TestRun::passed);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", summary);
        m.put("changed_files", changedFiles);
        m.put("tests_run", testsRun.stream().map(TestRun::toMap).toList());
        m.put("risks", risks);
        m.put("submitted_at", submittedAt.toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegatedTaskResult fromMap(Map<String, Object> m) {
        if (m == null) return null;
        List<String> files = listOfStrings(m.get("changed_files"));
        List<TestRun> tests = new ArrayList<>();
        Object rawTests = m.get("tests_run");
        if (rawTests instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> tm) tests.add(TestRun.fromMap((Map<String, Object>) tm));
                else if (item != null) tests.add(new TestRun(String.valueOf(item), true, ""));
            }
        }
        return new DelegatedTaskResult(
            stringOrNull(m.get("summary")),
            files,
            tests,
            listOfStrings(m.get("risks")),
            parseInstant(m.get("submitted_at"))
        );
    }

    private static List<String> copyStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private static List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(x -> x != null && !String.valueOf(x).isBlank()).map(String::valueOf).toList();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return Instant.now();
        try { return Instant.parse(String.valueOf(value)); }
        catch (Exception ignored) { return Instant.now(); }
    }

    /** A single test command or verification check reported by the specialist. */
    public record TestRun(String name, boolean passed, String details) {
        public static TestRun passed(String name) {
            return new TestRun(name, true, "");
        }

        public static TestRun failed(String name, String details) {
            return new TestRun(name, false, details);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("passed", passed);
            m.put("details", details != null ? details : "");
            return m;
        }

        public static TestRun fromMap(Map<String, Object> m) {
            if (m == null) return new TestRun("", false, "missing test result");
            return new TestRun(
                stringOrNull(m.get("name")),
                Boolean.parseBoolean(String.valueOf(m.getOrDefault("passed", "false"))),
                stringOrNull(m.get("details"))
            );
        }
    }
}
