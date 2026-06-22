package com.nousresearch.hermes.connector;

import java.util.List;
import java.util.Map;

/**
 * Connector abstraction for external system integration.
 *
 * <p>Connectors provide a uniform interface for Agent tools to interact with
 * external systems (e-commerce platforms, ERPs, logistics carriers, payment gateways).
 * Each connector exposes operations that are automatically registered as Agent tools.</p>
 */
public interface Connector {

    /**
     * Unique connector name (e.g., "taobao", "cainiao", "jushuitan").
     */
    String getName();

    /**
     * Human-readable label.
     */
    String getLabel();

    /**
     * Brief description of what this connector integrates with.
     */
    String getDescription();

    /**
     * Test if the connector is properly configured and reachable.
     */
    boolean testConnection();

    /**
     * Execute an operation with parameters.
     *
     * @param operation the operation name (from {@link #getSupportedOperations()})
     * @param params    operation-specific parameters
     * @return operation result
     */
    Map<String, Object> execute(String operation, Map<String, Object> params);

    /**
     * List supported operations.
     */
    List<ConnectorOperation> getSupportedOperations();

    /**
     * Get connector configuration schema (for UI forms).
     */
    Map<String, Object> getConfigSchema();

    /**
     * Update connector configuration.
     */
    void configure(Map<String, Object> config);

    /**
     * Check if connector is currently healthy.
     */
    boolean isHealthy();

    /**
     * Definition of a connector operation.
     */
    record ConnectorOperation(
        String name,
        String label,
        String description,
        Map<String, Object> parameterSchema,
        Map<String, Object> responseSchema
    ) {}
}
