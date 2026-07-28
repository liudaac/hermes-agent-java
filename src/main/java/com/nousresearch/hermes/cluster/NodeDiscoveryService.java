package com.nousresearch.hermes.cluster;

import java.util.Map;
import java.util.Set;

/**
 * Node discovery and registry abstraction for multi-instance Hermes.
 *
 * <p>Implementations maintain a live view of cluster nodes and notify
 * a listener when the node set changes. The {@link ClusterRouter} uses
 * this to rebuild its consistent-hash ring.</p>
 *
 * <p>Known implementations:</p>
 * <ul>
 *   <li>{@link MysqlNodeDiscoveryService} - MySQL heartbeat table (no extra middleware)</li>
 * </ul>
 */
public interface NodeDiscoveryService {

    /**
     * Start heartbeat and discovery loops.
     */
    void start();

    /**
     * Stop heartbeat and mark self as leaving.
     */
    void stop();

    /**
     * Register a callback fired when the active node set changes.
     */
    void setNodeChangeListener(NodeChangeListener listener);

    /**
     * Get a snapshot of active nodes: nodeId -> baseUrl.
     */
    Map<String, String> getActiveNodes();

    /**
     * Whether the service is running.
     */
    boolean isRunning();

    /**
     * Callback when nodes are added or removed from the cluster.
     */
    interface NodeChangeListener {
        /**
         * @param activeNodes current full set of active nodes (nodeId -> baseUrl)
         * @param added       node IDs that are new since last notification
         * @param removed     node IDs that disappeared since last notification
         */
        void onNodesChanged(Map<String, String> activeNodes, Set<String> added, Set<String> removed);
    }
}
