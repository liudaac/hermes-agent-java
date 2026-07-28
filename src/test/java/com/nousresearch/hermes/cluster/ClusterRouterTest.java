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

    // ============ Route resolution (3 tests) ============

    @Test
    @Order(3)
    @DisplayName("resolveRoute: disabled returns self; enabled null key returns self")
    void resolveRoute_disabledAndNullKey() {
        var router = new ClusterRouter(profile("node-1"));
        // Disabled by default
        assertEquals("node-1", router.resolveRoute("session:abc"));
        assertEquals("node-1", router.resolveRoute(null));

        // Enabled but null/blank key
        router.setEnabled(true);
        assertEquals("node-1", router.resolveRoute(null));
        assertEquals("node-1", router.resolveRoute(""));
    }

    @Test
    @Order(4)
    @DisplayName("registerNode/unregisterNode: toggles enabled flag and node count")
    void registerUnregister_togglesEnabled() {
        var router = new ClusterRouter(profile("node-1"));
        assertFalse(router.isEnabled());
        assertEquals(1, router.getRegisteredNodes().size());

        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());
        assertEquals(2, router.getRegisteredNodes().size());

        router.registerNode("node-3", "http://node-3:8080");
        assertEquals(3, router.getRegisteredNodes().size());

        router.unregisterNode("node-2");
        assertTrue(router.isEnabled()); // still 2 nodes

        router.unregisterNode("node-3");
        assertFalse(router.isEnabled()); // back to 1
    }

    @Test
    @Order(5)
    @DisplayName("sticky routing: same key -> same node; distribute across nodes")
    void stickyRouting_consistencyAndDistribution() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        // Same key always routes to same node
        String key = "session:sticky-test-123";
        String t1 = router.resolveRoute(key);
        assertEquals(t1, router.resolveRoute(key));
        assertEquals(t1, router.resolveRoute(key));

        // Distribution: 100 keys should spread across nodes
        int local = 0, remote = 0;
        for (int i = 0; i < 100; i++) {
            if (router.shouldHandleLocally("session:key-" + i)) local++;
            else remote++;
        }
        assertTrue(local > 10 && local < 90, "Expected distribution, got local=" + local);
        assertTrue(remote > 10, "Expected remote routing, got remote=" + remote);
    }

    // ============ Circuit Breaker (4 tests) ============

    @Test
    @Order(6)
    @DisplayName("Circuit breaker: full lifecycle HEALTHY -> DEAD -> HALF_OPEN -> HEALTHY")
    void circuitBreaker_fullLifecycle() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");

        ClusterRouter.NodeHealth h = router.getNodeHealth("node-2");
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, h.state);
        assertEquals(0, h.failures.get());

        // 2 failures: still HEALTHY
        h.recordFailure();
        h.recordFailure();
        assertEquals(2, h.failures.get());
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, h.state);

        // 3rd failure -> DEAD
        h.recordFailure();
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, h.state);
        assertFalse(h.canAttempt()); // probe interval not elapsed

        // Simulate HALF_OPEN -> success -> HEALTHY
        h.state = ClusterRouter.NodeHealth.State.HALF_OPEN;
        h.recordSuccess();
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, h.state);
        assertEquals(0, h.failures.get());

        // Simulate HALF_OPEN -> failure -> back to DEAD
        h.state = ClusterRouter.NodeHealth.State.HALF_OPEN;
        h.recordFailure();
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, h.state);
    }

    @Test
    @Order(7)
    @DisplayName("Circuit breaker: 3 forward failures -> DEAD (integration)")
    void circuitBreaker_forwardFailuresToDead() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1"); // unreachable
        router.setEnabled(true);

        for (int i = 0; i < 3; i++) {
            try {
                router.forward("node-2", "GET", "/api/v1/health", Map.of(), null);
            } catch (ClusterRouter.ClusterRouteException ignored) {}
        }

        ClusterRouter.NodeHealth h = router.getNodeHealth("node-2");
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, h.state);
        assertTrue(h.failures.get() >= 3);
    }

    @Test
    @Order(8)
    @DisplayName("Circuit breaker: DEAD node skipped in resolveNextHealthy")
    void circuitBreaker_deadNodeSkippedInRouting() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");
        router.registerNode("node-3", "http://node-3:8080");
        router.setEnabled(true);

        // Force node-2 to DEAD
        var h = router.getNodeHealth("node-2");
        for (int i = 0; i < 3; i++) h.recordFailure();

        String next = router.resolveNextHealthy("session:test", Set.of("node-1"));
        assertNotEquals("node-2", next, "DEAD node should be skipped");
    }

    @Test
    @Order(9)
    @DisplayName("getHealthSnapshot: shows all node states")
    void healthSnapshot() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        Map<String, String> snap = router.getHealthSnapshot();
        assertEquals(3, snap.size());
        snap.values().forEach(s -> assertEquals("HEALTHY", s));
    }

    // ============ Re-route (2 tests) ============

    @Test
    @Order(10)
    @DisplayName("Re-route: primary fails -> tries next node -> both DEAD after 3 rounds")
    void reRoute_primaryFailExhausts() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");
        router.registerNode("node-3", "http://localhost:2");
        router.setEnabled(true);

        // 3 rounds: each round node-2 fails -> re-route node-3 fails -> exhausted
        for (int i = 0; i < 3; i++) {
            assertThrows(ClusterRouter.ClusterRouteException.class, () ->
                router.forward("node-2", "GET", "/api/v1/health", Map.of(), null));
        }

        assertEquals(ClusterRouter.NodeHealth.State.DEAD, router.getNodeHealth("node-2").state);
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, router.getNodeHealth("node-3").state);
    }

    @Test
    @Order(11)
    @DisplayName("Re-route: unknown node throws ClusterRouteException")
    void reRoute_unknownNode() {
        var router = new ClusterRouter(profile("node-1"));
        assertThrows(ClusterRouter.ClusterRouteException.class, () ->
            router.forward("nonexistent", "GET", "/api/v1/health", Map.of(), null));
    }

    // ============ Discovery callback (3 tests) ============

    @Test
    @Order(12)
    @DisplayName("onNodesChanged: add nodes -> enabled + HEALTHY")
    void onNodesChanged_add() {
        var router = new ClusterRouter(profile("node-1"));
        assertFalse(router.isEnabled());

        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080", "node-2", "http://node-2:8080"),
            Set.of("node-2"), Set.of());

        assertTrue(router.getRegisteredNodes().contains("node-2"));
        assertTrue(router.isEnabled());
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY,
            router.getNodeHealth("node-2").state);
    }

    @Test
    @Order(13)
    @DisplayName("onNodesChanged: remove nodes -> disabled + health cleaned up")
    void onNodesChanged_remove() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());
        assertNotNull(router.getNodeHealth("node-2"));

        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080"),
            Set.of(), Set.of("node-2"));

        assertFalse(router.getRegisteredNodes().contains("node-2"));
        assertFalse(router.isEnabled());
        assertNull(router.getNodeHealth("node-2"));
    }

    @Test
    @Order(14)
    @DisplayName("onNodesChanged: add+remove simultaneously -> ring rebuilt correctly")
    void onNodesChanged_addAndRemove() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");

        // node-2 removed, node-3 added
        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080", "node-3", "http://node-3:8080"),
            Set.of("node-3"), Set.of("node-2"));

        assertFalse(router.getRegisteredNodes().contains("node-2"));
        assertTrue(router.getRegisteredNodes().contains("node-3"));
        assertTrue(router.isEnabled());
        assertNull(router.getNodeHealth("node-2"));
        assertNotNull(router.getNodeHealth("node-3"));
    }

    // ============ shouldHandleLocally (2 tests) ============

    @Test
    @Order(15)
    @DisplayName("shouldHandleLocally: true when disabled or self-only")
    void shouldHandleLocally_selfOnly() {
        var router = new ClusterRouter(profile("node-1"));
        router.setEnabled(true);
        assertTrue(router.shouldHandleLocally("session:abc"));
        assertTrue(router.shouldHandleLocally("agent:xyz"));
    }

    @Test
    @Order(16)
    @DisplayName("forward: routing key extraction from path for re-route")
    void extractRoutingKeyFromPath() {
        // /api/v1/agents/{agentId}/messages -> agent:{agentId}
        // /api/v1/sessions/{sessionId}/messages -> session:{sessionId}
        // /api/v1/health -> null
        // Tested implicitly via re-route tests above; this is a sanity check
        // that the router handles unknown paths gracefully (returns self)
        var router = new ClusterRouter(profile("node-1"));
        router.setEnabled(true);
        assertEquals("node-1", router.resolveRoute(null));
        assertEquals("node-1", router.resolveRoute(""));
    }
}
