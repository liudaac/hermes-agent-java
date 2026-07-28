package com.nousresearch.hermes.cluster;

import com.nousresearch.hermes.common.HermesProfile;
import com.nousresearch.hermes.common.StickyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-layer cluster router for multi-instance Hermes.
 *
 * <p>Resolves which node should handle a request based on sticky routing keys,
 * and forwards requests to remote nodes when needed.</p>
 *
 * <h2>Routing key priority (highest first)</h2>
 * <ol>
 *   <li>{@code sessionId} - from body or query param</li>
 *   <li>{@code agentId} - from path param</li>
 *   <li>{@code workspaceId} - from body or query param</li>
 *   <li>{@code tenantId} - from API Key -> tenant mapping</li>
 *   <li>No key - handle locally (round-robin effect via LB)</li>
 * </ol>
 *
 * <h2>Flow</h2>
 * <pre>
 *   Request -> any node
 *           -> resolveRoute(extract sticky key)
 *           -> target == self? -> handle locally
 *           -> target != self? -> check circuit breaker
 *             -> HEALTHY -> HTTP forward
 *             -> DEAD -> re-route to next candidate
 *           -> forward fails? -> record failure -> maybe mark DEAD -> re-route
 * </pre>
 *
 * <h2>Circuit Breaker (per-node)</h2>
 * <p>Each remote node has a local health state independent of the discovery
 * layer. This provides real-time failure detection between discovery cycles:</p>
 * <pre>
 *   HEALTHY  --[N consecutive forward failures]-->  DEAD
 *   DEAD     --[probe interval elapsed]--------->  HALF_OPEN
 *   HALF_OPEN--[probe success]------------------>  HEALTHY
 *   HALF_OPEN--[probe failure]----------------->  DEAD
 * </pre>
 *
 * <h2>Node registry</h2>
 * <p>Nodes are populated by {@link NodeDiscoveryService} (MySQL heartbeat
 * table by default). Manual registration is also supported for testing.</p>
 */
public class ClusterRouter implements NodeDiscoveryService.NodeChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRouter.class);

    // ── Config ──────────────────────────────────────────────
    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration PROBE_INTERVAL = Duration.ofSeconds(30);
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    // ── Core ────────────────────────────────────────────────
    private final HermesProfile profile;
    private final AtomicReference<StickyRouter> ringHolder;
    private final HttpClient httpClient;
    private final Map<String, String> nodeUrls;  // nodeId -> base URL

    // ── Circuit Breaker ────────────────────────────────────
    private final Map<String, NodeHealth> nodeHealth;

    private volatile boolean enabled;
    private volatile NodeDiscoveryService discoveryService;

    public ClusterRouter(HermesProfile profile) {
        this.profile = profile;
        this.ringHolder = new AtomicReference<>(
            new StickyRouter(List.of(profile.nodeId())));
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        this.nodeUrls = new ConcurrentHashMap<>();
        this.nodeUrls.put(profile.nodeId(), "http://localhost:" + getPort());
        this.nodeHealth = new ConcurrentHashMap<>();
        this.enabled = false;
    }

    // ============ Discovery wiring ============

    /**
     * Wire a {@link NodeDiscoveryService} to automatically maintain
     * the node registry. Call this before {@link #startDiscovery()}.
     */
    public void setDiscoveryService(NodeDiscoveryService service) {
        this.discoveryService = service;
        service.setNodeChangeListener(this);
    }

    /**
     * Start the discovery service (heartbeat + discovery loops).
     */
    public void startDiscovery() {
        if (discoveryService != null) {
            discoveryService.start();
            logger.info("Node discovery started for {}", profile.nodeId());
        }
    }

    /**
     * Stop the discovery service (graceful shutdown).
     */
    public void stopDiscovery() {
        if (discoveryService != null) {
            discoveryService.stop();
        }
    }

    @Override
    public void onNodesChanged(Map<String, String> activeNodes, Set<String> added, Set<String> removed) {
        // Update nodeUrls
        for (String nodeId : added) {
            nodeUrls.put(nodeId, activeNodes.get(nodeId));
            // New node starts as HEALTHY
            nodeHealth.computeIfAbsent(nodeId, k -> new NodeHealth());
            logger.info("Node added to router: {} -> {}", nodeId, activeNodes.get(nodeId));
        }
        for (String nodeId : removed) {
            nodeUrls.remove(nodeId);
            nodeHealth.remove(nodeId);
            logger.info("Node removed from router: {}", nodeId);
        }

        rebuildRing();

        // Update enabled flag based on node count
        if (nodeUrls.size() > 1) {
            this.enabled = true;
        } else {
            this.enabled = false;
        }
    }

    // ============ Manual Node Registry (for testing) ============

    /**
     * Register a peer node in the cluster (manual, for testing).
     *
     * @param nodeId  unique node ID
     * @param baseUrl node base URL (e.g. "http://10.0.1.5:8080")
     */
    public void registerNode(String nodeId, String baseUrl) {
        nodeUrls.put(nodeId, baseUrl);
        nodeHealth.computeIfAbsent(nodeId, k -> new NodeHealth());
        rebuildRing();
        if (nodeUrls.size() > 1) {
            this.enabled = true;
        }
        logger.info("Registered node: {} -> {} (cluster size={})", nodeId, baseUrl, nodeUrls.size());
    }

    /**
     * Remove a node (for testing or manual override).
     */
    public void unregisterNode(String nodeId) {
        nodeUrls.remove(nodeId);
        nodeHealth.remove(nodeId);
        rebuildRing();
        if (nodeUrls.size() <= 1) {
            this.enabled = false;
        }
        logger.info("Unregistered node: {} (cluster size={})", nodeId, nodeUrls.size());
    }

    /**
     * Get all registered node IDs.
     */
    public Set<String> getRegisteredNodes() {
        return Set.copyOf(nodeUrls.keySet());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ============ Route Resolution ============

    /**
     * Resolve which node should handle this request.
     *
     * @param routingKey the extracted sticky key (sessionId/agentId/workspaceId/tenantId)
     * @return target node ID, or self if routing disabled or no key
     */
    public String resolveRoute(String routingKey) {
        if (!enabled || routingKey == null || routingKey.isBlank()) {
            return profile.nodeId();
        }
        String target = ringHolder.get().routeFor(routingKey);
        return target != null ? target : profile.nodeId();
    }

    /**
     * Check if this node is the designated handler for the routing key.
     */
    public boolean shouldHandleLocally(String routingKey) {
        return resolveRoute(routingKey).equals(profile.nodeId());
    }

    /**
     * Resolve the next healthy node for a routing key, skipping DEAD nodes.
     * Used for re-route when the primary target is dead.
     *
     * @param routingKey   the sticky key (may be null for re-route from unknown path)
     * @param excludeNodes nodes to skip (already tried / known dead)
     * @return next healthy node ID, or self if none found
     */
    String resolveNextHealthy(String routingKey, Set<String> excludeNodes) {
        // If routing is disabled, always handle locally
        if (!enabled) {
            return profile.nodeId();
        }

        // If we have a routing key, try the primary target first
        if (routingKey != null && !routingKey.isBlank()) {
            StickyRouter ring = ringHolder.get();
            String primary = ring.routeFor(routingKey);
            if (primary != null && !excludeNodes.contains(primary) && isNodeHealthy(primary)) {
                return primary;
            }
        }

        // Fall back to any healthy node not excluded
        for (String node : nodeUrls.keySet()) {
            if (!excludeNodes.contains(node) && isNodeHealthy(node)) {
                return node;
            }
        }

        // All remote nodes excluded/unhealthy -> handle locally
        return profile.nodeId();
    }

    // ============ Forward with Circuit Breaker ============

    /**
     * Forward an HTTP request to a remote node, with circuit breaker protection.
     *
     * <p>If the target node is DEAD, attempts re-route to the next healthy node.
     * If all remote nodes are dead, throws ClusterRouteException so the caller
     * can decide to handle locally or return an error.</p>
     *
     * @param targetNodeId target node ID
     * @param method       HTTP method
     * @param path         request path (e.g. "/api/v1/agents/agent-1/messages")
     * @param headers      request headers (Authorization, Content-Type, etc.)
     * @param body         request body (may be null for GET)
     * @return HTTP response from target node
     */
    public HttpResponse<String> forward(String targetNodeId, String method, String path,
                                         Map<String, String> headers, String body) {
        return forward(targetNodeId, method, path, headers, body, Set.of());
    }

    /**
     * Forward with re-route support. Used internally for recursive re-routing.
     */
    private HttpResponse<String> forward(String targetNodeId, String method, String path,
                                          Map<String, String> headers, String body,
                                          Set<String> triedNodes) {
        // Check circuit breaker
        NodeHealth health = nodeHealth.get(targetNodeId);
        if (health != null && !health.canAttempt()) {
            logger.debug("Node {} is {} ({} failures) - re-routing",
                targetNodeId, health.state, health.failures.get());
            return reRoute(targetNodeId, method, path, headers, body, triedNodes);
        }

        String baseUrl = nodeUrls.get(targetNodeId);
        if (baseUrl == null) {
            // Node removed from registry (e.g. discovery cycle cleaned it up)
            markFailure(targetNodeId);
            return reRoute(targetNodeId, method, path, headers, body, triedNodes);
        }

        String url = baseUrl + path;
        logger.debug("Forwarding {} {} -> {} (node={})", method, path, url, targetNodeId);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(FORWARD_TIMEOUT);

            headers.forEach(builder::header);
            if (!headers.containsKey("Content-Type") && body != null) {
                builder.header("Content-Type", "application/json");
            }

            if (body != null && !body.isEmpty()) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            // Success - reset circuit breaker
            markSuccess(targetNodeId);

            logger.debug("Forward response: {} ({} bytes) from {}",
                response.statusCode(),
                response.body() != null ? response.body().length() : 0,
                targetNodeId);

            return response;
        } catch (Exception e) {
            logger.error("Forward failed to node {}: {}", targetNodeId, e.getMessage());
            markFailure(targetNodeId);
            return reRoute(targetNodeId, method, path, headers, body, triedNodes);
        }
    }

    /**
     * Re-route to the next healthy node after a failure.
     */
    private HttpResponse<String> reRoute(String failedNode, String method, String path,
                                          Map<String, String> headers, String body,
                                          Set<String> triedNodes) {
        Set<String> exclude = new HashSet<>(triedNodes);
        exclude.add(failedNode);
        exclude.add(profile.nodeId());  // Don't re-route to self via forward

        String next = resolveNextHealthy(extractRoutingKeyFromPath(path), exclude);
        if (next == null || next.equals(profile.nodeId()) || exclude.contains(next)) {
            // No more candidates - caller should handle locally or fail
            throw new ClusterRouteException(
                "All remote nodes exhausted for " + path + " (tried: " + exclude + ")");
        }

        logger.info("Re-routing {} {} from {} to {}", method, path, failedNode, next);
        return forward(next, method, path, headers, body, exclude);
    }

    /**
     * Forward and return the response body as a string.
     */
    public String forwardAndGetBody(String targetNodeId, String method, String path,
                                     Map<String, String> headers, String body) {
        HttpResponse<String> response = forward(targetNodeId, method, path, headers, body);
        return response.body();
    }

    // ============ Circuit Breaker State ============

    private boolean isNodeHealthy(String nodeId) {
        NodeHealth health = nodeHealth.get(nodeId);
        return health == null || health.canAttempt();
    }

    private void markSuccess(String nodeId) {
        NodeHealth health = nodeHealth.get(nodeId);
        if (health != null) {
            health.recordSuccess();
        }
    }

    private void markFailure(String nodeId) {
        NodeHealth health = nodeHealth.computeIfAbsent(nodeId, k -> new NodeHealth());
        int failures = health.recordFailure();
        if (health.state == NodeHealth.State.DEAD) {
            logger.warn("Node {} marked DEAD after {} consecutive failures",
                nodeId, failures);
        }
    }

    /**
     * Get the circuit breaker state for a node (for monitoring/debugging).
     */
    public NodeHealth getNodeHealth(String nodeId) {
        return nodeHealth.get(nodeId);
    }

    /**
     * Get health snapshot of all nodes.
     */
    public Map<String, String> getHealthSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String node : nodeUrls.keySet()) {
            NodeHealth h = nodeHealth.get(node);
            snapshot.put(node, h != null ? h.state.name() : "HEALTHY");
        }
        return snapshot;
    }

    // ============ Sticky Key Extraction ============

    /**
     * Extract the best sticky routing key from a request.
     *
     * <p>Priority: sessionId > agentId > workspaceId > tenantId</p>
     */
    public static String extractRoutingKey(Map<String, String> pathParams,
                                           Map<String, String> queryParams,
                                           String body,
                                           String tenantId) {
        // 1. sessionId (highest priority)
        String sessionId = extractField(queryParams, body, "sessionId");
        if (sessionId != null && !sessionId.isBlank()) {
            return "session:" + sessionId;
        }

        // 2. agentId (from path param)
        String agentId = pathParams != null ? pathParams.get("agentId") : null;
        if (agentId != null && !agentId.isBlank()) {
            return "agent:" + agentId;
        }

        // 3. workspaceId
        String workspaceId = extractField(queryParams, body, "workspaceId");
        if (workspaceId != null && !workspaceId.isBlank()) {
            return "workspace:" + workspaceId;
        }

        // 4. tenantId (lowest priority)
        if (tenantId != null && !tenantId.isBlank()) {
            return "tenant:" + tenantId;
        }

        return null;
    }

    private static String extractField(Map<String, String> queryParams, String body, String field) {
        if (queryParams != null) {
            String v = queryParams.get(field);
            if (v != null && !v.isBlank()) return v;
        }
        if (body != null && !body.isBlank()) {
            return extractJsonField(body, field);
        }
        return null;
    }

    private static String extractJsonField(String json, String field) {
        String quoted = "\"" + field + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            int end = json.indexOf('"', start + 1);
            if (end > start) return json.substring(start + 1, end);
        }
        return null;
    }

    /**
     * Best-effort routing key extraction from a URL path for re-route purposes.
     * This is a heuristic - the full extraction happens in IntegrationGatewayHandler
     * with path params. For re-route, we just need a rough key.
     */
    private static String extractRoutingKeyFromPath(String path) {
        // /api/v1/agents/{agentId}/messages -> agent:{agentId}
        int agentsIdx = path.indexOf("/agents/");
        if (agentsIdx >= 0) {
            int start = agentsIdx + 8;
            int end = path.indexOf('/', start);
            String agentId = end > 0 ? path.substring(start, end) : path.substring(start);
            if (!agentId.isBlank()) return "agent:" + agentId;
        }
        // /api/v1/sessions/{sessionId}/messages -> session:{sessionId}
        int sessionsIdx = path.indexOf("/sessions/");
        if (sessionsIdx >= 0) {
            int start = sessionsIdx + 10;
            int end = path.indexOf('/', start);
            String sessionId = end > 0 ? path.substring(start, end) : path.substring(start);
            if (!sessionId.isBlank()) return "session:" + sessionId;
        }
        return null;
    }

    // ============ Internal ============

    private void rebuildRing() {
        var nodes = new ArrayList<>(nodeUrls.keySet());
        ringHolder.set(new StickyRouter(nodes));
        logger.info("Rebuilt routing ring: {} nodes", nodes.size());
    }

    private int getPort() {
        String port = System.getProperty("server.port",
            System.getenv().getOrDefault("PORT", "8080"));
        try { return Integer.parseInt(port); } catch (NumberFormatException e) { return 8080; }
    }

    // ============ NodeHealth (Circuit Breaker) ============

    /**
     * Per-node circuit breaker state.
     */
    public static class NodeHealth {
        public enum State { HEALTHY, DEAD, HALF_OPEN }

        final AtomicInteger failures = new AtomicInteger(0);
        volatile State state = State.HEALTHY;
        volatile Instant lastProbeTime = Instant.EPOCH;

        /**
         * Can we attempt a forward to this node?
         * - HEALTHY: yes
         * - DEAD: only if probe interval has elapsed (transition to HALF_OPEN)
         * - HALF_OPEN: yes (this is the probe)
         */
        synchronized boolean canAttempt() {
            switch (state) {
                case HEALTHY:
                    return true;
                case DEAD:
                    if (Instant.now().isAfter(lastProbeTime.plus(PROBE_INTERVAL))) {
                        state = State.HALF_OPEN;
                        logger.debug("Node transitioned DEAD -> HALF_OPEN for probe");
                        return true;
                    }
                    return false;
                case HALF_OPEN:
                    return true;
            }
            return true;
        }

        synchronized int recordFailure() {
            int n = failures.incrementAndGet();
            if (state == State.HALF_OPEN) {
                // Probe failed -> back to DEAD
                state = State.DEAD;
                lastProbeTime = Instant.now();
            } else if (n >= FAILURE_THRESHOLD) {
                state = State.DEAD;
                lastProbeTime = Instant.now();
            }
            return n;
        }

        synchronized void recordSuccess() {
            if (state != State.HEALTHY) {
                logger.debug("Node recovered: {} -> HEALTHY", state);
            }
            failures.set(0);
            state = State.HEALTHY;
        }
    }

    // ============ Exception ============

    public static class ClusterRouteException extends RuntimeException {
        public ClusterRouteException(String message) { super(message); }
        public ClusterRouteException(String message, Throwable cause) { super(message, cause); }
    }
}
