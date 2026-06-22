package com.nousresearch.hermes.blueprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business-facing versioned team blueprint. */
public class TeamBlueprintRecord {
    private String workspaceId;
    private String teamId;
    private String name;
    private String description;
    private String scenario;
    private String scenarioId;
    private int activeVersion;
    private List<TeamBlueprintVersion> versions = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public TeamBlueprintRecord() {
    }

    /** 获取WorkspaceId。 */
    public String getWorkspaceId() { return workspaceId; }
    public TeamBlueprintRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    /** 获取TeamId。 */
    public String getTeamId() { return teamId; }
    public TeamBlueprintRecord setTeamId(String teamId) { this.teamId = teamId; return this; }
    /** 获取Name。 */
    public String getName() { return name; }
    public TeamBlueprintRecord setName(String name) { this.name = name; return this; }
    /** 获取Description。 */
    public String getDescription() { return description; }
    public TeamBlueprintRecord setDescription(String description) { this.description = description; return this; }
    /** 获取Scenario。 */
    public String getScenario() { return scenario; }
    public TeamBlueprintRecord setScenario(String scenario) { this.scenario = scenario; return this; }
    /** 获取ScenarioId。 */
    public String getScenarioId() { return scenarioId; }
    public TeamBlueprintRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    /** 获取ActiveVersion。 */
    public int getActiveVersion() { return activeVersion; }
    public TeamBlueprintRecord setActiveVersion(int activeVersion) { this.activeVersion = activeVersion; return this; }
    /** 获取Versions。 */
    public List<TeamBlueprintVersion> getVersions() { return versions; }
    public TeamBlueprintRecord setVersions(List<TeamBlueprintVersion> versions) { this.versions = versions != null ? versions : new ArrayList<>(); return this; }
    /** 获取Metadata。 */
    public Map<String, Object> getMetadata() { return metadata; }
    public TeamBlueprintRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    /** 获取CreatedAt。 */
    public Instant getCreatedAt() { return createdAt; }
    public TeamBlueprintRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    /** 获取UpdatedAt。 */
    public Instant getUpdatedAt() { return updatedAt; }
    public TeamBlueprintRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
