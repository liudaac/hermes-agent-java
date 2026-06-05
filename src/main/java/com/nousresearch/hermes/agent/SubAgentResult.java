package com.nousresearch.hermes.agent;

import java.util.List;
import java.util.ArrayList;

/**
 * Result of sub-agent execution with knowledge extraction.
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
    
    // Shared memory: knowledge extracted from sub-agent's work
    public List<String> insights = new ArrayList<>();
    public List<String> memoriesToSave = new ArrayList<>();
    public String learnedPattern;
    
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
    
    public SubAgentResult(String id, String task, String output, boolean success, 
                         boolean completed, String error, int iterationsUsed, long durationMs,
                         List<String> insights, List<String> memoriesToSave) {
        this(id, task, output, success, completed, error, iterationsUsed, durationMs);
        this.insights = insights != null ? insights : new ArrayList<>();
        this.memoriesToSave = memoriesToSave != null ? memoriesToSave : new ArrayList<>();
    }
}