package com.nousresearch.hermes.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolPerformanceTrackerTest {

    @TempDir Path tempDir;

    @Test
    void record_and_build_hint() {
        ToolPerformanceTracker tracker = new ToolPerformanceTracker(tempDir);

        // Simulate 10 calls: 8 success, 2 failure
        for (int i = 0; i < 8; i++) {
            tracker.record("web_search", true, 500);
        }
        tracker.record("web_search", false, 2000, "timeout after 30s");
        tracker.record("web_search", false, 1500, "rate limit exceeded");

        // Simulate 5 slow but successful calls
        for (int i = 0; i < 5; i++) {
            tracker.record("browser_navigate", true, 15_000);
        }

        String hints = tracker.buildHintBlock();
        assertFalse(hints.isEmpty());
        assertTrue(hints.contains("web_search"));
        assertTrue(hints.contains("browser_navigate"));
        assertTrue(hints.contains("80%"), "Should show 80% success rate");
        assertTrue(hints.contains("slow") || hints.contains("SLOW"), "Should flag slow tool");
    }

    @Test
    void insufficient_samples_yield_empty_block() {
        ToolPerformanceTracker tracker = new ToolPerformanceTracker(tempDir);
        tracker.record("new_tool", true, 100); // only 1 call
        assertTrue(tracker.buildHintBlock().isEmpty());
    }

}
