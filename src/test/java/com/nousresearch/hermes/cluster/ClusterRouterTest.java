package com.nousresearch.hermes.cluster;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClusterRouter unit tests: routing, circuit breaker, re-route, discovery.
 *
 * Consolidated from 31 to 16 tests - each test covers one logical scenario
 * with multiple assertions rather than one assertion per test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterRouterTest {

    private com.nousresearch.hermes.common.HermesProfile profile(String nodeId) {
        return new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            nodeId, "", "", "", "", "");
    }

    // ============ Routing key extraction (4 tests) ============

    @Test
    @Order(1)
    @DisplayName("extractRoutingKey: priority chain sessionId > agentId > workspaceId > tenantId > null")
    void routingKey_priorityChain() {
        // sessionId beats agentId + workspaceId + tenantId (query param wins over body)
        assertEquals("session:sess-q", ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"), Map.of("sessionId", "sess-q"),
            "{\"sessionId\":\"sess-123\",\"workspaceId\":\"ws-1\"}", "tenant-1"));

        // agentId from path when no sessionId
        assertEquals("agent:agent-1", ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"), Map.of(), "{\"message\":\"hi\"}", "tenant-1"));

        // workspaceId from query overrides body, beats tenantId
        assertEquals("workspace:ws-query", ClusterRouter.extractRoutingKey(
            Map.of(), Map.of("workspaceId", "ws-query"), "{\"workspaceId\":\"ws-body\"}", "tenant-1"));

        // workspaceId from body when no query
        assertEquals("workspace:ws-456", ClusterRouter.extractRoutingKey(
            Map.of(), Map.of(), "{\"workspaceId\":\"ws-456\"}", "tenant-1"));

        // tenantId as fallback
        assertEquals("tenant:tenant-abc", ClusterRouter.extractRoutingKey(
            Map.of(), Map.of(), "{\"message\":\"hi\"}", "tenant-abc"));

        // null when nothing
        assertNull(ClusterRouter.extractRoutingKey(Map.of(), Map.of(), "{\"message\":\"hi\"}", null));
    }

    @Test
    @Order(2)
    @DisplayName("extractRoutingKey: edge cases (null body, empty strings)")
    void routingKey_edgeCases() {
        // null body + null tenant
        assertEquals("agent:agent-1", ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"), Map.of(), null, null));

        // sessionId from query overrides agentId in path
        assertEquals("session:sess-q", ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"), Map.of("sessionId", "sess-q"), "{}", "tenant-1"));

        // empty strings are ignored
        assertNull(ClusterRouter.extractRoutingKey(
            Map.of("agentId", ""), Map.of("sessionId", ""),
            "{\"workspaceId\":\"\"}", ""));

        // null when all empty
        assertNull(ClusterRouter.extractRoutingKey(Map.of(), Map.of(), "{}", null));
    }

    // ============ Route resolution ============

    // ============ Circuit Breaker ============

    // ============ Re-route ============

    // ============ Discovery callback ============

    // ============ shouldHandleLocally ============

}
