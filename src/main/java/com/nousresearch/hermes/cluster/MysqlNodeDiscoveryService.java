package com.nousresearch.hermes.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * MySQL-based node discovery service for multi-instance Hermes.
 *
 * <p>Uses a {@code cluster_node} table with heartbeat + TTL to maintain
 * the live node registry. No external middleware (Nacos, Consul, Redis pub/sub)
 * required — just MySQL, which is already a hard dependency.</p>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   Startup    -> INSERT ... ON DUPLICATE KEY UPDATE (register self)
 *   Running    -> ScheduledExecutor every 15s: UPDATE last_heartbeat
 *   Discovery  -> ScheduledExecutor every 30s: SELECT active nodes
 *                 diff with local cache -> notify listener on change
 *   Shutdown   -> UPDATE status='LEAVING' (graceful) or just stop heartbeating
 *   Crash      -> heartbeat TTL expires -> next discovery cycle removes node
 * </pre>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code hermes.cluster.heartbeat.interval.seconds} (default 15)</li>
 *   <li>{@code hermes.cluster.discovery.interval.seconds} (default 30)</li>
 *   <li>{@code hermes.cluster.heartbeat.ttl.seconds} (default 60)</li>
 * </ul>
 */
public class MysqlNodeDiscoveryService implements NodeDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(MysqlNodeDiscoveryService.class);

    private final DataSource dataSource;
    private final String selfNodeId;
    private final String selfBaseUrl;

    private final int heartbeatIntervalSeconds;
    private final int discoveryIntervalSeconds;
    private final int heartbeatTtlSeconds;

    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    // Listener notified when the active node set changes
    private volatile NodeChangeListener listener;

    // Last known active nodes (for diff detection)
    private volatile Map<String, String> lastActiveNodes = Map.of();

    public MysqlNodeDiscoveryService(DataSource dataSource, String selfNodeId, String selfBaseUrl) {
        this(dataSource, selfNodeId, selfBaseUrl,
             getIntProp("hermes.cluster.heartbeat.interval.seconds", 15),
             getIntProp("hermes.cluster.discovery.interval.seconds", 30),
             getIntProp("hermes.cluster.heartbeat.ttl.seconds", 60));
    }

    public MysqlNodeDiscoveryService(DataSource dataSource, String selfNodeId, String selfBaseUrl,
                                      int heartbeatIntervalSeconds, int discoveryIntervalSeconds,
                                      int heartbeatTtlSeconds) {
        this.dataSource = dataSource;
        this.selfNodeId = selfNodeId;
        this.selfBaseUrl = selfBaseUrl;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.discoveryIntervalSeconds = discoveryIntervalSeconds;
        this.heartbeatTtlSeconds = heartbeatTtlSeconds;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "hermes-node-discovery");
            t.setDaemon(true);
            return t;
        });
    }

    // ============ Lifecycle ============

    @Override
    public void start() {
        if (running) return;
        running = true;

        // Register self immediately
        registerSelf();

        // Start heartbeat
        scheduler.scheduleAtFixedRate(this::heartbeat,
            heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);

        // Start discovery
        scheduler.scheduleAtFixedRate(this::discover,
            5, discoveryIntervalSeconds, TimeUnit.SECONDS); // 5s initial delay

        logger.info("NodeDiscovery started: node={}, url={}, heartbeat={}s, discovery={}s, ttl={}s",
            selfNodeId, selfBaseUrl, heartbeatIntervalSeconds, discoveryIntervalSeconds, heartbeatTtlSeconds);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdownNow();

        // Mark self as leaving (graceful shutdown)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE cluster_node SET status='LEAVING', last_heartbeat=? WHERE node_id=?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, selfNodeId);
            ps.executeUpdate();
            logger.info("Node {} marked as LEAVING", selfNodeId);
        } catch (SQLException e) {
            logger.warn("Failed to mark node as leaving: {}", e.getMessage());
        }
    }

    @Override
    public void setNodeChangeListener(NodeChangeListener listener) {
        this.listener = listener;
    }

    // ============ Registration ============

    private void registerSelf() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO cluster_node (node_id, base_url, last_heartbeat, status, started_at)
                 VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                 ON DUPLICATE KEY UPDATE
                   base_url = VALUES(base_url),
                   last_heartbeat = VALUES(last_heartbeat),
                   status = 'ACTIVE',
                   started_at = CURRENT_TIMESTAMP
                 """)) {
            ps.setString(1, selfNodeId);
            ps.setString(2, selfBaseUrl);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            logger.info("Registered self in cluster_node: {} -> {}", selfNodeId, selfBaseUrl);
        } catch (SQLException e) {
            logger.error("Failed to register self in cluster_node: {}", e.getMessage());
        }
    }

    private void heartbeat() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE cluster_node SET last_heartbeat=?, status='ACTIVE' WHERE node_id=?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, selfNodeId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                // Node was removed (e.g. by another instance's cleanup) - re-register
                logger.warn("Heartbeat found no row for {} - re-registering", selfNodeId);
                registerSelf();
            }
        } catch (SQLException e) {
            logger.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    // ============ Discovery ============

    private void discover() {
        Map<String, String> current;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT node_id, base_url FROM cluster_node " +
                 "WHERE status='ACTIVE' AND last_heartbeat > ?")) {
            // Active = heartbeat within TTL window
            Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(heartbeatTtlSeconds));
            ps.setTimestamp(1, cutoff);

            Map<String, String> found = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getString("node_id"), rs.getString("base_url"));
                }
            }
            current = Map.copyOf(found);
        } catch (SQLException e) {
            logger.warn("Discovery query failed: {}", e.getMessage());
            return;
        }

        // Diff with last known
        if (!current.equals(lastActiveNodes)) {
            Set<String> added = new HashSet<>(current.keySet());
            added.removeAll(lastActiveNodes.keySet());

            Set<String> removed = new HashSet<>(lastActiveNodes.keySet());
            removed.removeAll(current.keySet());

            logger.info("Node discovery change: +{} -{} (total={})",
                added, removed, current.size());

            lastActiveNodes = current;

            if (listener != null) {
                try {
                    listener.onNodesChanged(current, added, removed);
                } catch (Exception e) {
                    logger.error("NodeChangeListener threw: {}", e.getMessage(), e);
                }
            }
        }
    }

    // ============ Snapshot ============

    @Override
    public Map<String, String> getActiveNodes() {
        return lastActiveNodes;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ============ Schema ============

    /**
     * Create the cluster_node table if it doesn't exist.
     * Call this during application bootstrap before starting the discovery service.
     */
    public static void ensureSchema(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_node (
                    node_id         VARCHAR(64)  NOT NULL,
                    base_url        VARCHAR(256) NOT NULL,
                    last_heartbeat  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
                    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (node_id),
                    INDEX idx_status_heartbeat (status, last_heartbeat)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            logger.info("Ensured cluster_node table exists");
        } catch (SQLException e) {
            logger.error("Failed to create cluster_node table: {}", e.getMessage());
        }
    }

    // ============ Utils ============

    private static int getIntProp(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        val = System.getenv(key.replace(".", "_").toUpperCase());
        if (val != null && !val.isBlank()) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
