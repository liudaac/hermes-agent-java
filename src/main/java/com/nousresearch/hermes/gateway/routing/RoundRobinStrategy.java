package com.nousresearch.hermes.gateway.routing;

import java.util.List;

/**
 * S2-1 #2: Round-robin 策略（无 stickiness，默认策略）。
 *
 * <p>轮询分配请求到健康节点，不保证同一租户到同一节点。
 * 单节点时直接返回本地节点。</p>
 */
public class RoundRobinStrategy implements RoutingStrategy {

    private final ClusterTopology topology;
    private volatile int counter = 0;

    public RoundRobinStrategy(ClusterTopology topology) {
        this.topology = topology;
    }

    @Override
    public synchronized ClusterNode resolve(String tenantId, String sessionId) {
        List<ClusterNode> healthy = topology.getHealthyNodes();
        if (healthy.isEmpty()) {
            return topology.getNode(topology.getLocalNodeId()).orElse(null);
        }
        if (healthy.size() == 1) {
            return healthy.get(0);
        }
        ClusterNode node = healthy.get(counter % healthy.size());
        counter++;
        return node;
    }

    @Override
    public String name() {
        return "round-robin";
    }
}
