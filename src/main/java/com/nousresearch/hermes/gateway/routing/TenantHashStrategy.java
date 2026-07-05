package com.nousresearch.hermes.gateway.routing;

import java.util.List;
import java.util.TreeMap;

/**
 * S2-1 #2: 基于一致性哈希的租户 sticky 路由策略。
 *
 * <p>同一租户的请求始终路由到同一节点（除非节点上下线）。
 * 使用一致性哈希环（虚拟节点），节点变更时最小化 key 迁移。</p>
 *
 * <p>哈希输入：tenantId（主）+ sessionId（次，如果提供则进一步细化）。</p>
 */
public class TenantHashStrategy implements RoutingStrategy {

    private static final int VIRTUAL_NODES = 150;
    private final ClusterTopology topology;
    private volatile TreeMap<Integer, ClusterNode> hashRing = new TreeMap<>();

    public TenantHashStrategy(ClusterTopology topology) {
        this.topology = topology;
        rebuildRing();
    }

    /**
     * 重建哈希环（节点变更时调用）。
     */
    public synchronized void rebuildRing() {
        TreeMap<Integer, ClusterNode> newRing = new TreeMap<>();
        for (ClusterNode node : topology.getHealthyNodes()) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                int hash = hash(node.nodeId() + ":" + i);
                newRing.put(hash, node);
            }
        }
        this.hashRing = newRing;
    }

    @Override
    public ClusterNode resolve(String tenantId, String sessionId) {
        List<ClusterNode> healthy = topology.getHealthyNodes();
        if (healthy.isEmpty()) {
            // 无健康节点，返回本地节点
            return topology.getNode(topology.getLocalNodeId())
                .orElse(healthy.isEmpty() ? null : healthy.get(0));
        }
        if (healthy.size() == 1) {
            return healthy.get(0);
        }

        // 一致性哈希：tenantId 为主 key
        String hashKey = sessionId != null && !sessionId.isBlank()
            ? tenantId + ":" + sessionId
            : tenantId;

        int hash = hash(hashKey);
        TreeMap<Integer, ClusterNode> ring = this.hashRing;
        if (ring.isEmpty()) return healthy.get(0);

        // 顺时针找第一个 >= hash 的节点
        var entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    @Override
    public String name() {
        return "tenant-hash";
    }

    /**
     * 简单哈希函数（FNV-1a 变体，分布均匀且稳定）。
     */
    static int hash(String s) {
        if (s == null) return 0;
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return Math.abs(h);
    }
}
