package com.nousresearch.hermes.gateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S2-1 #2: 路由解析器 — 根据配置选择策略，判断请求是否应由本地处理。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * routing:
 *   sticky:
 *     strategy: tenant-hash   # 或 round-robin / local
 * }</pre>
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #resolveNode} — 解析目标节点</li>
 *   <li>{@link #shouldHandleLocally} — 判断是否应由本地处理</li>
 *   <li>{@link #getTargetUrl} — 如果非本地，返回目标节点的 URL</li>
 * </ul>
 */
public class RoutingResolver {
    private static final Logger logger = LoggerFactory.getLogger(RoutingResolver.class);

    private final RoutingStrategy strategy;
    private final ClusterTopology topology;

    public RoutingResolver(ClusterTopology topology, RoutingStrategy strategy) {
        this.topology = topology;
        this.strategy = strategy;
        logger.info("Routing strategy: {} (local node: {}, cluster size: {})",
            strategy.name(), topology.getLocalNodeId(), topology.getAllNodes().size());
    }

    /**
     * 根据配置创建路由解析器。
     *
     * @param topology 集群拓扑
     * @param strategyName 策略名称：tenant-hash / round-robin / local
     * @return 路由解析器
     */
    public static RoutingResolver create(ClusterTopology topology, String strategyName) {
        RoutingStrategy strategy = switch (strategyName != null ? strategyName.toLowerCase() : "local") {
            case "tenant-hash" -> new TenantHashStrategy(topology);
            case "round-robin" -> new RoundRobinStrategy(topology);
            default -> new LocalStrategy(topology);
        };
        return new RoutingResolver(topology, strategy);
    }

    /**
     * 解析目标节点。
     */
    public ClusterNode resolveNode(String tenantId, String sessionId) {
        return strategy.resolve(tenantId, sessionId);
    }

    /**
     * 判断请求是否应由本地节点处理。
     */
    public boolean shouldHandleLocally(String tenantId, String sessionId) {
        if (topology.isSingleNode()) return true;

        ClusterNode target = resolveNode(tenantId, sessionId);
        if (target == null) return true; // 无法解析，本地处理

        boolean isLocal = topology.isLocal(target);
        if (!isLocal) {
            logger.debug("Request for tenant={} routed to node={} (local={})",
                tenantId, target.nodeId(), topology.getLocalNodeId());
        }
        return isLocal;
    }

    /**
     * 获取目标节点的 URL（用于 HTTP 转发）。
     * 如果目标是本地节点返回 null。
     */
    public String getTargetUrl(String tenantId, String sessionId) {
        if (topology.isSingleNode()) return null;

        ClusterNode target = resolveNode(tenantId, sessionId);
        if (target == null || topology.isLocal(target)) return null;
        return target.baseUrl();
    }

    /**
     * 获取当前策略名称。
     */
    public String getStrategyName() {
        return strategy.name();
    }

    /**
     * 获取集群拓扑。
     */
    public ClusterTopology getTopology() {
        return topology;
    }

    /**
     * 节点健康变更时通知策略重建（仅 TenantHashStrategy 需要）。
     */
    public void onTopologyChanged() {
        if (strategy instanceof TenantHashStrategy hashStrategy) {
            hashStrategy.rebuildRing();
            logger.info("Hash ring rebuilt after topology change");
        }
    }
}
