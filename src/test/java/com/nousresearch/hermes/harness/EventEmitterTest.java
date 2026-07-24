package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

class EventEmitterTest {

    @Test
    void subscriberReceivesEvents() {
        var emitter = new EventEmitter("t1", "s1", "a1", null);
        var received = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();

        emitter.subscribe(received::add);
        emitter.emit(AgentEvent.LOOP_START, Map.of("budget", 25));

        assertEquals(1, received.size());
        assertEquals(AgentEvent.LOOP_START, received.get(0).type());
    }

    @Test
    void drainReturnsAndClearsQueue() {
        var emitter = new EventEmitter("t1", "s1", "a1", null);
        emitter.emit(AgentEvent.PRE_LLM, Map.of("iteration", 1));
        emitter.emit(AgentEvent.POST_LLM, Map.of("finishReason", "stop"));

        List<AgentEvent> drained = emitter.drain();
        assertEquals(2, drained.size());
        assertFalse(emitter.hasPending());
    }

    @Test
    void failedSubscriberDoesNotBreakOthers() {
        var emitter = new EventEmitter("t1", "s1", "a1", null);
        var good = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();

        emitter.subscribe(e -> { throw new RuntimeException("boom"); });
        emitter.subscribe(good::add);

        emitter.emit(AgentEvent.LOOP_START, Map.of("budget", 10));

        assertEquals(1, good.size());
    }
}
