package com.nousresearch.hermes.org.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Intelligent router for distributed agent calls.
 *
 * <p>Routes requests to the best available agent node using:
 * <ul>
 *   <li><b>Least-loaded</b> — picks the node with the lowest current load</li>
 *   <li><b>Capability-aware</b> — routes based on required agent capabilities</li>
 *   <li><b>Affinity</b> — pin certain tenants to specific nodes</li>
 *   <li><b>Circuit breaker</b> — temporarily disable failing nodes</li>
 *   <li><b>Fallback</b> — retry on alternate node on failure</li>
 * </ul>
 */
public class AgentRouter {
    private static final Logger logger = LoggerFactory.getLogger(AgentRouter.class);

    public enum Strategy { LEAST_LOADED, ROUND_ROBIN, AFFINITY, RANDOM }

    private final AgentRegistry registry;
    private final Strategy strategy;
    private final Map<String, Integer> roundRobinCounters = new ConcurrentHashMap<>();

    // Circuit breaker state
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private int failureThreshold = 3;
    private long resetTimeoutMs = 30_000;
    private long healthyTtlMs = 60_000;

    // Tenant → node affinity
    private final Map<String, String> tenantAffinity = new ConcurrentHashMap<>();

    public AgentRouter(AgentRegistry registry, Strategy strategy) {
        this.registry = registry;
        this.strategy = strategy;
    }

    /** Route a request to the best matching agent. */
    public Optional<AgentRegistry.AgentNode> route(String requiredCapability, String tenantId) {
        List<AgentRegistry.AgentNode> candidates = registry.findByCapability(requiredCapability);
        if (candidates.isEmpty()) {
            // Try by tag instead
            candidates = registry.findByTag(requiredCapability);
        }
        if (candidates.isEmpty()) return Optional.empty();

        // Filter out unhealthy / circuit-broken nodes
        candidates = candidates.stream()
            .filter(n -> n.isHealthy(healthyTtlMs))
            .filter(n -> !isCircuitOpen(n.agentId))
            .toList();
        if (candidates.isEmpty()) return Optional.empty();

        // Affinity override
        if (tenantId != null && tenantAffinity.containsKey(tenantId)) {
            String preferredNode = tenantAffinity.get(tenantId);
            for (var node : candidates) {
                if (node.nodeId.equals(preferredNode)) return Optional.of(node);
            }
        }

        return Optional.of(selectNode(candidates));
    }

    /** Route to the least loaded capable node matching required tags. */
    public Optional<AgentRegistry.AgentNode> routeByTags(Set<String> requiredTags, int maxLoad) {
        var candidates = new ArrayList<AgentRegistry.AgentNode>();
        for (var node : registry.listAll()) {
            if (node.tags.containsAll(requiredTags)
                && node.isHealthy(healthyTtlMs)
                && !isCircuitOpen(node.agentId)
                && node.load <= maxLoad) {
                candidates.add(node);
            }
        }
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator.comparingInt(n -> n.load));
        return Optional.of(candidates.get(0));
    }

    private AgentRegistry.AgentNode selectNode(List<AgentRegistry.AgentNode> candidates) {
        return switch (strategy) {
            case LEAST_LOADED -> candidates.stream().min(Comparator.comparingInt(n -> n.load)).orElse(candidates.get(0));
            case ROUND_ROBIN -> {
                int idx = roundRobinCounters.merge(candidates.get(0).nodeId, 1, Integer::sum) % candidates.size();
                yield candidates.get(idx);
            }
            case RANDOM -> candidates.get(new Random().nextInt(candidates.size()));
            case AFFINITY -> candidates.get(0);
        };
    }

    /** Pin a tenant to a specific node. */
    public void pinTenant(String tenantId, String nodeId) {
        tenantAffinity.put(tenantId, nodeId);
        logger.info("Pinned tenant {} to node {}", tenantId, nodeId);
    }

    public void unpinTenant(String tenantId) {
        tenantAffinity.remove(tenantId);
    }

    // ---- circuit breaker -------

    public void recordSuccess(String agentId) {
        CircuitBreaker cb = breakers.get(agentId);
        if (cb != null) cb.successCount++;
    }

    public boolean recordFailure(String agentId) {
        CircuitBreaker cb = breakers.computeIfAbsent(agentId, k -> new CircuitBreaker());
        cb.failureCount++;
        cb.lastFailureMs = System.currentTimeMillis();
        if (cb.failureCount >= failureThreshold) {
            cb.open = true;
            cb.openedAtMs = System.currentTimeMillis();
            logger.warn("Circuit breaker OPEN for agent {}", agentId);
        }
        return cb.open;
    }

    public boolean isCircuitOpen(String agentId) {
        CircuitBreaker cb = breakers.get(agentId);
        if (cb == null || !cb.open) return false;
        if (System.currentTimeMillis() - cb.openedAtMs > resetTimeoutMs) {
            cb.open = false;
            cb.failureCount = 0;
            logger.info("Circuit breaker RESET for agent {}", agentId);
            return false;
        }
        return true;
    }

    /** Manually reset circuit breaker. */
    public void resetBreaker(String agentId) {
        breakers.remove(agentId);
        logger.info("Circuit breaker manually reset for {}", agentId);
    }

    /** Route with fallback: try primary, if fails, try next n candidates. */
    public <T> T routeWithFallback(String capability, String tenantId,
                                    Function<AgentRegistry.AgentNode, Optional<T>> fn) {
        List<AgentRegistry.AgentNode> candidates = registry.findByCapability(capability);
        if (candidates.isEmpty()) throw new NoSuchElementException("No agents with capability: " + capability);

        for (var node : candidates) {
            if (!node.isHealthy(healthyTtlMs) || isCircuitOpen(node.agentId)) continue;
            try {
                Optional<T> result = fn.apply(node);
                if (result.isPresent()) {
                    recordSuccess(node.agentId);
                    return result.get();
                }
                recordFailure(node.agentId);
            } catch (Exception e) {
                recordFailure(node.agentId);
                logger.warn("Agent {} failed: {}", node.agentId, e.getMessage());
            }
        }
        throw new RuntimeException("All agents exhausted for capability: " + capability);
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("strategy", strategy.name());
        s.put("affinity_pins", tenantAffinity.size());
        s.put("open_breakers", breakers.values().stream().filter(cb -> cb.open).count());
        s.put("healthy_ttl_ms", healthyTtlMs);
        return s;
    }

    public void setFailureThreshold(int n) { this.failureThreshold = n; }
    public void setResetTimeoutMs(long ms) { this.resetTimeoutMs = ms; }

    private static class CircuitBreaker {
        boolean open;
        int failureCount;
        int successCount;
        long lastFailureMs;
        long openedAtMs;
    }
}