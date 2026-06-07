package com.nousresearch.hermes.org.distributed;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Service registry for distributed agent discovery across nodes.
 *
 * <p>Enables agents to find each other across the cluster without
 * hard-coded addresses. Agents register their capabilities and
 * health status; consumers discover them by capability or tag.</p>
 */
public class AgentRegistry {

    /** All registered agents keyed by agentId. */
    private final ConcurrentHashMap<String, AgentNode> agents = new ConcurrentHashMap<>();

    /** Capability index: capability name → agent IDs. */
    private final ConcurrentHashMap<String, Set<String>> capabilityIndex = new ConcurrentHashMap<>();

    /** Tag index: tag → agent IDs. */
    private final ConcurrentHashMap<String, Set<String>> tagIndex = new ConcurrentHashMap<>();

    /** Node index: nodeId → agent IDs on that node. */
    private final ConcurrentHashMap<String, Set<String>> nodeIndex = new ConcurrentHashMap<>();

    /** Recent events for debugging. */
    private final ConcurrentLinkedDeque<String> events = new ConcurrentLinkedDeque<>();
    private static final int MAX_EVENTS = 200;

    /** Register or update an agent's presence. */
    public void register(String agentId, String nodeId, String host, int port,
                         Set<String> capabilities, Set<String> tags, int load) {
        AgentNode existing = agents.get(agentId);
        if (existing != null) {
            // Remove old indexes
            deindex(agentId, existing);
        }

        AgentNode node = new AgentNode(agentId, nodeId, host, port, capabilities, tags, load, System.currentTimeMillis());
        agents.put(agentId, node);
        index(agentId, node);
        log("REGISTER", agentId + " @ " + host + ":" + port);
    }

    /** Deregister an agent (graceful shutdown). */
    public void deregister(String agentId) {
        AgentNode node = agents.remove(agentId);
        if (node != null) deindex(agentId, node);
        log("DEREGISTER", agentId);
    }

    /** Heartbeat — update last seen timestamp. */
    public void heartbeat(String agentId, int load) {
        AgentNode node = agents.get(agentId);
        if (node != null) {
            node.lastSeenMs = System.currentTimeMillis();
            node.load = load;
        }
    }

    /** Mark agents unseen for too long as stale. */
    public List<String> cleanupStale(long ttlMs) {
        long cutoff = System.currentTimeMillis() - ttlMs;
        List<String> stale = new ArrayList<>();
        for (var entry : agents.entrySet()) {
            if (entry.getValue().lastSeenMs < cutoff) {
                stale.add(entry.getKey());
            }
        }
        stale.forEach(this::deregister);
        return stale;
    }

    // ---- discovery ----

    /** Find agents by capability. */
    public List<AgentNode> findByCapability(String capability) {
        Set<String> ids = capabilityIndex.getOrDefault(capability, Set.of());
        return ids.stream().map(agents::get).filter(Objects::nonNull).toList();
    }

    /** Find agents by tag. */
    public List<AgentNode> findByTag(String tag) {
        Set<String> ids = tagIndex.getOrDefault(tag, Set.of());
        return ids.stream().map(agents::get).filter(Objects::nonNull).toList();
    }

    /** Find agents on a specific node. */
    public List<AgentNode> findByNode(String nodeId) {
        Set<String> ids = nodeIndex.getOrDefault(nodeId, Set.of());
        return ids.stream().map(agents::get).filter(Objects::nonNull).toList();
    }

    /** Get a specific agent. */
    public Optional<AgentNode> get(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** All registered agents. */
    public Collection<AgentNode> listAll() {
        return List.copyOf(agents.values());
    }

    /** Cluster-wide load distribution summary. */
    public Map<String, Object> getLoadSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Integer> nodeLoad = new LinkedHashMap<>();
        for (AgentNode n : agents.values()) {
            nodeLoad.merge(n.nodeId, n.load, Integer::sum);
        }
        s.put("total_agents", agents.size());
        s.put("total_nodes", nodeIndex.size());
        s.put("node_load", nodeLoad);
        s.put("capabilities", new ArrayList<>(capabilityIndex.keySet()));
        return s;
    }

    /** Recent events. */
    public List<String> getRecentEvents(int n) {
        return events.stream().limit(n).toList();
    }

    /** List available capabilities across the cluster. */
    public Set<String> listCapabilities() {
        return Collections.unmodifiableSet(capabilityIndex.keySet());
    }

    // ---- internal ----

    private void index(String agentId, AgentNode node) {
        for (String cap : node.capabilities) {
            capabilityIndex.computeIfAbsent(cap, k -> ConcurrentHashMap.newKeySet()).add(agentId);
        }
        for (String tag : node.tags) {
            tagIndex.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(agentId);
        }
        nodeIndex.computeIfAbsent(node.nodeId, k -> ConcurrentHashMap.newKeySet()).add(agentId);
    }

    private void deindex(String agentId, AgentNode node) {
        for (String cap : node.capabilities) {
            Set<String> ids = capabilityIndex.get(cap);
            if (ids != null) ids.remove(agentId);
        }
        for (String tag : node.tags) {
            Set<String> ids = tagIndex.get(tag);
            if (ids != null) ids.remove(agentId);
        }
        Set<String> ids = nodeIndex.get(node.nodeId);
        if (ids != null) ids.remove(agentId);
    }

    private void log(String action, String detail) {
        events.addFirst(System.currentTimeMillis() + " [" + action + "] " + detail);
        while (events.size() > MAX_EVENTS) events.pollLast();
    }

    /** A registered agent node. */
    public static class AgentNode {
        public final String agentId;
        public final String nodeId;
        public final String host;
        public final int port;
        public final Set<String> capabilities;
        public final Set<String> tags;
        public volatile int load;
        public volatile long lastSeenMs;

        AgentNode(String agentId, String nodeId, String host, int port,
                  Set<String> capabilities, Set<String> tags, int load, long lastSeenMs) {
            this.agentId = agentId;
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.capabilities = Set.copyOf(capabilities);
            this.tags = Set.copyOf(tags);
            this.load = load;
            this.lastSeenMs = lastSeenMs;
        }

        public String getAddress() { return host + ":" + port; }
        public boolean isHealthy(long ttlMs) {
            return System.currentTimeMillis() - lastSeenMs < ttlMs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", agentId);
            m.put("node", nodeId);
            m.put("address", getAddress());
            m.put("capabilities", capabilities);
            m.put("tags", tags);
            m.put("load", load);
            m.put("healthy", isHealthy(30_000));
            return m;
        }
    }
}
