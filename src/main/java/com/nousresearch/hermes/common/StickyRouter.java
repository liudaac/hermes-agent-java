package com.nousresearch.hermes.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Consistent-hash sticky router for multi-instance Hermes.
 *
 * <p>In cluster mode, requests for the same workspaceId should preferentially
 * route to the same instance to keep the agent + approval state local. This
 * router maps a workspaceId to a node ID using consistent hashing, so adding
 * or removing a node only reassigns ~1/N of the keys.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * StickyRouter router = new StickyRouter(List.of("node-1", "node-2", "node-3"));
 * String targetNode = router.routeFor("workspace-acme");
 * if (targetNode.equals(profile.nodeId())) {
 *     // Handle locally
 * } else {
 *     // Forward to targetNode (via reverse proxy, HTTP redirect, or message bus)
 * }
 * }</pre>
 *
 * <h2>Phase 4b status</h2>
 * <p>Routing decision logic is complete. The actual request forwarding
 * (reverse proxy config, nginx upstream hash, or application-level HTTP
 * forward) is a deployment concern that depends on the load balancer.</p>
 *
 * <p>For nginx, the equivalent config is:</p>
 * <pre>
 * upstream hermes_backend {
 *     hash $arg_workspaceId consistent;
 *     server node-1:8080;
 *     server node-2:8080;
 *     server node-3:8080;
 * }
 * </pre>
 *
 * <p>This class is useful for application-level routing (e.g. Jarvis
 * approval callbacks that need to find the originating node) and for
 * testing routing decisions without a real load balancer.</p>
 */
public class StickyRouter {
    private static final Logger logger = LoggerFactory.getLogger(StickyRouter.class);

    private final SortedMap<Integer, String> ring = new TreeMap<>();
    private final int virtualNodes;

    /**
     * @param nodes        list of node IDs in the cluster
     * @param virtualNodes number of virtual nodes per physical node (default 150)
     */
    public StickyRouter(List<String> nodes, int virtualNodes) {
        this.virtualNodes = virtualNodes;
        for (String node : nodes) {
            for (int i = 0; i < virtualNodes; i++) {
                int hash = hash(node + ":" + i);
                ring.put(hash, node);
            }
        }
        logger.info("StickyRouter initialized: {} nodes, {} virtual nodes, ring size={}",
            nodes.size(), virtualNodes, ring.size());
    }

    /** Default 150 virtual nodes per physical node. */
    public StickyRouter(List<String> nodes) {
        this(nodes, 150);
    }

    /**
     * Route a key to a node using consistent hashing.
     *
     * @param key workspaceId (or any partition key)
     * @return node ID that should handle this key
     */
    public String routeFor(String key) {
        if (ring.isEmpty()) return null;
        int hash = hash(key);
        SortedMap<Integer, String> tail = ring.tailMap(hash);
        int targetHash = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(targetHash);
    }

    /**
     * Check if the current node should handle this key.
     *
     * @param key      workspaceId
     * @param nodeId   current node's ID
     * @return true if this node is the preferred handler
     */
    public boolean isLocal(String key, String nodeId) {
        String target = routeFor(key);
        return nodeId.equals(target);
    }

    /** Add a node to the ring (for dynamic scaling). */
    public void addNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            int hash = hash(node + ":" + i);
            ring.put(hash, node);
        }
        logger.info("Added node {} to ring (ring size now {})", node, ring.size());
    }

    /** Remove a node from the ring (for graceful drain). */
    public void removeNode(String node) {
        ring.entrySet().removeIf(e -> e.getValue().equals(node));
        logger.info("Removed node {} from ring (ring size now {})", node, ring.size());
    }

    /** FNV-1a hash for consistent distribution. */
    private static int hash(String s) {
        int h = 0x811c9dc5;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x01000193;
        }
        return h;
    }
}
