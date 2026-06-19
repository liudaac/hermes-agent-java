package com.nousresearch.hermes.business.foundation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight architecture checks for the Business Portal foundation boundary.
 *
 * <p>This intentionally avoids adding an ArchUnit dependency. It protects the
 * new adapter-first contract with source-level checks that are cheap to run in
 * the existing Maven/JUnit setup.</p>
 */
class BusinessPortalFoundationArchitectureTest {
    private static final Path BUSINESS_MAIN = Path.of("src/main/java/com/nousresearch/hermes/business");

    private static final List<Pattern> LOW_LEVEL_FOUNDATION_IMPORTS = List.of(
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.approval\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.collaboration\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.org\\.evolution\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.org\\.observe\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.tenant\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.tools\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.prompt\\..*;"),
        Pattern.compile("^import com\\.nousresearch\\.hermes\\.evolution\\..*;")
    );

    private static final Set<String> EXPLICITLY_ALLOWED_FILES = Set.of(
        "src/main/java/com/nousresearch/hermes/business/approval/BusinessApprovalAdapter.java",
        "src/main/java/com/nousresearch/hermes/business/run/BusinessRunProjectionAdapter.java",
        "src/main/java/com/nousresearch/hermes/business/insight/BusinessInsightProjectionAdapter.java",
        "src/main/java/com/nousresearch/hermes/business/insight/BusinessEvalRunProjectionAdapter.java",
        "src/main/java/com/nousresearch/hermes/business/safetyvalve/BusinessSafetyValveAdapter.java"
    );

    @Test
    void ordinaryBusinessPortalClassesDoNotBypassFoundationFacadeOrAdapters() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BUSINESS_MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (isAllowedBoundaryClass(file)) continue;
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (LOW_LEVEL_FOUNDATION_IMPORTS.stream().anyMatch(pattern -> pattern.matcher(trimmed).matches())) {
                        violations.add(file + " -> " + trimmed);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> "Business Portal classes must use BusinessPortalFoundationFacade or explicit adapters instead of low-level foundation imports:\n"
            + String.join("\n", violations));
    }

    @Test
    void facadeAndRegistryRemainTheOnlyBusinessFoundationWiringClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        Path foundationDir = BUSINESS_MAIN.resolve("foundation");
        try (Stream<Path> files = Files.walk(foundationDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (!name.equals("BusinessPortalFoundationFacade.java") && !name.equals("BusinessPortalAdapterRegistry.java")
                    && !name.equals("BusinessPortalFoundationDiagnostics.java")) {
                    violations.add(file.toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "business.foundation should stay a thin boundary package; unexpected classes:\n"
            + String.join("\n", violations));
    }

    private boolean isAllowedBoundaryClass(Path file) {
        String normalized = file.toString().replace('\\', '/');
        if (normalized.contains("/business/foundation/")) return true;
        return EXPLICITLY_ALLOWED_FILES.contains(normalized);
    }
}
