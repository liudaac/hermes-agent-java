package com.nousresearch.hermes.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business scenario that connects a business goal to an entry team blueprint. */
public class ScenarioRecord {
    private String workspaceId;
    private String scenarioId;
    private String name;
    private String description;
    private String entryTeamId;
    private String status = "ACTIVE";
    private List<String> successCriteria = new ArrayList<>();
    private List<String> approvalRules = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public ScenarioRecord() {
    }

    public String getWorkspaceId() { return workspaceId; }
    public ScenarioRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getScenarioId() { return scenarioId; }
    public ScenarioRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    public String getName() { return name; }
    public ScenarioRecord setName(String name) { this.name = name; return this; }
    public String getDescription() { return description; }
    public ScenarioRecord setDescription(String description) { this.description = description; return this; }
    public String getEntryTeamId() { return entryTeamId; }
    public ScenarioRecord setEntryTeamId(String entryTeamId) { this.entryTeamId = entryTeamId; return this; }
    public String getStatus() { return status; }
    public ScenarioRecord setStatus(String status) { this.status = status; return this; }
    public List<String> getSuccessCriteria() { return successCriteria; }
    public ScenarioRecord setSuccessCriteria(List<String> successCriteria) { this.successCriteria = successCriteria != null ? successCriteria : new ArrayList<>(); return this; }
    public List<String> getApprovalRules() { return approvalRules; }
    public ScenarioRecord setApprovalRules(List<String> approvalRules) { this.approvalRules = approvalRules != null ? approvalRules : new ArrayList<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public ScenarioRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public ScenarioRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public ScenarioRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
