package com.nousresearch.hermes.testutil;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** JUnit extension that points Constants.getHermesHome() at a temp directory. */
public class IsolatedHermesHome implements BeforeEachCallback, AfterEachCallback {
    private String previousHermesHome;
    private String previousLegacyHermesHome;
    private Path tempHome;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        previousHermesHome = System.getProperty("hermes.home");
        previousLegacyHermesHome = System.getProperty("HERMES_HOME");
        tempHome = Files.createTempDirectory("hermes-test-home-");
        System.setProperty("hermes.home", tempHome.toString());
        System.setProperty("HERMES_HOME", tempHome.toString());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        restore("hermes.home", previousHermesHome);
        restore("HERMES_HOME", previousLegacyHermesHome);
        deleteRecursively(tempHome);
    }

    public Path getTempHome() { return tempHome; }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try {
            if (Files.exists(root)) {
                Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
            }
        } catch (IOException ignored) {}
    }
}
