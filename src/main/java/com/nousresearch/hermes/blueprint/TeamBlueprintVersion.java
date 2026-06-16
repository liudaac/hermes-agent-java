package com.nousresearch.hermes.blueprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable published/draft version snapshot for a team blueprint. */
public class TeamBlueprintVersion {
    private int version;
    private String status = "DRAFT";
    private String changeSummary;
    private String operatingManual;
    private List<String> promptAssetRefs = new ArrayList<>();
    private List<AgentBlueprintRecord> agents = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant activatedAt;

    public TeamBlueprintVersion() {
    }

    public int getVersion() { return version; }
    public TeamBlueprintVersion setVersion(int version) { this.version = version; return this; }
    public String getStatus() { return status; }
    public TeamBlueprintVersion setStatus(String status) { this.status = status; return this; }
    public String getChangeSummary() { return changeSummary; }
    public TeamBlueprintVersion setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; return this; }
    public String getOperatingManual() { return operatingManual; }
    public TeamBlueprintVersion setOperatingManual(String operatingManual) { this.operatingManual = operatingManual; return this; }
    public List<String> getPromptAssetRefs() { return promptAssetRefs; }
    public TeamBlueprintVersion setPromptAssetRefs(List<String> promptAssetRefs) { this.promptAssetRefs = promptAssetRefs != null ? promptAssetRefs : new ArrayList<>(); return this; }
    public List<AgentBlueprintRecord> getAgents() { return agents; }
    public TeamBlueprintVersion setAgents(List<AgentBlueprintRecord> agents) { this.agents = agents != null ? agents : new ArrayList<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public TeamBlueprintVersion setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public TeamBlueprintVersion setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getActivatedAt() { return activatedAt; }
    public TeamBlueprintVersion setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; return this; }
}
