package com.nousresearch.hermes.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CognitiveTraceTest {

    @TempDir Path tempDir;

    @Test
    void builder_creates_trace() {
        var trace = CognitiveTrace.builder(1, CognitiveTrace.Phase.OBSERVE)
            .observation("User asked about weather")
            .goal("Answer weather query")
            .build();

        assertEquals(1, trace.turn());
        assertEquals(CognitiveTrace.Phase.OBSERVE, trace.phase());
        assertEquals("User asked about weather", trace.observation());
        assertEquals("Answer weather query", trace.goal());
        assertNotNull(trace.timestamp());
    }

    @Test
    void collector_appends_and_bounds() {
        var collector = new CognitiveTraceCollector("session-1", tempDir);

        for (int i = 0; i < 250; i++) {
            collector.observe(i, "msg " + i);
        }

        assertEquals(200, collector.size(), "Should be bounded to MAX_IN_MEMORY");
        assertFalse(collector.isEmpty());
    }

    @Test
    void collector_exports_jsonl() {
        var collector = new CognitiveTraceCollector("session-2", tempDir);
        collector.observe(1, "hello");
        collector.orient(1, "respond", "user wants greeting");
        collector.decide(1, "say hi", null, 100);

        Path export = tempDir.resolve("export.jsonl");
        collector.exportToJsonl(export);

        assertTrue(export.toFile().exists());
        List<String> lines = assertDoesNotThrow(() -> java.nio.file.Files.readAllLines(export));
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("OBSERVE"));
        assertTrue(lines.get(1).contains("ORIENT"));
        assertTrue(lines.get(2).contains("DECIDE"));
    }

    @Test
    void collector_getTraces_returns_ordered() {
        var collector = new CognitiveTraceCollector("session-3", tempDir);
        collector.observe(1, "first");
        collector.observe(2, "second");

        List<CognitiveTrace> traces = collector.getTraces();
        assertEquals(2, traces.size());
        assertEquals(CognitiveTrace.Phase.OBSERVE, traces.get(0).phase());
        assertEquals("first", traces.get(0).observation());
    }

    @Test
    void collector_close_flushes_to_disk() {
        var collector = new CognitiveTraceCollector("session-4", tempDir);
        collector.observe(1, "hello");
        collector.close();

        Path jsonl = tempDir.resolve("traces_session-4.jsonl");
        assertTrue(jsonl.toFile().exists());
    }
}
