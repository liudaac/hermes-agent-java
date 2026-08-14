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

}
