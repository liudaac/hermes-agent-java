package com.nousresearch.hermes.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Singleton registry for all Hermes Agent tools.
 * Each tool registers its schema, handler, and metadata.
 */
public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private static ToolRegistry instance;
    
    private final Map<String, ToolEntry> tools = new HashMap<>();
    private final Map<String, Function<Void, Boolean>> toolsetChecks = new HashMap<>();
    
    private ToolRegistry() {}
    
    public static synchronized ToolRegistry getInstance() {
        if (instance == null) {
            instance = new ToolRegistry();
        }
        return instance;
    }
    
    /**
     * Register a tool.
     */
    public void register(ToolEntry entry) {
        String name = entry.getName();
        ToolEntry existing = tools.get(name);
        if (existing != null && !existing.getToolset().equals(entry.getToolset())) {
            logger.warn("Tool name collision: '{}' (toolset '{}') is being overwritten by toolset '{}'",
                name, existing.getToolset(), entry.getToolset());
        }
        tools.put(name, entry);
        logger.debug("Registered tool: {} (toolset: {})", name, entry.getToolset());
    }
    
    /**
     * Register with builder pattern.
     */
    public void register(String name, String toolset, Map<String, Object> schema,
                        Function<Map<String, Object>, String> handler) {
        register(new ToolEntry.Builder()
            .name(name)
            .toolset(toolset)
            .schema(schema)
            .handler(handler)
            .build());
    }
    
    /**
     * Deregister a tool.
     */
    public void deregister(String name) {
        ToolEntry entry = tools.remove(name);
        if (entry != null) {
            // Clean up toolset check if this was the last tool
            String toolset = entry.getToolset();
            boolean hasOtherTools = tools.values().stream()
                .anyMatch(e -> e.getToolset().equals(toolset));
            if (!hasOtherTools) {
                toolsetChecks.remove(toolset);
            }
            logger.debug("Deregistered tool: {}", name);
        }
    }
    
    /**
     * Get OpenAI-format tool definitions.
     */
    public List<Map<String, Object>> getDefinitions(Set<String> toolNames, boolean quiet) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Function<Void, Boolean>, Boolean> checkResults = new HashMap<>();
        
        for (String name : toolNames.stream().sorted().toList()) {
            ToolEntry entry = tools.get(name);
            if (entry == null) continue;
            
            // Check availability
            Function<Void, Boolean> checkFn = toolsetChecks.get(entry.getToolset());
            if (checkFn != null) {
                if (!checkResults.containsKey(checkFn)) {
                    try {
                        checkResults.put(checkFn, checkFn.apply(null));
                    } catch (Exception e) {
                        checkResults.put(checkFn, false);
                        if (!quiet) {
                            logger.debug("Tool {} check raised; skipping", name);
                        }
                    }
                }
                if (!checkResults.get(checkFn)) {
                    if (!quiet) {
                        logger.debug("Tool {} unavailable (check failed)", name);
                    }
                    continue;
                }
            }
            
            // Add schema with name
            Map<String, Object> schemaWithName = new HashMap<>(entry.getSchema());
            schemaWithName.put("name", entry.getName());
            
            Map<String, Object> definition = new HashMap<>();
            definition.put("type", "function");
            definition.put("function", schemaWithName);
            result.add(definition);
        }
        
        return result;
    }
    
    /**
     * Get ToolDefinition objects for specific tools (for ModelClient).
     */
    public List<com.nousresearch.hermes.model.ToolDefinition> getToolDefinitions(Set<String> toolNames) {
        List<com.nousresearch.hermes.model.ToolDefinition> result = new ArrayList<>();
        Map<Function<Void, Boolean>, Boolean> checkResults = new HashMap<>();
        
        for (String name : toolNames.stream().sorted().toList()) {
            ToolEntry entry = tools.get(name);
            if (entry == null) continue;
            
            // Check availability
            Function<Void, Boolean> checkFn = toolsetChecks.get(entry.getToolset());
            if (checkFn != null) {
                if (!checkResults.containsKey(checkFn)) {
                    try {
                        checkResults.put(checkFn, checkFn.apply(null));
                    } catch (Exception e) {
                        checkResults.put(checkFn, false);
                    }
                }
                if (!checkResults.get(checkFn)) {
                    continue;
                }
            }
            
            // Build ToolDefinition
            String description = entry.getDescription();
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) entry.getSchema().get("parameters");
            result.add(new com.nousresearch.hermes.model.ToolDefinition(entry.getName(), description, parameters));
        }
        
        return result;
    }
    
    /**
     * Execute a tool.
     */
    public String dispatch(String name, Map<String, Object> args) {
        ToolEntry entry = tools.get(name);
        if (entry == null) {
            return toolError("Unknown tool: " + name);
        }
        
        try {
            return entry.getHandler().apply(args);
        } catch (Exception e) {
            logger.error("Tool {} dispatch error: {}", name, e.getMessage(), e);
            return toolError("Tool execution failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    
    /**
     * List tools for a tenant (ACP integration).
     */
    public List<Map<String, Object>> listTools(String tenantId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolEntry entry : tools.values()) {
            Map<String, Object> tool = new HashMap<>();
            tool.put("name", entry.getName());
            tool.put("toolset", entry.getToolset());
            tool.put("description", entry.getDescription());
            tool.put("schema", entry.getSchema());
            result.add(tool);
        }
        return result;
    }

    /**
     * Call a tool with tenant context (ACP integration).
     *
     * @deprecated This bypasses tenant permission checks, quota tracking, approval gates,
     * and audit logging. Prefer {@link TenantAwareToolDispatcher#dispatch(String, Map)}
     * which goes through the full tenant-isolated execution chain.
     * Only retained for backwards compatibility; ACP now routes through TenantAwareToolDispatcher.
     */
    @Deprecated
    public Object callTool(String name, Map<String, Object> params,
                           com.nousresearch.hermes.tenant.core.TenantContext tenantContext) {
        ToolEntry entry = tools.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        try {
            String result = entry.getHandler().apply(params);
            // Try to parse as JSON object, fallback to raw string
            try {
                return mapper.readValue(result, Object.class);
            } catch (Exception e) {
                return result;
            }
        } catch (Exception e) {
            logger.error("Tool {} call error: {}", name, e.getMessage(), e);
            throw new RuntimeException("Tool execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get all registered tool names.
     */
    public List<String> getAllToolNames() {
        return tools.keySet().stream().sorted().toList();
    }
    
    /**
     * Get all registered tool entries.
     */
    public List<ToolEntry> getAllTools() {
        return tools.values().stream().toList();
    }
    
    /**
     * Get tool schema.
     */
    public Map<String, Object> getSchema(String name) {
        ToolEntry entry = tools.get(name);
        return entry != null ? entry.getSchema() : null;
    }
    
    /**
     * Get toolset for a tool.
     */
    public String getToolsetForTool(String name) {
        ToolEntry entry = tools.get(name);
        return entry != null ? entry.getToolset() : null;
    }
    
    /**
     * Get emoji for a tool.
     */
    public String getEmoji(String name, String defaultEmoji) {
        ToolEntry entry = tools.get(name);
        return (entry != null && entry.getEmoji() != null) ? entry.getEmoji() : defaultEmoji;
    }
    
    /**
     * Get tool to toolset mapping.
     */
    public Map<String, String> getToolToToolsetMap() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ToolEntry> e : tools.entrySet()) {
            result.put(e.getKey(), e.getValue().getToolset());
        }
        return result;
    }
    
    /**
     * Check if a toolset is available.
     */
    public boolean isToolsetAvailable(String toolset) {
        Function<Void, Boolean> check = toolsetChecks.get(toolset);
        if (check == null) return true;
        try {
            return check.apply(null);
        } catch (Exception e) {
            logger.debug("Toolset {} check raised; marking unavailable", toolset);
            return false;
        }
    }
    
    /**
     * Get available toolsets.
     */
    public Map<String, ToolsetInfo> getAvailableToolsets() {
        Map<String, ToolsetInfo> result = new HashMap<>();
        
        for (ToolEntry entry : tools.values()) {
            String ts = entry.getToolset();
            ToolsetInfo info = result.computeIfAbsent(ts, k -> new ToolsetInfo());
            info.tools.add(entry.getName());
            if (entry.getRequiresEnv() != null) {
                for (String env : entry.getRequiresEnv()) {
                    if (!info.requirements.contains(env)) {
                        info.requirements.add(env);
                    }
                }
            }
        }
        
        // Set availability
        for (Map.Entry<String, ToolsetInfo> e : result.entrySet()) {
            e.getValue().available = isToolsetAvailable(e.getKey());
        }
        
        return result;
    }
    
    // Helper methods for tool responses
    public static String toolError(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }
    
    public static String toolError(String message, Map<String, Object> extra) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("error", message);
            result.putAll(extra);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }
    
    public static String toolResult(Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"error\":\"Failed to serialize result\"}";
        }
    }
    
    public static String toolResult(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"error\":\"Failed to serialize result\"}";
        }
    }
    
    /**
     * Toolset information.
     */
    public static class ToolsetInfo {
        public boolean available;
        public List<String> tools = new ArrayList<>();
        public List<String> requirements = new ArrayList<>();
        public String description = "";
    }
}