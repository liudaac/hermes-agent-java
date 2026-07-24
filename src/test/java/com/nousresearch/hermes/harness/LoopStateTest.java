package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopStateTest {

    @Test
    void lifecycleTransitionsWork() {
        var state = new LoopState(25);
        assertEquals(LoopState.Lifecycle.IDLE, state.lifecycle());

        state.setLifecycle(LoopState.Lifecycle.RUNNING);
        assertTrue(state.isRunning());

        state.setLifecycle(LoopState.Lifecycle.PAUSED_APPROVAL);
        assertTrue(state.isPaused());
        assertFalse(state.isRunning());

        state.setLifecycle(LoopState.Lifecycle.FAILED);
        assertEquals(LoopState.Lifecycle.FAILED, state.lifecycle());
    }

    @Test
    void budgetConsumesAndReports() {
        var state = new LoopState(3);
        assertTrue(state.budget().hasRemaining());
        assertEquals(3, state.iterationsRemaining());

        state.budget().consume();
        state.budget().consume();
        assertEquals(2, state.iterationsUsed());
        assertEquals(1, state.iterationsRemaining());

        state.budget().consume();
        assertFalse(state.budget().hasRemaining());
    }

    @Test
    void resetClearsAllState() {
        var state = new LoopState(10);
        state.addToHistory(com.nousresearch.hermes.model.ModelMessage.user("hello"));
        state.budget().consume();
        state.incrementTurn();
        state.setLifecycle(LoopState.Lifecycle.RUNNING);

        state.reset();

        assertEquals(0, state.historySize());
        assertEquals(0, state.iterationsUsed());
        assertEquals(0, state.userTurnCount());
        assertEquals(LoopState.Lifecycle.IDLE, state.lifecycle());
    }

    @Test
    void serializeAndDeserializeRoundTrip() {
        var state = new LoopState(25);
        state.addToHistory(com.nousresearch.hermes.model.ModelMessage.user("test message"));
        state.addToHistory(com.nousresearch.hermes.model.ModelMessage.assistant("test response"));
        state.incrementTurn();
        state.setLifecycle(LoopState.Lifecycle.RUNNING);

        var json = state.serialize();
        var restored = LoopState.deserialize(json, 25);

        assertEquals(2, restored.historySize());
        assertEquals("test message", restored.history().get(0).getContent());
        assertEquals("test response", restored.history().get(1).getContent());
        assertEquals(1, restored.userTurnCount());
    }
}
