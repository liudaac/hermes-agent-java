package com.nousresearch.hermes.gateway.routing;

/**
 * S2-1 #2: 本地优先策略（单节点部署默认）。
 *
 * <p>始终返回本地节点。适用于单实例部署或不需要路由的场景。</p>
 */
public class LocalStrategy implements RoutingStrategy {

    private final ClusterTopology topology;

    public LocalStrategy(ClusterTopology topology) {
        this.topology = topology;
    }

    @Override
    public ClusterNode resolve(String tenantId, String sessionId) {
        return topology.getNode(topology.getLocalNodeId()).orElse(null);
    }

    @Override
    public String name() {
        return "local";
    }
}
