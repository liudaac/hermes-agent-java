package com.nousresearch.hermes.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for external system connectors.
 *
 * <p>Manages connector lifecycle, health checks, and exposes operations
 * as Agent-usable tool definitions.</p>
 */
public class ConnectorRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ConnectorRegistry.class);

    private final ConcurrentHashMap<String, Connector> connectors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConnectorHealth> healthStatus = new ConcurrentHashMap<>();

    /**
     * Register a connector.
     */
    public void register(Connector connector) {
        connectors.put(connector.getName(), connector);
        healthStatus.put(connector.getName(), new ConnectorHealth(connector.getName(), true, null, System.currentTimeMillis()));
        logger.info("Registered connector: {} ({})", connector.getName(), connector.getLabel());
    }

    /**
     * Get a connector by name.
     */
    public Optional<Connector> get(String name) {
        return Optional.ofNullable(connectors.get(name));
    }

    /**
     * List all registered connectors.
     */
    public List<Connector> listAll() {
        return new ArrayList<>(connectors.values());
    }

    /**
     * List connectors by category prefix.
     */
    public List<Connector> listByCategory(String category) {
        return connectors.values().stream()
            .filter(c -> c.getName().startsWith(category + ".") || c.getName().equals(category))
            .collect(Collectors.toList());
    }

    /**
     * Execute an operation on a connector.
     */
    public Map<String, Object> execute(String connectorName, String operation, Map<String, Object> params) {
        Connector connector = connectors.get(connectorName);
        if (connector == null) {
            throw new IllegalArgumentException("Connector not found: " + connectorName);
        }
        if (!connector.isHealthy()) {
            throw new IllegalStateException("Connector " + connectorName + " is not healthy");
        }
        return connector.execute(operation, params);
    }

    /**
     * Run health checks on all connectors.
     */
    public void runHealthChecks() {
        for (Connector connector : connectors.values()) {
            try {
                boolean healthy = connector.testConnection();
                healthStatus.put(connector.getName(),
                    new ConnectorHealth(connector.getName(), healthy, null, System.currentTimeMillis()));
            } catch (Exception e) {
                healthStatus.put(connector.getName(),
                    new ConnectorHealth(connector.getName(), false, e.getMessage(), System.currentTimeMillis()));
                logger.warn("Health check failed for connector {}: {}", connector.getName(), e.getMessage());
            }
        }
    }

    /**
     * Get health status summary.
     */
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", connectors.size());
        summary.put("healthy", healthStatus.values().stream().filter(ConnectorHealth::healthy).count());
        summary.put("unhealthy", healthStatus.values().stream().filter(h -> !h.healthy()).count());
        summary.put("details", new ArrayList<>(healthStatus.values()));
        return summary;
    }

    /**
     * Convert connector operations to tool definitions for Agent registration.
     */
    public List<Map<String, Object>> toToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Connector connector : connectors.values()) {
            for (Connector.ConnectorOperation op : connector.getSupportedOperations()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", connector.getName() + "_" + op.name());
                tool.put("connector", connector.getName());
                tool.put("operation", op.name());
                tool.put("description", "[" + connector.getLabel() + "] " + op.description());
                tool.put("parameters", op.parameterSchema());
                tools.add(tool);
            }
        }
        return tools;
    }

    public record ConnectorHealth(String connectorName, boolean healthy, String error, long checkedAt) {}
}
