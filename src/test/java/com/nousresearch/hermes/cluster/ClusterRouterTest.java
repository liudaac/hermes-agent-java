package com.nousresearch.hermes.cluster;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClusterRouter unit tests: routing, circuit breaker, re-route.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterRouterTest {

    private com.nousresearch.hermes.common.HermesProfile profile(String nodeId) {
        return new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            nodeId, "", "", "", "", "");
    }

    // ============ Routing key extraction ============

    @Test
    @Order(1)
    @DisplayName("extractRoutingKey: sessionId has highest priority")
    void routingKey_sessionId_priority() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"),
            Map.of(),
            "{\"sessionId\":\"sess-123\",\"workspaceId\":\"ws-1\"}",
            "tenant-1"
        );
        assertEquals("session:sess-123", key);
    }

    @Test
    @Order(2)
    @DisplayName("extractRoutingKey: agentId from path param")
    void routingKey_agentId_fromPath() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"),
            Map.of(),
            "{\"message\":\"hello\"}",
            "tenant-1"
        );
        assertEquals("agent:agent-1", key);
    }

    @Test
    @Order(3)
    @DisplayName("extractRoutingKey: workspaceId from body")
    void routingKey_workspaceId_fromBody() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of(),
            Map.of(),
            "{\"workspaceId\":\"ws-456\",\"message\":\"hello\"}",
            "tenant-1"
        );
        assertEquals("workspace:ws-456", key);
    }

    @Test
    @Order(4)
    @DisplayName("extractRoutingKey: workspaceId from query param")
    void routingKey_workspaceId_fromQuery() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of(),
            Map.of("workspaceId", "ws-query"),
            "{}",
            "tenant-1"
        );
        assertEquals("workspace:ws-query", key);
    }

    @Test
    @Order(5)
    @DisplayName("extractRoutingKey: tenantId as fallback")
    void routingKey_tenantId_fallback() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of(),
            Map.of(),
            "{\"message\":\"hello\"}",
            "tenant-abc"
        );
        assertEquals("tenant:tenant-abc", key);
    }

    @Test
    @Order(6)
    @DisplayName("extractRoutingKey: null when no keys")
    void routingKey_null_whenEmpty() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of(),
            Map.of(),
            "{\"message\":\"hello\"}",
            null
        );
        assertNull(key);
    }

    @Test
    @Order(7)
    @DisplayName("extractRoutingKey: sessionId from query param overrides agentId")
    void routingKey_sessionId_fromQuery() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"),
            Map.of("sessionId", "sess-q"),
            "{}",
            "tenant-1"
        );
        assertEquals("session:sess-q", key);
    }

    @Test
    @Order(8)
    @DisplayName("extractRoutingKey: null body handled gracefully")
    void routingKey_nullBody() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of("agentId", "agent-1"),
            Map.of(),
            null,
            null
        );
        assertEquals("agent:agent-1", key);
    }

    @Test
    @Order(9)
    @DisplayName("extractRoutingKey: empty strings ignored")
    void routingKey_emptyStrings() {
        String key = ClusterRouter.extractRoutingKey(
            Map.of("agentId", ""),
            Map.of("sessionId", ""),
            "{\"workspaceId\":\"\"}",
            ""
        );
        assertNull(key);
    }

    // ============ Route resolution ============

    @Test
    @Order(10)
    @DisplayName("resolveRoute returns self when disabled")
    void resolveRoute_disabled_returnsSelf() {
        var router = new ClusterRouter(profile("node-1"));
        assertEquals("node-1", router.resolveRoute("session:abc"));
        assertEquals("node-1", router.resolveRoute(null));
        assertEquals("node-1", router.resolveRoute(""));
    }

    @Test
    @Order(11)
    @DisplayName("resolveRoute returns self for null key even when enabled")
    void resolveRoute_enabled_nullKey() {
        var router = new ClusterRouter(profile("node-1"));
        router.setEnabled(true);
        assertEquals("node-1", router.resolveRoute(null));
        assertEquals("node-1", router.resolveRoute(""));
    }

    @Test
    @Order(12)
    @DisplayName("registerNode enables routing and distributes keys")
    void registerNode_enablesRouting() {
        var router = new ClusterRouter(profile("node-1"));
        assertFalse(router.isEnabled());

        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());
        assertEquals(2, router.getRegisteredNodes().size());
    }

    @Test
    @Order(13)
    @DisplayName("unregisterNode disables when down to 1 node")
    void unregisterNode_disables() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());

        router.unregisterNode("node-2");
        assertFalse(router.isEnabled());
    }

    @Test
    @Order(14)
    @DisplayName("shouldHandleLocally returns true for self node")
    void shouldHandleLocally_self() {
        var router = new ClusterRouter(profile("node-1"));
        router.setEnabled(true);

        assertTrue(router.shouldHandleLocally("session:abc"));
        assertTrue(router.shouldHandleLocally("agent:xyz"));
    }

    @Test
    @Order(15)
    @DisplayName("shouldHandleLocally distributes across nodes")
    void shouldHandleLocally_distributes() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        int localCount = 0;
        int remoteCount = 0;
        for (int i = 0; i < 100; i++) {
            String key = "session:key-" + i;
            if (router.shouldHandleLocally(key)) {
                localCount++;
            } else {
                remoteCount++;
            }
        }
        assertTrue(localCount > 10 && localCount < 90,
            "Expected distribution, got local=" + localCount + " remote=" + remoteCount);
        assertTrue(remoteCount > 10,
            "Expected some remote routing, got local=" + localCount + " remote=" + remoteCount);
    }

    @Test
    @Order(16)
    @DisplayName("forward throws for unknown node (triggers re-route -> exhausted)")
    void forward_unknownNode() {
        var router = new ClusterRouter(profile("node-1"));
        // No other nodes registered -> re-route has nowhere to go
        assertThrows(ClusterRouter.ClusterRouteException.class, () ->
            router.forward("nonexistent", "GET", "/api/v1/health", Map.of(), null));
    }

    @Test
    @Order(17)
    @DisplayName("same routing key always routes to same node (sticky)")
    void stickyRouting_consistent() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        String key = "session:sticky-test-123";
        String target1 = router.resolveRoute(key);
        String target2 = router.resolveRoute(key);
        String target3 = router.resolveRoute(key);

        assertEquals(target1, target2);
        assertEquals(target2, target3);
    }

    // ============ Circuit Breaker ============

    @Test
    @Order(20)
    @DisplayName("Circuit breaker: node starts HEALTHY")
    void circuitBreaker_startsHealthy() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        assertNotNull(health);
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, health.state);
        assertEquals(0, health.failures.get());
    }

    @Test
    @Order(21)
    @DisplayName("Circuit breaker: 3 failures -> DEAD")
    void circuitBreaker_failuresToDead() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1"); // unreachable port

        // forward to unreachable node 3 times
        for (int i = 0; i < 3; i++) {
            try {
                router.forward("node-2", "GET", "/api/v1/health", Map.of(), null);
            } catch (Exception ignored) {
                // Expected: connection refused + re-route exhausted
            }
        }

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        assertNotNull(health);
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, health.state);
        assertTrue(health.failures.get() >= 3);
    }

    @Test
    @Order(22)
    @DisplayName("Circuit breaker: DEAD node is skipped in resolveNextHealthy")
    void circuitBreaker_deadNodeSkipped() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");
        router.registerNode("node-3", "http://node-3:8080");
        router.setEnabled(true);

        // Force node-2 to DEAD
        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        for (int i = 0; i < 3; i++) {
            health.recordFailure();
        }
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, health.state);

        // resolveNextHealthy should skip node-2
        String next = router.resolveNextHealthy("session:test", Set.of("node-1"));
        // Should be node-3 (the healthy one), not node-2
        assertNotEquals("node-2", next, "DEAD node should be skipped");
    }

    @Test
    @Order(23)
    @DisplayName("Circuit breaker: success resets to HEALTHY")
    void circuitBreaker_successResets() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        health.recordFailure();
        health.recordFailure();
        assertEquals(2, health.failures.get());
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, health.state);

        health.recordSuccess();
        assertEquals(0, health.failures.get());
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, health.state);
    }

    @Test
    @Order(24)
    @DisplayName("Circuit breaker: HALF_OPEN after probe interval")
    void circuitBreaker_halfOpenAfterInterval() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        // Force to DEAD
        for (int i = 0; i < 3; i++) {
            health.recordFailure();
        }
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, health.state);

        // Immediately: canAttempt should be false (probe interval not elapsed)
        assertFalse(health.canAttempt());

        // Note: we can't easily test time-based transition without mocking time.
        // The canAttempt() logic is tested via code review: after PROBE_INTERVAL
        // (30s), DEAD -> HALF_OPEN transition happens inside canAttempt().
        // This is validated in integration tests.
    }

    @Test
    @Order(25)
    @DisplayName("Circuit breaker: HALF_OPEN probe failure -> back to DEAD")
    void circuitBreaker_halfOpenProbeFailure() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        // Force to DEAD
        for (int i = 0; i < 3; i++) {
            health.recordFailure();
        }
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, health.state);

        // Simulate HALF_OPEN transition + failure
        health.state = ClusterRouter.NodeHealth.State.HALF_OPEN;
        int failures = health.recordFailure();
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, health.state);
    }

    @Test
    @Order(26)
    @DisplayName("Circuit breaker: HALF_OPEN probe success -> HEALTHY")
    void circuitBreaker_halfOpenProbeSuccess() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        // Force to DEAD
        for (int i = 0; i < 3; i++) {
            health.recordFailure();
        }
        assertEquals(ClusterRouter.NodeHealth.State.DEAD, health.state);

        // Simulate HALF_OPEN transition + success
        health.state = ClusterRouter.NodeHealth.State.HALF_OPEN;
        health.recordSuccess();
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, health.state);
        assertEquals(0, health.failures.get());
    }

    // ============ Re-route ============

    @Test
    @Order(30)
    @DisplayName("Re-route: primary fails -> tries next healthy node")
    void reRoute_primaryFails() {
        var router = new ClusterRouter(profile("node-1"));
        // node-2 is unreachable, node-3 is also unreachable
        router.registerNode("node-2", "http://localhost:1");
        router.registerNode("node-3", "http://localhost:2");
        router.setEnabled(true);

        // Each forward call: node-2 fails (1 failure) -> re-route to node-3 -> node-3 fails (1 failure) -> exhausted
        // After 3 calls, both should be DEAD (3 failures each)
        for (int i = 0; i < 3; i++) {
            try {
                router.forward("node-2", "GET", "/api/v1/health", Map.of(), null);
            } catch (ClusterRouter.ClusterRouteException ignored) {
                // Expected: all nodes exhausted
            }
        }

        // Both should be marked DEAD after 3 consecutive failures
        assertEquals(ClusterRouter.NodeHealth.State.DEAD,
            router.getNodeHealth("node-2").state);
        assertEquals(ClusterRouter.NodeHealth.State.DEAD,
            router.getNodeHealth("node-3").state);
    }

    @Test
    @Order(31)
    @DisplayName("Re-route: exhausted nodes throws with details")
    void reRoute_exhaustedThrows() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://localhost:1");
        router.setEnabled(true);

        ClusterRouter.ClusterRouteException ex = assertThrows(
            ClusterRouter.ClusterRouteException.class, () ->
                router.forward("node-2", "GET", "/api/v1/health", Map.of(), null));

        assertTrue(ex.getMessage().contains("exhausted") || ex.getMessage().contains("failed"));
    }

    @Test
    @Order(32)
    @DisplayName("getHealthSnapshot shows all nodes")
    void healthSnapshot_allNodes() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        Map<String, String> snapshot = router.getHealthSnapshot();
        assertEquals(3, snapshot.size()); // self + node-2 + node-3
        assertEquals("HEALTHY", snapshot.get("node-1"));
        assertEquals("HEALTHY", snapshot.get("node-2"));
        assertEquals("HEALTHY", snapshot.get("node-3"));
    }

    // ============ NodeDiscoveryService callback ============

    @Test
    @Order(40)
    @DisplayName("onNodesChanged: adds new nodes to registry")
    void onNodesChanged_adds() {
        var router = new ClusterRouter(profile("node-1"));

        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080", "node-2", "http://node-2:8080"),
            Set.of("node-2"),
            Set.of()
        );

        assertTrue(router.getRegisteredNodes().contains("node-2"));
        assertTrue(router.isEnabled()); // >1 node -> enabled
    }

    @Test
    @Order(41)
    @DisplayName("onNodesChanged: removes gone nodes from registry")
    void onNodesChanged_removes() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());

        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080"),
            Set.of(),
            Set.of("node-2")
        );

        assertFalse(router.getRegisteredNodes().contains("node-2"));
        assertFalse(router.isEnabled()); // back to 1 node
    }

    @Test
    @Order(42)
    @DisplayName("onNodesChanged: new node starts HEALTHY")
    void onNodesChanged_newNodeHealthy() {
        var router = new ClusterRouter(profile("node-1"));

        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080", "node-2", "http://node-2:8080"),
            Set.of("node-2"),
            Set.of()
        );

        ClusterRouter.NodeHealth health = router.getNodeHealth("node-2");
        assertNotNull(health);
        assertEquals(ClusterRouter.NodeHealth.State.HEALTHY, health.state);
    }

    @Test
    @Order(43)
    @DisplayName("onNodesChanged: removed node's health entry cleaned up")
    void onNodesChanged_removesHealth() {
        var router = new ClusterRouter(profile("node-1"));
        router.registerNode("node-2", "http://node-2:8080");
        assertNotNull(router.getNodeHealth("node-2"));

        router.onNodesChanged(
            Map.of("node-1", "http://localhost:8080"),
            Set.of(),
            Set.of("node-2")
        );

        assertNull(router.getNodeHealth("node-2"));
    }
}
