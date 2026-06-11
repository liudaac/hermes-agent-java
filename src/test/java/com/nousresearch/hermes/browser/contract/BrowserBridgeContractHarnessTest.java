package com.nousresearch.hermes.browser.contract;

import com.nousresearch.hermes.browser.BrowserBridgeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeContractHarnessTest {

    @Test
    void mockDaemonSatisfiesBrowserBridgeContract() throws Exception {
        try (BrowserBridgeMockDaemon daemon = BrowserBridgeMockDaemon.start(0)) {
            BrowserBridgeContractReport report = BrowserBridgeContractVerifier.verify(
                new BrowserBridgeConfig("kimi", daemon.endpoint(), 3000)
            );
            assertTrue(report.ok(), report.toMap().toString());
            assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("capabilities") && c.ok()));
            assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("actions.open") && c.ok()));
            assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("errors.session_missing") && c.ok()));
        }
    }

    @Test
    void verifierReportsFailureForUnavailableDaemon() {
        BrowserBridgeContractReport report = BrowserBridgeContractVerifier.verify(
            new BrowserBridgeConfig("kimi", "http://127.0.0.1:9", 1000)
        );
        assertFalse(report.ok());
        assertTrue(report.checks().stream().anyMatch(c -> !c.ok()));
        assertTrue(report.toMap().toString().contains("daemon_unavailable") || report.toMap().toString().contains("bridge_unavailable"));
    }
}
