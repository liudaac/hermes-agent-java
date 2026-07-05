package com.nousresearch.hermes.gateway.routing;

import java.util.*;

/**
 * S2-1 #2: 集群节点表示。
 */
public record ClusterNode(String nodeId, String baseUrl, boolean healthy) {

    public ClusterNode withHealthy(boolean healthy) {
        return new ClusterNode(nodeId, baseUrl, healthy);
    }

    @Override
    public String toString() {
        return "ClusterNode{" + nodeId + " @ " + baseUrl + (healthy ? "" : " [unhealthy]") + "}";
    }
}
