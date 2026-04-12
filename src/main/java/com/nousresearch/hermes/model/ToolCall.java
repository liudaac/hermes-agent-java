package com.nousresearch.hermes.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a tool call from the LLM.
 */
public class ToolCall {
    
    private String id;
    private String type;
    private Function function;
    
    public ToolCall() {
        this.type = "function";
    }
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public Function getFunction() { return function; }
    public void setFunction(Function function) { this.function = function; }
    
    /**
     * Function call details.
     */
    public static class Function {
        private String name;
        private String arguments;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }
}
