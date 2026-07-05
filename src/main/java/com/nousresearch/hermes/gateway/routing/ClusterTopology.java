package com.nousresearch.hermes.gateway.routing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * S2-1 #2: 集群拓扑 — 管理所有已知节点 + 健康状态。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * cluster:
 *   nodes:
 *     - node-id: "node-1"
 *       base-url: "http://10.0.0.1:8080"
 *     - node-id: "node-2"
 *       base-url: "http://10.0.0.2:8080"
 *     - node-id: "node-3"
 *       base-url: "http://10.0.0.3:8080"
 * }</pre>
 */
public class ClusterTopology {

    private final String localNodeId;
    private final List<ClusterNode> nodes = new CopyOnWriteArrayList<>();
    private final Map<String, ClusterNode> nodeById = new ConcurrentHashMap<>();

    public ClusterTopology(String localNodeId) {
        this.localNodeId = localNodeId;
    }

    /**
     * 添加节点到拓扑。
     */
    public void addNode(String nodeId, String baseUrl) {
        ClusterNode node = new ClusterNode(nodeId, baseUrl, true);
        nodes.add(node);
        nodeById.put(nodeId, node);
    }

    /**
     * 标记节点健康状态。
     */
    public void setNodeHealth(String nodeId, boolean healthy) {
        ClusterNode existing = nodeById.get(nodeId);
        if (existing != null) {
            ClusterNode updated = existing.withHealthy(healthy);
            nodeById.put(nodeId, updated);
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).nodeId().equals(nodeId)) {
                    nodes.set(i, updated);
                    break;
                }
            }
        }
    }

    /**
     * 获取所有健康节点。
     */
    public List<ClusterNode> getHealthyNodes() {
        return nodes.stream().filter(ClusterNode::healthy).toList();
    }

    /**
     * 获取所有节点（含不健康的）。
     */
    public List<ClusterNode> getAllNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * 获取本地节点 ID。
     */
    public String getLocalNodeId() {
        return localNodeId;
    }

    /**
     * 本地节点是否是目标节点。
     */
    public boolean isLocal(ClusterNode node) {
        return node.nodeId().equals(localNodeId);
    }

    /**
     * 按 nodeId 查找。
     */
    public Optional<ClusterNode> getNode(String nodeId) {
        return Optional.ofNullable(nodeById.get(nodeId));
    }

    /**
     * 健康节点数。
     */
    public int healthyCount() {
        return getHealthyNodes().size();
    }

    /**
     * 是否是单节点部署（无其他节点）。
     */
    public boolean isSingleNode() {
        return nodes.size() <= 1;
    }
}
