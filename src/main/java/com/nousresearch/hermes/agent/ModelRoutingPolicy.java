package com.nousresearch.hermes.agent;

import java.util.Map;

/**
 * F1: Model routing policy - maps agent roles to model aliases.
 *
 * <p>Allows configuring which model each agent role uses by default.
 * For example:</p>
 * <pre>
 *   planner  -> smart (Claude-3.5-Sonnet)
 *   executor -> fast  (GPT-4o-mini)
 *   reviewer -> smart  (Claude-3.5-Sonnet)
 *   coder    -> fast   (GPT-4o-mini)
 *   analyst  -> cheap  (DeepSeek-Chat)
 * </pre>
 *
 * <p>Configuration in tenant config.yaml:</p>
 * <pre>
 * model_routing:
 *   planner: smart
 *   executor: fast
 *   reviewer: smart
 *   coder: fast
 *   analyst: cheap
 * </pre>
 *
 * <p>If no routing is configured, the tenant's default model is used.</p>
 */
public class ModelRoutingPolicy {

    private final Map<String, String> roleToAlias;

    public ModelRoutingPolicy(Map<String, String> roleToAlias) {
        this.roleToAlias = roleToAlias != null ? Map.copyOf(roleToAlias) : Map.of();
    }

    /**
     * Get the model alias for a role.
     * @return alias name, or null if no routing configured for this role
     */
    public String getAliasForRole(String role) {
        if (role == null) return null;
        return roleToAlias.get(role.toLowerCase());
    }

    /**
     * Check if a role has a model routing configured.
     */
    public boolean hasRouting(String role) {
        return role != null && roleToAlias.containsKey(role.toLowerCase());
    }

    /**
     * Get all configured routings.
     */
    public Map<String, String> getRoutings() {
        return roleToAlias;
    }

    /**
     * Create from tenant config map.
     * Expected format: { "planner": "smart", "executor": "fast", ... }
     */
    @SuppressWarnings("unchecked")
    public static ModelRoutingPolicy fromConfig(Object config) {
        if (config instanceof Map<?, ?> map) {
            Map<String, String> routing = new java.util.LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    routing.put(entry.getKey().toString().toLowerCase(),
                               entry.getValue().toString());
                }
            }
            return new ModelRoutingPolicy(routing);
        }
        return new ModelRoutingPolicy(Map.of());
    }

    /**
     * Default routing policy with common presets.
     */
    public static ModelRoutingPolicy defaults() {
        return new ModelRoutingPolicy(Map.of(
            "planner", "smart",
            "executor", "fast",
            "reviewer", "smart",
            "coder", "fast",
            "analyst", "cheap",
            "default", "fast"
        ));
    }
}
