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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *           -> target != self? -> HTTP forward to target node
 * </pre>
 *
 * <h2>Node registry</h2>
 * <p>Nodes are registered dynamically. In production, use Redis or a shared
 * registry (e.g. Kubernetes API, Consul) to maintain the live node list.
 * For testing, nodes can be registered manually.</p>
 */
public class ClusterRouter {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRouter.class);

    private final HermesProfile profile;
    private final java.util.concurrent.atomic.AtomicReference<StickyRouter> ringHolder;
    private final HttpClient httpClient;
    private final Map<String, String> nodeUrls;  // nodeId -> base URL (e.g. "http://node-2:8080")

    private volatile boolean enabled;

    public ClusterRouter(HermesProfile profile) {
        this.profile = profile;
        this.ringHolder = new java.util.concurrent.atomic.AtomicReference<>(
            new StickyRouter(java.util.List.of(profile.nodeId())));
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.nodeUrls = new ConcurrentHashMap<>();
        this.nodeUrls.put(profile.nodeId(), "http://localhost:" + getPort());
        this.enabled = false;  // disabled by default, enable when cluster has >1 node
    }

    // ============ Node Registry ============

    /**
     * Register a peer node in the cluster.
     *
     * @param nodeId  unique node ID
     * @param baseUrl node base URL (e.g. "http://10.0.1.5:8080")
     */
    public void registerNode(String nodeId, String baseUrl) {
        nodeUrls.put(nodeId, baseUrl);
        rebuildRing();
        if (nodeUrls.size() > 1) {
            this.enabled = true;
        }
        logger.info("Registered node: {} -> {} (cluster size={})", nodeId, baseUrl, nodeUrls.size());
    }

    /**
     * Remove a node (e.g. on scale-down or health check failure).
     */
    public void unregisterNode(String nodeId) {
        nodeUrls.remove(nodeId);
        rebuildRing();
        if (nodeUrls.size() <= 1) {
            this.enabled = false;
        }
        logger.info("Unregistered node: {} (cluster size={})", nodeId, nodeUrls.size());
    }

    /**
     * Get all registered node IDs.
     */
    public java.util.Set<String> getRegisteredNodes() {
        return java.util.Set.copyOf(nodeUrls.keySet());
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

    // ============ Request Forwarding ============

    /**
     * Forward an HTTP request to a remote node.
     *
     * @param targetNodeId target node ID
     * @param method       HTTP method
     * @param path         request path (e.g. "/api/v1/agents/agent-1/messages")
     * @param headers      request headers (Authorization, Content-Type, etc.)
     * @param body         request body (may be null for GET)
     * @return HTTP response from target node
     */
    public HttpResponse<String> forward(String targetNodeId, String method, String path,
                                         java.util.Map<String, String> headers, String body) {
        String baseUrl = nodeUrls.get(targetNodeId);
        if (baseUrl == null) {
            throw new ClusterRouteException("Unknown target node: " + targetNodeId);
        }

        String url = baseUrl + path;
        logger.debug("Forwarding {} {} -> {} (node={})", method, path, url, targetNodeId);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60));

            // Copy headers
            headers.forEach(builder::header);
            if (!headers.containsKey("Content-Type") && body != null) {
                builder.header("Content-Type", "application/json");
            }

            // Set method + body
            if (body != null && !body.isEmpty()) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            logger.debug("Forward response: {} ({} bytes) from {}",
                response.statusCode(),
                response.body() != null ? response.body().length() : 0,
                targetNodeId);

            return response;
        } catch (Exception e) {
            logger.error("Forward failed to node {}: {}", targetNodeId, e.getMessage());
            throw new ClusterRouteException("Forward to " + targetNodeId + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Forward and return the response body as a string.
     * Convenience method for simple cases.
     */
    public String forwardAndGetBody(String targetNodeId, String method, String path,
                                     Map<String, String> headers, String body) {
        HttpResponse<String> response = forward(targetNodeId, method, path, headers, body);
        return response.body();
    }

    // ============ Sticky Key Extraction ============

    /**
     * Extract the best sticky routing key from a request.
     *
     * <p>Priority: sessionId > agentId > workspaceId > tenantId</p>
     *
     * @param pathParams    path parameters (e.g. {agentId: "agent-1"})
     * @param queryParams   query parameters
     * @param body          request body (JSON, may contain sessionId/workspaceId)
     * @param tenantId      tenant ID (from API Key auth)
     * @return routing key, or null if none found
     */
    public static String extractRoutingKey(Map<String, String> pathParams,
                                           Map<String, String> queryParams,
                                           String body,
                                           String tenantId) {
        // 1. sessionId (highest priority - keeps conversation on same node)
        String sessionId = extractField(queryParams, body, "sessionId");
        if (sessionId != null && !sessionId.isBlank()) {
            return "session:" + sessionId;
        }

        // 2. agentId (from path param - keeps agent state on same node)
        String agentId = pathParams != null ? pathParams.get("agentId") : null;
        if (agentId != null && !agentId.isBlank()) {
            return "agent:" + agentId;
        }

        // 3. workspaceId (from query or body)
        String workspaceId = extractField(queryParams, body, "workspaceId");
        if (workspaceId != null && !workspaceId.isBlank()) {
            return "workspace:" + workspaceId;
        }

        // 4. tenantId (lowest priority - distributes tenants across nodes)
        if (tenantId != null && !tenantId.isBlank()) {
            return "tenant:" + tenantId;
        }

        // No key - let LB handle it (round-robin)
        return null;
    }

    /**
     * Extract a field from query params first, then from JSON body.
     */
    private static String extractField(Map<String, String> queryParams, String body, String field) {
        // Query param first
        if (queryParams != null) {
            String v = queryParams.get(field);
            if (v != null && !v.isBlank()) return v;
        }
        // Then body (simple JSON string extraction)
        if (body != null && !body.isBlank()) {
            return extractJsonField(body, field);
        }
        return null;
    }

    /**
     * Minimal JSON field extractor (no dependency on JSON library).
     */
    private static String extractJsonField(String json, String field) {
        String quoted = "\"" + field + "\"";
        int idx = json.indexOf(quoted);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + quoted.length());
        if (colon < 0) return null;
        int start = colon + 1;
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            // String value
            int end = json.indexOf('"', start + 1);
            if (end > start) return json.substring(start + 1, end);
        } else {
            // Non-string value (number, boolean) - not a valid routing key
            return null;
        }
        return null;
    }

    // ============ Internal ============

    private void rebuildRing() {
        var nodes = new java.util.ArrayList<>(nodeUrls.keySet());
        ringHolder.set(new StickyRouter(nodes));
        logger.info("Rebuilt routing ring: {} nodes", nodes.size());
    }

    private int getPort() {
        String port = System.getProperty("server.port",
            System.getenv().getOrDefault("PORT", "8080"));
        try { return Integer.parseInt(port); } catch (NumberFormatException e) { return 8080; }
    }

    // ============ Exception ============

    public static class ClusterRouteException extends RuntimeException {
        public ClusterRouteException(String message) { super(message); }
        public ClusterRouteException(String message, Throwable cause) { super(message, cause); }
    }
}
