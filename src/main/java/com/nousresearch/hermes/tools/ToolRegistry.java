package com.nousresearch.hermes.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Central registry for all available tools.
 * Tools register themselves here and are discovered by the agent.
 */
public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ToolRegistry INSTANCE = new ToolRegistry();
    
    private final Map<String, ToolEntry> tools = new HashMap<>();
    
    private ToolRegistry() {}
    
    public static ToolRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Register a tool.
     */
    public void register(ToolEntry entry) {
        tools.put(entry.getName(), entry);
        logger.debug("Registered tool: {}", entry.getName());
    }
    
    /**
     * Get a tool by name.
     */
    public ToolEntry getTool(String name) {
        return tools.get(name);
    }

    /**
     * Get all registered tools.
     */
    public Map<String, ToolEntry> getAllTools() {
        return new HashMap<>(tools);
    }
    
    /**
     * Get all registered tools.
     */
    public Map<String, ToolEntry> getAllTools() {
        return new HashMap<>(tools);
    }
    
    /**
     * Execute a tool.
     */
    public String execute(String name, Map<String, Object> args) {
        ToolEntry entry = tools.get(name);
        if (entry == null) {
            return toolError("Tool not found: " + name);
        }
        
        try {
            return entry.execute(args);
        } catch (Exception e) {
            logger.error("Tool execution failed: {}", e.getMessage(), e);
            return toolError("Execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Format a successful tool result.
     */
    public static String toolResult(Object result) {
        return "RESULT: " + result.toString();
    }
    
    /**
     * Format an error tool result.
     */
    public static String toolError(String message) {
        return "ERROR: " + message;
    }
}
