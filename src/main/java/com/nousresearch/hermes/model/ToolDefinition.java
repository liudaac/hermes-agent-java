package com.nousresearch.hermes.model;

import java.util.Map;

/**
 * Definition of a tool that can be called by the AI model.
 */
public class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    
    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
    
    /**
     * Get the tool name.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Get the tool description.
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Get the tool parameters schema.
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    /**
     * Create a builder for ToolDefinition.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for ToolDefinition.
     */
    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> parameters;
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }
        
        public ToolDefinition build() {
            return new ToolDefinition(name, description, parameters);
        }
    }
    
    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "', description='" + description + "'}";
    }
}
