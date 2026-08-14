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

}
