package com.nousresearch.hermes.harness.session;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

class SessionLogTest {

    private SessionLog log;

    @BeforeEach
    void setUp() {
        log = new SessionLog();
    }

    @Test
    @DisplayName("Surface events produce messages in order")
    void surfaceEventsProduceMessages() {
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "Hello"));
        log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "Hi there"));
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "How are you?"));

        List<ModelMessage> msgs = log.deriveMessages();
        assertEquals(3, msgs.size());
        assertEquals("user", msgs.get(0).getRole());
        assertEquals("Hello", msgs.get(0).getContent());
        assertEquals("assistant", msgs.get(1).getRole());
        assertEquals("Hi there", msgs.get(1).getContent());
        assertEquals("user", msgs.get(2).getRole());
        assertEquals("How are you?", msgs.get(2).getContent());
    }

    @Test
    @DisplayName("Non-surface events are not in derived messages")
    void nonSurfaceEventsExcluded() {
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "Hello"));
        log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 1));
        log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "Hi"));
        log.appendTrace(SessionEventType.STEP_START, Map.of("step", 1));
        log.appendTrace(SessionEventType.TOOL_CALL, Map.of("name", "read_file", "callId", "c1"));
        log.appendSurface(SessionEventType.TOOL_RESULT, Map.of("content", "file contents", "toolCallId", "c1"));
        log.appendTrace(SessionEventType.STEP_END, Map.of("step", 1));
        log.appendTrace(SessionEventType.TURN_END, Map.of("turn", 1));

        List<ModelMessage> msgs = log.deriveMessages();
        assertEquals(3, msgs.size());
        assertEquals("user", msgs.get(0).getRole());
        assertEquals("assistant", msgs.get(1).getRole());
        assertEquals("tool", msgs.get(2).getRole());
        assertEquals("c1", msgs.get(2).getToolCallId());
    }

    @Test
    @DisplayName("REPLACE op shadows a range of events")
    void replaceOpShadowsRange() {
        // Append 5 surface events
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "msg1"));
        log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "msg2"));
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "msg3"));
        log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "msg4"));
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "msg5"));

        // Replace seq 2-4 with a summary
        log.appendReplace(SessionEventType.USER_MESSAGE,
            Map.of("content", "[Summary of msg2-msg4]"),
            2, 4);

        List<ModelMessage> msgs = log.deriveMessages();
        assertEquals(3, msgs.size());
        assertEquals("msg1", msgs.get(0).getContent());
        assertEquals("[Summary of msg2-msg4]", msgs.get(1).getContent());
        assertEquals("msg5", msgs.get(2).getContent());
    }

    @Test
    @DisplayName("Seq counter is monotonic")
    void seqMonotonic() {
        SessionEvent e1 = log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "a"));
        SessionEvent e2 = log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 1));
        SessionEvent e3 = log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "b"));

        assertEquals(1, e1.seq());
        assertEquals(2, e2.seq());
        assertEquals(3, e3.seq());
        assertEquals(3, log.lastSeq());
    }

    @Test
    @DisplayName("Tool result carries toolCallId")
    void toolResultCarriesToolCallId() {
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "read file"));
        log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "let me read"));
        log.appendSurface(SessionEventType.TOOL_RESULT,
            Map.of("content", "file contents here", "toolCallId", "call-123"));

        List<ModelMessage> msgs = log.deriveMessages();
        assertEquals(3, msgs.size());
        assertEquals("tool", msgs.get(2).getRole());
        assertEquals("call-123", msgs.get(2).getToolCallId());
    }

    @Test
    @DisplayName("findLast returns the most recent event of a type")
    void findLastReturnsMostRecent() {
        log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 1));
        log.appendTrace(SessionEventType.TURN_END, Map.of("turn", 1));
        log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 2));

        var last = log.findLast(SessionEventType.TURN_START);
        assertTrue(last.isPresent());
        assertEquals(2, last.get().data().get("turn"));
    }

    @Test
    @DisplayName("findByType returns all events of a type in order")
    void findByTypeReturnsAllInOrder() {
        log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 1));
        log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 2));
        log.appendTrace(SessionEventType.TURN_START, Map.of("turn", 3));

        List<SessionEvent> turns = log.findByType(SessionEventType.TURN_START);
        assertEquals(3, turns.size());
        assertEquals(1, turns.get(0).data().get("turn"));
        assertEquals(3, turns.get(2).data().get("turn"));
    }

    @Test
    @DisplayName("appendSurface rejects non-surface type")
    void appendSurfaceRejectsNonSurface() {
        assertThrows(IllegalArgumentException.class, () ->
            log.appendSurface(SessionEventType.TURN_START, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
            log.appendSurface(SessionEventType.TOOL_CALL, Map.of()));
    }

    @Test
    @DisplayName("Empty log produces empty messages")
    void emptyLogProducesEmptyMessages() {
        assertTrue(log.deriveMessages().isEmpty());
        assertTrue(log.isEmpty());
    }

    @Test
    @DisplayName("Surface seqs track visible events")
    void surfaceSeqsTrack() {
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "a"));
        log.appendSurface(SessionEventType.ASSISTANT_MESSAGE, Map.of("content", "b"));
        log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "c"));

        List<Long> seqs = SessionSurfaceFolder.surfaceSeqs(log.allEvents());
        assertEquals(List.of(1L, 2L, 3L), seqs);

        // Replace seq 1-2: summary (seq 4) takes position of first removed (seq 1)
        log.appendReplace(SessionEventType.USER_MESSAGE,
            Map.of("content", "[summary]"), 1, 2);
        seqs = SessionSurfaceFolder.surfaceSeqs(log.allEvents());
        assertEquals(List.of(4L, 3L), seqs);
    }

    @Test
    @DisplayName("Multiple compactions stack correctly")
    void multipleCompactionsStack() {
        // 6 messages
        for (int i = 1; i <= 6; i++) {
            log.appendSurface(SessionEventType.USER_MESSAGE, Map.of("content", "msg" + i));
        }

        // First compaction: replace 1-3
        log.appendReplace(SessionEventType.USER_MESSAGE,
            Map.of("content", "[summary 1-3]"), 1, 3);

        // Second compaction: replace 4-5
        log.appendReplace(SessionEventType.USER_MESSAGE,
            Map.of("content", "[summary 4-5]"), 4, 5);

        List<ModelMessage> msgs = log.deriveMessages();
        assertEquals(3, msgs.size());
        assertEquals("[summary 1-3]", msgs.get(0).getContent());
        assertEquals("[summary 4-5]", msgs.get(1).getContent());
        assertEquals("msg6", msgs.get(2).getContent());
    }
}
