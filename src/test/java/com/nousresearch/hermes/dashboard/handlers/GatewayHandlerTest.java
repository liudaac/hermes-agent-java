package com.nousresearch.hermes.dashboard.handlers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayHandlerTest {

    @Test
    @DisplayName("Unsupported gateway actions should not report fake success or fake PID")
    void unsupportedActionsDoNotReportFakeSuccess() {
        GatewayHandler handler = new GatewayHandler();

        Map<String, Object> response = handler.markUnsupported(
            "gateway-restart",
            "not wired"
        );

        assertEquals("gateway-restart", response.get("name"));
        assertEquals(false, response.get("ok"));
        assertEquals(true, response.get("unsupported"));
        assertEquals(false, response.get("running"));
        assertEquals(2, response.get("exit_code"));
        assertNull(response.get("pid"));
        assertTrue(response.get("message").toString().contains("not wired"));
    }

    @Test
    @DisplayName("Action status mapping should cap returned log lines")
    void actionStatusMappingCapsLines() {
        GatewayHandler handler = new GatewayHandler();
        GatewayHandler.ActionStatus status = new GatewayHandler.ActionStatus();
        status.name = "action";
        status.running = false;
        status.exitCode = 2;
        status.pid = null;
        status.lines = java.util.List.of("one", "two", "three");

        Map<String, Object> mapped = handler.toActionStatusMap(status, 2);

        assertEquals(java.util.List.of("two", "three"), mapped.get("lines"));
    }
}
