package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopResultTest {

    @Test
    void completedResult() {
        var result = new LoopResult.Completed("hello world");
        assertTrue(result.isCompleted());
        assertFalse(result.isPaused());
        assertFalse(result.isFailed());
        assertEquals("hello world", ((LoopResult.Completed) result).response());
    }

    @Test
    void pausedResult() {
        var state = new LoopState(10);
        var result = new LoopResult.Paused(state);
        assertTrue(result.isPaused());
        assertFalse(result.isCompleted());
    }

    @Test
    void failedResult() {
        var result = new LoopResult.Failed("something broke");
        assertTrue(result.isFailed());
        assertEquals("something broke", ((LoopResult.Failed) result).error());
    }
}
