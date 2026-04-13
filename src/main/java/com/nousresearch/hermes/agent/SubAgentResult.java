package com.nousresearch.hermes.agent;

/**
 * Result of sub-agent execution.
 */
public class SubAgentResult {
    public String id;
    public String task;
    public String output;
    public boolean success;
    public boolean completed;
    public String error;
    public int iterationsUsed;
    public long durationMs;
    
    public SubAgentResult() {}
    
    public SubAgentResult(String id, String task, String output, boolean success, 
                         boolean completed, String error, int iterationsUsed, long durationMs) {
        this.id = id;
        this.task = task;
        this.output = output;
        this.success = success;
        this.completed = completed;
        this.error = error;
        this.iterationsUsed = iterationsUsed;
        this.durationMs = durationMs;
    }
}