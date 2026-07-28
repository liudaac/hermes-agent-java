package com.nousresearch.hermes.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StickyRouter consistent-hash routing and HermesProfile defaults.
 */
class StickyRouterTest {

    @Test
    void sameKeyAlwaysRoutesToSameNode() {
        StickyRouter router = new StickyRouter(List.of("node-1", "node-2", "node-3"));
        String target = router.routeFor("workspace-acme");
        assertNotNull(target);
        // Repeated calls return the same node
        for (int i = 0; i < 100; i++) {
            assertEquals(target, router.routeFor("workspace-acme"));
        }
    }

    @Test
    void differentKeysDistributeAcrossNodes() {
        StickyRouter router = new StickyRouter(List.of("n1", "n2", "n3"));
        Map<String, Long> distribution = java.util.stream.IntStream.range(0, 1000)
            .mapToObj(i -> "ws-" + i)
            .map(key -> router.routeFor(key))
            .collect(Collectors.groupingBy(n -> n, Collectors.counting()));
        // Each node should get at least some keys (not perfectly balanced with 3 nodes
        // but none should be empty with 1000 keys)
        assertEquals(3, distribution.size());
        distribution.values().forEach(count -> assertTrue(count > 50, "Node got too few keys: " + count));
    }

    @Test
    void addingNodeOnlyReassignsFractionOfKeys() {
        StickyRouter router3 = new StickyRouter(List.of("n1", "n2", "n3"));
        // Record routing for 1000 keys
        Map<String, String> routing3 = new java.util.HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String key = "ws-" + i;
            routing3.put(key, router3.routeFor(key));
        }

        // Add a 4th node
        router3.addNode("n4");

        // Count how many keys moved
        long moved = 0;
        for (Map.Entry<String, String> e : routing3.entrySet()) {
            if (!e.getValue().equals(router3.routeFor(e.getKey()))) {
                moved++;
            }
        }

        // With 150 virtual nodes and 4th node added to 3, ~25% should move
        // Allow generous bounds (10-40%) for hash variance
        assertTrue(moved < 400, "Too many keys moved when adding node: " + moved);
        assertTrue(moved > 50, "Too few keys moved when adding node: " + moved);
    }

}
