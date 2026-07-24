package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;

import java.util.List;

class LoopCheckpointTest {

    @Test
    void pendingToolCallReturnsCorrectIndex() {
        var msg = ModelMessage.assistant("let me search");
        var tc1 = new ToolCall();
        tc1.setId("tc1");
        var tc2 = new ToolCall();
        tc2.setId("tc2");
        var tc3 = new ToolCall();
        tc3.setId("tc3");

        var cp = new LoopCheckpoint(msg, List.of(tc1, tc2, tc3), 1,
            List.of(new LoopCheckpoint.ToolCallResult("tc1", "result1")),
            5, 20, 3);

        assertEquals("tc2", cp.pendingToolCall().getId());
        assertEquals(1, cp.remainingToolCalls());
    }

    @Test
    void noRemainingCallsWhenPendingIsLast() {
        var msg = ModelMessage.assistant("done");
        var tc = new ToolCall();
        tc.setId("tc_last");

        var cp = new LoopCheckpoint(msg, List.of(tc), 0,
            List.of(), 3, 22, 1);

        assertEquals(0, cp.remainingToolCalls());
    }
}
