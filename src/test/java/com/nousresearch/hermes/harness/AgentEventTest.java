package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class AgentEventTest {

    @Test
    void loopStartEventHasCorrectTypeAndBudget() {
        var event = AgentEvent.loopStart("t1", "s1", "a1", 25);
        assertEquals(AgentEvent.LOOP_START, event.type());
        assertEquals(25, event.data().get("budget"));
        assertEquals("t1", event.tenantId());
    }

    @Test
    void preToolEventCarriesCallIdAndArgs() {
        var event = AgentEvent.preTool("t1", "s1", "a1", "call_123", "web_search",
            Map.of("query", "test"));
        assertEquals(AgentEvent.PRE_TOOL, event.type());
        assertEquals("call_123", event.data().get("callId"));
        assertEquals("web_search", event.data().get("tool"));
    }

    @Test
    void approvalNeededEventHasRiskLevel() {
        var event = AgentEvent.approvalNeeded("t1", "s1", "a1", "call_1", "file_write", "MEDIUM");
        assertEquals(AgentEvent.APPROVAL_NEEDED, event.type());
        assertEquals("MEDIUM", event.data().get("risk"));
    }
}
