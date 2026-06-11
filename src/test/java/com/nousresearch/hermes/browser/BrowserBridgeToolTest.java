package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.testutil.TestTenants;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void browserBridgeExecutesThroughMockAndRecordsTrace() throws Exception {
        TenantContext tenant = TestTenants.create("browser-bridge-test");
        try {
        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);

        String opened = dispatcher.dispatch("browser_bridge", Map.of(
            "action", "open",
            "url", "https://example.com",
            "actor", "dashboard",
            "reason", "smoke-test browser bridge"
        ));

        JsonNode openJson = MAPPER.readTree(opened);
        assertTrue(openJson.get("ok").asBoolean());
        assertEquals("MockBrowserBridge", openJson.get("provider").asText());
        assertNotNull(openJson.get("session_id").asText());
        assertNotNull(openJson.get("trace_id").asText());

        String observed = dispatcher.dispatch("browser_bridge", Map.of(
            "action", "observe",
            "session_id", openJson.get("session_id").asText(),
            "actor", "dashboard",
            "reason", "observe mock page"
        ));
        JsonNode observeJson = MAPPER.readTree(observed);
        assertTrue(observeJson.get("ok").asBoolean());
        assertTrue(observeJson.get("content").asText().contains("Mock page"));

        var traces = tenant.getObservability().getRecentTraces("browser-bridge", 10);
        assertTrue(traces.size() >= 2);
        assertTrue(traces.stream().anyMatch(t -> t.getTaskDescription().contains("browser_bridge:open")));
        } finally { TestTenants.cleanup(tenant); }
    }

    @Test
    void browserBridgeBlocksSensitiveActionsWithoutConfirmation() throws Exception {
        TenantContext tenant = TestTenants.create("browser-bridge-policy-test");
        try {
        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);

        String result = dispatcher.dispatch("browser_bridge", Map.of(
            "action", "click",
            "target", "Delete account button",
            "actor", "viewer",
            "reason", "delete account"
        ));

        JsonNode json = MAPPER.readTree(result);
        assertTrue(json.has("error"));
        assertTrue(json.get("requires_confirmation").asBoolean());
        assertTrue(tenant.getObservability().getRecentTraces("browser-bridge", 10).isEmpty());
        } finally { TestTenants.cleanup(tenant); }
    }

    @Test
    void browserBridgeAllowsSensitiveActionsWhenConfirmed() throws Exception {
        TenantContext tenant = TestTenants.create("browser-bridge-confirmed-test");
        try {
        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);

        JsonNode openJson = MAPPER.readTree(dispatcher.dispatch("browser_bridge", Map.of(
            "action", "open",
            "url", "https://example.com/settings",
            "actor", "operator",
            "reason", "prepare confirmed action"
        )));

        String result = dispatcher.dispatch("browser_bridge", Map.of(
            "action", "click",
            "session_id", openJson.get("session_id").asText(),
            "target", "Delete draft button",
            "actor", "operator",
            "reason", "confirmed cleanup of draft",
            "confirmed", true
        ));

        JsonNode json = MAPPER.readTree(result);
        assertTrue(json.get("ok").asBoolean());
        assertEquals("performed click", json.get("message").asText());
        } finally { TestTenants.cleanup(tenant); }
    }
}
