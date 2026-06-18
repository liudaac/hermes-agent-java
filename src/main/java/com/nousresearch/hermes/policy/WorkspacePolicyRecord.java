package com.nousresearch.hermes.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workspace-level skill and tool policy configuration. */
public class WorkspacePolicyRecord {
    private String workspaceId;
    private List<String> allowedSkills = new ArrayList<>();
    private List<String> deniedSkills = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> deniedTools = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getWorkspaceId() { return workspaceId; }
    public WorkspacePolicyRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public List<String> getAllowedSkills() { return allowedSkills; }
    public WorkspacePolicyRecord setAllowedSkills(List<String> allowedSkills) { this.allowedSkills = allowedSkills != null ? allowedSkills : new ArrayList<>(); return this; }
    public List<String> getDeniedSkills() { return deniedSkills; }
    public WorkspacePolicyRecord setDeniedSkills(List<String> deniedSkills) { this.deniedSkills = deniedSkills != null ? deniedSkills : new ArrayList<>(); return this; }
    public List<String> getAllowedTools() { return allowedTools; }
    public WorkspacePolicyRecord setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools != null ? allowedTools : new ArrayList<>(); return this; }
    public List<String> getDeniedTools() { return deniedTools; }
    public WorkspacePolicyRecord setDeniedTools(List<String> deniedTools) { this.deniedTools = deniedTools != null ? deniedTools : new ArrayList<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public WorkspacePolicyRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
}
