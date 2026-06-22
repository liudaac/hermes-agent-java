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
    private List<String> toolApprovalRules = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public AgentBlueprintRecord() {
    }

    /** 获取AgentId。 */
    public String getAgentId() { return agentId; }
    public AgentBlueprintRecord setAgentId(String agentId) { this.agentId = agentId; return this; }
    /** 获取DisplayName。 */
    public String getDisplayName() { return displayName; }
    public AgentBlueprintRecord setDisplayName(String displayName) { this.displayName = displayName; return this; }
    /** 获取Responsibility。 */
    public String getResponsibility() { return responsibility; }
    public AgentBlueprintRecord setResponsibility(String responsibility) { this.responsibility = responsibility; return this; }
    /** 获取KnowledgeRefs。 */
    public List<String> getKnowledgeRefs() { return knowledgeRefs; }
    public AgentBlueprintRecord setKnowledgeRefs(List<String> knowledgeRefs) { this.knowledgeRefs = knowledgeRefs != null ? knowledgeRefs : new ArrayList<>(); return this; }
    /** 获取AllowedTools。 */
    public List<String> getAllowedTools() { return allowedTools; }
    public AgentBlueprintRecord setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools != null ? allowedTools : new ArrayList<>(); return this; }
    /** 获取AllowedSkills。 */
    public List<String> getAllowedSkills() { return allowedSkills; }
    public AgentBlueprintRecord setAllowedSkills(List<String> allowedSkills) { this.allowedSkills = allowedSkills != null ? allowedSkills : new ArrayList<>(); return this; }
    /** 获取ApprovalRules。 */
    public List<String> getApprovalRules() { return approvalRules; }
    public AgentBlueprintRecord setApprovalRules(List<String> approvalRules) { this.approvalRules = approvalRules != null ? approvalRules : new ArrayList<>(); return this; }
    /** 获取ToolApprovalRules。 */
    public List<String> getToolApprovalRules() { return toolApprovalRules; }
    public AgentBlueprintRecord setToolApprovalRules(List<String> toolApprovalRules) { this.toolApprovalRules = toolApprovalRules != null ? toolApprovalRules : new ArrayList<>(); return this; }
    /** 获取Metadata。 */
    public Map<String, Object> getMetadata() { return metadata; }
    public AgentBlueprintRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
}
