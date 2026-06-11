package com.nousresearch.hermes.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative Java-local delegated executor that applies a caller-provided
 * unified diff in an isolated temporary sandbox.
 *
 * <p>This executor deliberately does not call OpenClaw, spawn shells, run git,
 * use network access, or merge changes back into the parent checkout. Its only
 * write target is a temp-directory sandbox. The parent repository is used as a
 * read-only source for files referenced by the patch.</p>
 */
public class LocalPatchExecutor implements DelegatedTaskExecutor {
    public static final String NAME = "local_patch";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DelegatedTaskExecutionResult execute(DelegatedTask task, DelegatedTaskExecutionPolicy policy) {
        DelegatedTaskExecutionPolicy effectivePolicy = policy != null ? policy : DelegatedTaskExecutionPolicy.safeDefault();
        String patch = patchText(effectivePolicy.metadata());
        if (patch == null || patch.isBlank()) {
            return DelegatedTaskExecutionResult.notExecuted(NAME, "NO_PATCH_PROVIDED", "No patch/diff was provided in execution policy metadata", effectivePolicy);
        }
        if (!effectivePolicy.allowFileChanges()) {
            return DelegatedTaskExecutionResult.notExecuted(NAME, "FILE_CHANGES_NOT_ALLOWED", "LocalPatchExecutor requires allow_file_changes=true because it materializes patched files in a sandbox", effectivePolicy);
        }

        DelegatedExecutorSafetyPolicy safetyPolicy = safetyPolicy(effectivePolicy);
        Set<DelegatedExecutorCapability> requested = EnumSet.of(DelegatedExecutorCapability.FILE_READ, DelegatedExecutorCapability.PATCH_WRITE);
        List<ExecutorSafetyViolation> capabilityViolations = safetyPolicy.validateRequestedCapabilities(requested);
        if (!capabilityViolations.isEmpty()) {
            return safetyDenied(effectivePolicy, "Requested local patch capabilities were denied", capabilityViolations);
        }

        List<PatchFile> patchFiles;
        try {
            patchFiles = parseUnifiedDiff(patch);
        } catch (IllegalArgumentException ex) {
            return DelegatedTaskExecutionResult.notExecuted(NAME, "INVALID_PATCH", ex.getMessage(), effectivePolicy);
        }
        if (patchFiles.isEmpty()) {
            return DelegatedTaskExecutionResult.notExecuted(NAME, "NO_PATCH_PROVIDED", "Patch did not contain any changed files", effectivePolicy);
        }
        List<String> changedFiles = patchFiles.stream().map(PatchFile::path).distinct().toList();
        List<ExecutorSafetyViolation> pathViolations = safetyPolicy.validateChangedFiles(changedFiles);
        if (!pathViolations.isEmpty()) {
            return safetyDenied(effectivePolicy, "Patch changes files outside the delegated executor safety policy", pathViolations);
        }

        Path repositoryRoot = repositoryRoot(effectivePolicy.metadata());
        Path sandboxRoot;
        try {
            sandboxRoot = Files.createTempDirectory("hermes-local-patch-");
            for (PatchFile file : patchFiles) {
                applyFilePatch(repositoryRoot, sandboxRoot, file);
            }
        } catch (Exception ex) {
            return DelegatedTaskExecutionResult.notExecuted(NAME, "PATCH_APPLY_FAILED", ex.getMessage(), effectivePolicy);
        }

        List<DelegatedTaskResult.TestRun> tests = reportedTests(effectivePolicy.metadata());
        DelegatedTaskResult result = new DelegatedTaskResult(
            "Applied provided patch in isolated local sandbox: " + sandboxRoot,
            changedFiles,
            tests,
            List.of(
                "sandbox_root=" + sandboxRoot,
                "parent_checkout_unmodified=true",
                "auto_merge=false",
                "commands_executed=false"
            ),
            Instant.now()
        );
        return DelegatedTaskExecutionResult.executed(NAME, "Patch applied in sandbox_root=" + sandboxRoot, result, effectivePolicy);
    }

    @SuppressWarnings("unchecked")
    private static DelegatedExecutorSafetyPolicy safetyPolicy(DelegatedTaskExecutionPolicy policy) {
        Object raw = policy.metadata().get("safety_policy");
        if (raw instanceof Map<?, ?> map) {
            return DelegatedExecutorSafetyPolicy.fromMap((Map<String, Object>) map);
        }
        return policy.toExecutorSafetyPolicy();
    }

    private static DelegatedTaskExecutionResult safetyDenied(DelegatedTaskExecutionPolicy policy, String message, List<ExecutorSafetyViolation> violations) {
        String details = violations.stream()
            .map(v -> v.code() + ":" + v.subject())
            .reduce((a, b) -> a + ", " + b)
            .orElse("safety denied");
        return DelegatedTaskExecutionResult.notExecuted(NAME, "SAFETY_DENIED", message + " (" + details + ")", policy);
    }

    private static String patchText(Map<String, Object> metadata) {
        if (metadata == null) return null;
        for (String key : List.of("patch", "diff", "patch_text", "patchText", "unified_diff", "unifiedDiff")) {
            Object value = metadata.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        Object result = metadata.get("result");
        if (result instanceof Map<?, ?> map) {
            for (String key : List.of("patch", "diff", "patch_text", "unified_diff")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        }
        return null;
    }

    private static Path repositoryRoot(Map<String, Object> metadata) {
        Object raw = firstNonNull(
            firstNonNull(metadata.get("repository_root"), metadata.get("repo_root")),
            firstNonNull(metadata.get("workspace_root"), metadata.get("working_directory"))
        );
        if (raw == null || String.valueOf(raw).isBlank()) return Path.of("").toAbsolutePath().normalize();
        return Path.of(String.valueOf(raw)).toAbsolutePath().normalize();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static void applyFilePatch(Path repositoryRoot, Path sandboxRoot, PatchFile patchFile) throws IOException {
        Path relative = Path.of(patchFile.path()).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("Patch path escapes repository: " + patchFile.path());
        Path source = repositoryRoot.resolve(relative).normalize();
        if (!source.startsWith(repositoryRoot)) throw new IOException("Patch source escapes repository: " + patchFile.path());
        Path target = sandboxRoot.resolve(relative).normalize();
        if (!target.startsWith(sandboxRoot)) throw new IOException("Patch target escapes sandbox: " + patchFile.path());

        List<String> original = Files.exists(source) ? Files.readAllLines(source, StandardCharsets.UTF_8) : List.of();
        List<String> patched = applyHunks(original, patchFile);
        Files.createDirectories(target.getParent());
        Files.write(target, patched, StandardCharsets.UTF_8);
    }

    private static List<String> applyHunks(List<String> original, PatchFile file) {
        List<String> output = new ArrayList<>();
        int oldIndex = 0;
        for (PatchHunk hunk : file.hunks()) {
            int hunkOldIndex = Math.max(0, hunk.oldStart() - 1);
            if (hunkOldIndex < oldIndex) throw new IllegalArgumentException("Overlapping hunk in patch for " + file.path());
            while (oldIndex < hunkOldIndex && oldIndex < original.size()) output.add(original.get(oldIndex++));
            for (String line : hunk.lines()) {
                if (line.equals("\\ No newline at end of file")) continue;
                if (line.isEmpty()) throw new IllegalArgumentException("Invalid empty hunk line in patch for " + file.path());
                char marker = line.charAt(0);
                String content = line.length() > 1 ? line.substring(1) : "";
                switch (marker) {
                    case ' ' -> {
                        requireOriginalLine(original, oldIndex, content, file.path());
                        output.add(content);
                        oldIndex++;
                    }
                    case '-' -> {
                        requireOriginalLine(original, oldIndex, content, file.path());
                        oldIndex++;
                    }
                    case '+' -> output.add(content);
                    case '\\' -> { /* marker line, ignored */ }
                    default -> throw new IllegalArgumentException("Invalid hunk marker '" + marker + "' in patch for " + file.path());
                }
            }
        }
        while (oldIndex < original.size()) output.add(original.get(oldIndex++));
        return output;
    }

    private static void requireOriginalLine(List<String> original, int index, String expected, String path) {
        if (index >= original.size()) throw new IllegalArgumentException("Patch context exceeds source file for " + path);
        String actual = original.get(index);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Patch context mismatch for " + path + " at source line " + (index + 1));
        }
    }

    private static List<PatchFile> parseUnifiedDiff(String patch) {
        String[] rawLines = patch.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<PatchFile> files = new ArrayList<>();
        String oldPath = null;
        String newPath = null;
        List<PatchHunk> hunks = new ArrayList<>();
        PatchHunk currentHunk = null;

        for (String line : rawLines) {
            if (line.startsWith("diff --git ")) {
                if (newPath != null && !hunks.isEmpty()) files.add(new PatchFile(cleanPatchPath(newPath), List.copyOf(hunks)));
                oldPath = null;
                newPath = null;
                hunks = new ArrayList<>();
                currentHunk = null;
                continue;
            }
            if (line.startsWith("--- ")) {
                oldPath = line.substring(4).trim();
                continue;
            }
            if (line.startsWith("+++ ")) {
                newPath = line.substring(4).trim();
                continue;
            }
            if (line.startsWith("@@ ")) {
                if (newPath == null || newPath.isBlank()) throw new IllegalArgumentException("Patch hunk is missing +++ target path");
                currentHunk = new PatchHunk(parseOldStart(line), new ArrayList<>());
                hunks.add(currentHunk);
                continue;
            }
            if (currentHunk != null) {
                if (line.startsWith("diff --git ") || line.startsWith("--- ") || line.startsWith("+++ ")) {
                    // handled by outer branches on the next iteration in normal diffs
                    continue;
                }
                if (line.isEmpty()) continue;
                currentHunk.lines().add(line);
            }
        }
        if (newPath != null && !hunks.isEmpty()) files.add(new PatchFile(cleanPatchPath(newPath), List.copyOf(hunks)));
        return files.stream()
            .filter(f -> f.path() != null && !f.path().isBlank() && !"/dev/null".equals(f.path()))
            .toList();
    }

    private static int parseOldStart(String hunkHeader) {
        int minus = hunkHeader.indexOf('-');
        if (minus < 0) throw new IllegalArgumentException("Invalid hunk header: " + hunkHeader);
        int cursor = minus + 1;
        int start = cursor;
        while (cursor < hunkHeader.length() && Character.isDigit(hunkHeader.charAt(cursor))) cursor++;
        if (cursor == start) return 1;
        return Integer.parseInt(hunkHeader.substring(start, cursor));
    }

    private static String cleanPatchPath(String raw) {
        if (raw == null) return null;
        String path = raw.trim();
        int tab = path.indexOf('\t');
        if (tab >= 0) path = path.substring(0, tab);
        int space = path.indexOf(' ');
        if (space >= 0) path = path.substring(0, space);
        if (path.startsWith("a/") || path.startsWith("b/")) path = path.substring(2);
        return path.replace('\\', '/');
    }

    @SuppressWarnings("unchecked")
    private static List<DelegatedTaskResult.TestRun> reportedTests(Map<String, Object> metadata) {
        Object raw = firstNonNull(metadata.get("tests_run"), metadata.get("testsRun"));
        if (!(raw instanceof List<?> list)) return List.of();
        List<DelegatedTaskResult.TestRun> tests = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                tests.add(DelegatedTaskResult.TestRun.fromMap((Map<String, Object>) map));
            } else if (item != null) {
                tests.add(DelegatedTaskResult.TestRun.passed(String.valueOf(item)));
            }
        }
        return tests;
    }

    private record PatchFile(String path, List<PatchHunk> hunks) {}
    private record PatchHunk(int oldStart, List<String> lines) {}
}
