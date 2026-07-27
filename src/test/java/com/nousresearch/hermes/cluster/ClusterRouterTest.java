package com.nousresearch.hermes.cluster;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClusterRouter unit tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterRouterTest {

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
            Map.of(),  // no agentId in path
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
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        // Disabled by default (only 1 node)
        assertEquals("node-1", router.resolveRoute("session:abc"));
        assertEquals("node-1", router.resolveRoute(null));
        assertEquals("node-1", router.resolveRoute(""));
    }

    @Test
    @Order(11)
    @DisplayName("resolveRoute returns self for null key even when enabled")
    void resolveRoute_enabled_nullKey() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        router.setEnabled(true);
        assertEquals("node-1", router.resolveRoute(null));
        assertEquals("node-1", router.resolveRoute(""));
    }

    @Test
    @Order(12)
    @DisplayName("registerNode enables routing and distributes keys")
    void registerNode_enablesRouting() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        assertFalse(router.isEnabled());

        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());
        assertEquals(2, router.getRegisteredNodes().size());
    }

    @Test
    @Order(13)
    @DisplayName("unregisterNode disables when down to 1 node")
    void unregisterNode_disables() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        router.registerNode("node-2", "http://node-2:8080");
        assertTrue(router.isEnabled());

        router.unregisterNode("node-2");
        assertFalse(router.isEnabled());
    }

    @Test
    @Order(14)
    @DisplayName("shouldHandleLocally returns true for self node")
    void shouldHandleLocally_self() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        router.setEnabled(true);

        // With only node-1, everything routes to self
        assertTrue(router.shouldHandleLocally("session:abc"));
        assertTrue(router.shouldHandleLocally("agent:xyz"));
    }

    @Test
    @Order(15)
    @DisplayName("shouldHandleLocally distributes across nodes")
    void shouldHandleLocally_distributes() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        // Different keys should route to different nodes (probabilistic)
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
        // With 3 nodes, roughly 1/3 should be local
        assertTrue(localCount > 10 && localCount < 90,
            "Expected distribution, got local=" + localCount + " remote=" + remoteCount);
        assertTrue(remoteCount > 10,
            "Expected some remote routing, got local=" + localCount + " remote=" + remoteCount);
    }

    @Test
    @Order(16)
    @DisplayName("forward throws for unknown node")
    void forward_unknownNode() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);

        assertThrows(ClusterRouter.ClusterRouteException.class, () ->
            router.forward("nonexistent", "GET", "/api/v1/health", Map.of(), null));
    }

    @Test
    @Order(17)
    @DisplayName("same routing key always routes to same node (sticky)")
    void stickyRouting_consistent() {
        var profile = new com.nousresearch.hermes.common.HermesProfile(
            com.nousresearch.hermes.common.HermesProfile.Mode.LOCAL,
            "node-1", "", "", "", "", "");
        var router = new ClusterRouter(profile);
        router.registerNode("node-2", "http://node-2:8080");
        router.registerNode("node-3", "http://node-3:8080");

        String key = "session:sticky-test-123";
        String target1 = router.resolveRoute(key);
        String target2 = router.resolveRoute(key);
        String target3 = router.resolveRoute(key);

        assertEquals(target1, target2);
        assertEquals(target2, target3);
    }
}
