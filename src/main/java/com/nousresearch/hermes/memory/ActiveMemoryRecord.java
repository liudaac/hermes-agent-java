package com.nousresearch.hermes.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active Memory entry — a piece of knowledge, rule, or historical case
 * that can be recalled during scenario execution.
 */
public class ActiveMemoryRecord {
    public static final String TYPE_RULE = "rule";
    public static final String TYPE_CASE = "case";
    public static final String TYPE_PREFERENCE = "preference";
    public static final String TYPE_CONTEXT = "context";

    private String workspaceId;
    private String memoryId;
    private String type;
    private String title;
    private String content;
    private List<String> tags = new ArrayList<>();
    private List<String> scenarioIds = new ArrayList<>();
    private List<String> teamIds = new ArrayList<>();
    private int recallCount;
    private Instant lastRecalledAt;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getWorkspaceId() { return workspaceId; }
    public ActiveMemoryRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getMemoryId() { return memoryId; }
    public ActiveMemoryRecord setMemoryId(String memoryId) { this.memoryId = memoryId; return this; }
    public String getType() { return type; }
    public ActiveMemoryRecord setType(String type) { this.type = type; return this; }
    public String getTitle() { return title; }
    public ActiveMemoryRecord setTitle(String title) { this.title = title; return this; }
    public String getContent() { return content; }
    public ActiveMemoryRecord setContent(String content) { this.content = content; return this; }
    public List<String> getTags() { return tags; }
    public ActiveMemoryRecord setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); return this; }
    public List<String> getScenarioIds() { return scenarioIds; }
    public ActiveMemoryRecord setScenarioIds(List<String> scenarioIds) { this.scenarioIds = scenarioIds != null ? scenarioIds : new ArrayList<>(); return this; }
    public List<String> getTeamIds() { return teamIds; }
    public ActiveMemoryRecord setTeamIds(List<String> teamIds) { this.teamIds = teamIds != null ? teamIds : new ArrayList<>(); return this; }
    public int getRecallCount() { return recallCount; }
    public ActiveMemoryRecord setRecallCount(int recallCount) { this.recallCount = recallCount; return this; }
    public Instant getLastRecalledAt() { return lastRecalledAt; }
    public ActiveMemoryRecord setLastRecalledAt(Instant lastRecalledAt) { this.lastRecalledAt = lastRecalledAt; return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public ActiveMemoryRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public ActiveMemoryRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public ActiveMemoryRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
