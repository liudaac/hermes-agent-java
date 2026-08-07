package com.nousresearch.hermes.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionReferenceTest {

    @TempDir
    Path tempDir;

    private LocalSessionLibrary library;
    private SessionReference sessionRef;

    @BeforeEach
    void setUp() {
        library = new LocalSessionLibrary(tempDir);
        sessionRef = new SessionReference(library);
    }

    @Test
    void shouldBuildReferenceFromExistingAsset() {
        // Given: a saved session asset with steps
        var steps = List.of(
                new SessionAsset.StepSummary(0, "Check environment", "ask_user", "staging", false, 1000),
                new SessionAsset.StepSummary(1, "Pull code", "terminal", "success", true, 2000),
                new SessionAsset.StepSummary(2, "Run tests", "terminal", "42 passed", false, 3000)
        );
        var asset = new SessionAsset(
                "sa_1", "tenant-1", "user-1", "session-1",
                "Deploy Flow", "A deployment session",
                SessionAsset.SessionStatus.COMPLETED,
                false, 0, null, List.of(), steps,
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis()
        );
        library.saveAsset(asset);

        // When: build reference
        String reference = sessionRef.buildReference("tenant-1", "session-1");

        // Then: reference contains the steps
        assertNotNull(reference);
        assertTrue(reference.contains("Deploy Flow"));
        assertTrue(reference.contains("Check environment"));
        assertTrue(reference.contains("Pull code"));
        assertTrue(reference.contains("Run tests"));
        assertTrue(reference.contains("⭐")); // key step marker
    }

    @Test
    void shouldReturnNullForNonExistentSession() {
        String reference = sessionRef.buildReference("tenant-1", "non-existent");
        assertNull(reference);
    }

    @Test
    void shouldInjectReferenceIntoMessage() {
        String userMessage = "Please deploy the app";
        String referenceContext = "[参考流程: \"Deploy Flow\"]\n1. Check environment\n2. Pull code\n";

        String result = sessionRef.injectReference(userMessage, referenceContext);

        assertTrue(result.startsWith(referenceContext));
        assertTrue(result.contains("---"));
        assertTrue(result.endsWith(userMessage));
    }

    @Test
    void shouldReturnOriginalMessageWhenReferenceIsNull() {
        String userMessage = "Hello";
        String result = sessionRef.injectReference(userMessage, null);
        assertEquals(userMessage, result);
    }

    @Test
    void shouldReturnOriginalMessageWhenReferenceIsBlank() {
        String userMessage = "Hello";
        String result = sessionRef.injectReference(userMessage, "  ");
        assertEquals(userMessage, result);
    }

    @Test
    void shouldInjectReferenceFromSessionInOneShot() {
        // Given: a saved asset
        var steps = List.of(
                new SessionAsset.StepSummary(0, "Step A", null, "ok", false, 1000),
                new SessionAsset.StepSummary(1, "Step B", "terminal", "done", true, 2000)
        );
        var asset = new SessionAsset(
                null, "tenant-1", "user-1", "ref-session",
                "Reference Session", "Summary",
                SessionAsset.SessionStatus.COMPLETED,
                false, 0, null, List.of(), steps,
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis()
        );
        library.saveAsset(asset);

        // When: one-shot inject
        String result = sessionRef.injectReferenceFromSession("tenant-1", "ref-session", "Do similar task");

        // Then: message contains reference + original
        assertNotNull(result);
        assertTrue(result.contains("Reference Session"));
        assertTrue(result.contains("Step A"));
        assertTrue(result.contains("Step B"));
        assertTrue(result.contains("Do similar task"));
        assertTrue(result.contains("---"));
    }

    @Test
    void shouldReturnOriginalMessageWhenReferenceSessionNotFound() {
        String result = sessionRef.injectReferenceFromSession("tenant-1", "non-existent", "Hello");
        assertEquals("Hello", result);
    }
}
