package com.nousresearch.hermes.browser.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeProviderProbeTest {

    @Test
    void probeFindsStandardHermesContract() throws Exception {
        try (BrowserBridgeMockDaemon daemon = BrowserBridgeMockDaemon.start(0)) {
            BrowserBridgeProviderProbe.ProbeResult result = new BrowserBridgeProviderProbe(daemon.endpoint(), 3000).probe();
            assertTrue(result.ok(), result.toMap().toString());
            assertEquals(100, result.score());
            assertNotNull(result.bestCandidate());
            assertEquals("webbridge-contract", result.bestCandidate().provider());
            assertEquals("/actions", result.bestCandidate().actionPath());
            assertTrue(result.toMap().toString().contains("recommended_config"));
        }
    }

    @Test
    void probeReportsPartialFailureForUnavailableEndpoint() {
        BrowserBridgeProviderProbe.ProbeResult result = new BrowserBridgeProviderProbe("http://127.0.0.1:9", 1000).probe();
        assertFalse(result.ok());
        assertEquals(0, result.score());
        assertFalse(result.candidates().isEmpty());
    }
}
