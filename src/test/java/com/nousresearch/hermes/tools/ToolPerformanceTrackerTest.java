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

    @Test
    void persist_and_reload() {
        ToolPerformanceTracker t1 = new ToolPerformanceTracker(tempDir);
        for (int i = 0; i < 3; i++) t1.record("alpha", true, 100);
        t1.record("alpha", false, 200, "bad arg");
        for (int i = 0; i < 3; i++) t1.record("beta", true, 300);
        t1.save();

        ToolPerformanceTracker t2 = new ToolPerformanceTracker(tempDir);
        String hints = t2.buildHintBlock();
        assertTrue(hints.contains("alpha"));
        assertTrue(hints.contains("beta"));
    }

    @Test
    void recency_score_decays() throws InterruptedException {
        ToolPerformanceTracker tracker = new ToolPerformanceTracker(tempDir);
        for (int i = 0; i < 3; i++) tracker.record("old_tool", true, 100);
        Thread.sleep(10); // ensure different timestamp
        for (int i = 0; i < 3; i++) tracker.record("new_tool", true, 100);

        String hints = tracker.buildHintBlock();
        assertTrue(hints.contains("old_tool"));
        assertTrue(hints.contains("new_tool"));
    }
}
