package com.nousresearch.hermes.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business-facing role card for one digital employee in a team blueprint. */
public class AgentBlueprintRecord {
    private String agentId;
    private String displayName;
    private String responsibility;
    private List<String> knowledgeRefs = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> allowedSkills = new ArrayList<>();
    private List<String> approvalRules = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public AgentBlueprintRecord() {
    }

    public String getAgentId() { return agentId; }
    public AgentBlueprintRecord setAgentId(String agentId) { this.agentId = agentId; return this; }
    public String getDisplayName() { return displayName; }
    public AgentBlueprintRecord setDisplayName(String displayName) { this.displayName = displayName; return this; }
    public String getResponsibility() { return responsibility; }
    public AgentBlueprintRecord setResponsibility(String responsibility) { this.responsibility = responsibility; return this; }
    public List<String> getKnowledgeRefs() { return knowledgeRefs; }
    public AgentBlueprintRecord setKnowledgeRefs(List<String> knowledgeRefs) { this.knowledgeRefs = knowledgeRefs != null ? knowledgeRefs : new ArrayList<>(); return this; }
    public List<String> getAllowedTools() { return allowedTools; }
    public AgentBlueprintRecord setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools != null ? allowedTools : new ArrayList<>(); return this; }
    public List<String> getAllowedSkills() { return allowedSkills; }
    public AgentBlueprintRecord setAllowedSkills(List<String> allowedSkills) { this.allowedSkills = allowedSkills != null ? allowedSkills : new ArrayList<>(); return this; }
    public List<String> getApprovalRules() { return approvalRules; }
    public AgentBlueprintRecord setApprovalRules(List<String> approvalRules) { this.approvalRules = approvalRules != null ? approvalRules : new ArrayList<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public AgentBlueprintRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
}
