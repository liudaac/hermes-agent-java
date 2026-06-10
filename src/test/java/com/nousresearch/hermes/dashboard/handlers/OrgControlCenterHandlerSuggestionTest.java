package com.nousresearch.hermes.dashboard.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrgControlCenterHandlerSuggestionTest {
    @Test
    void highImpactAnomaliesSuggestDeprioritize() {
        var suggestion = OrgControlCenterHandler.anomalySuggestion("HIGH_LATENCY", "agent-1", "slow responses");

        assertEquals("agent_override", suggestion.get("kind"));
        assertEquals("deprioritized", suggestion.get("mode"));
        assertEquals("agent-1", suggestion.get("target_agent"));
        assertEquals(60 * 60 * 1000, suggestion.get("ttl_ms"));
    }

    @Test
    void costSpikeSuggestsMonitoring() {
        var suggestion = OrgControlCenterHandler.anomalySuggestion("COST_SPIKE", "agent-1", "cost spike");

        assertEquals("monitor", suggestion.get("kind"));
        assertEquals("Monitor cost spike", suggestion.get("label"));
    }
}
