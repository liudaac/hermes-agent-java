package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    @Test
    void under60PercentDoesNothing() {
        var cm = new ContextManager(10_000, 1_000, 4);
        var history = new java.util.ArrayList<ModelMessage>();
        history.add(ModelMessage.system("system"));
        history.add(ModelMessage.user("hello"));
        history.add(ModelMessage.assistant("hi"));

        var stats = cm.enforce(history, null);
        assertFalse(stats.anythingDone());
        assertEquals(3, history.size());
    }

    @Test
    void shieldsOldToolResultsAt60Percent() {
        var cm = new ContextManager(800, 100, 1); // small window, preserve only 1 recent
        var history = new java.util.ArrayList<ModelMessage>();
        history.add(ModelMessage.system("sys"));
        history.add(ModelMessage.user("do task"));
        // Old tool result with large content
        history.add(ModelMessage.assistant("calling tool"));
        history.add(ModelMessage.tool("X".repeat(2000), "tc1"));
        // Buffer messages so tool result is outside preserve zone
        history.add(ModelMessage.assistant("intermediate step"));
        history.add(ModelMessage.user("next step"));
        // Recent message (preserved)
        history.add(ModelMessage.assistant("final answer"));

        var stats = cm.enforce(history, null);
        assertTrue(stats.toolResultsShielded() > 0);
        // The old tool result should be shielded
        var toolMsg = history.stream()
            .filter(m -> "tool".equals(m.getRole()))
            .findFirst().orElse(null);
        assertNotNull(toolMsg);
        assertTrue(toolMsg.getContent().startsWith("[shielded]"));
        assertTrue(toolMsg.getContent().length() < 300);
    }

}
