package com.nousresearch.hermes.blueprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of applying a business TeamBlueprintVersion to collaboration foundation objects. */
public class TeamBlueprintCompileResult {
    private String workspaceId;
    private String tenantId;
    private String teamId;
    private Integer version;
    private String teamName;
    private boolean applied;
    private FoundationCapabilityValidationReport validationReport;
    private Instant compiledAt = Instant.now();
    private final List<String> registeredAgents = new ArrayList<>();
    private final List<String> teamMembers = new ArrayList<>();
    private String leadAgentId;
    private final List<String> warnings = new ArrayList<>();

    public String getWorkspaceId() { return workspaceId; }
    public TeamBlueprintCompileResult setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getTenantId() { return tenantId; }
    public TeamBlueprintCompileResult setTenantId(String tenantId) { this.tenantId = tenantId; return this; }
    public String getTeamId() { return teamId; }
    public TeamBlueprintCompileResult setTeamId(String teamId) { this.teamId = teamId; return this; }
    public Integer getVersion() { return version; }
    public TeamBlueprintCompileResult setVersion(Integer version) { this.version = version; return this; }
    public String getTeamName() { return teamName; }
    public TeamBlueprintCompileResult setTeamName(String teamName) { this.teamName = teamName; return this; }
    public boolean isApplied() { return applied; }
    public TeamBlueprintCompileResult setApplied(boolean applied) { this.applied = applied; return this; }
    public FoundationCapabilityValidationReport getValidationReport() { return validationReport; }
    public TeamBlueprintCompileResult setValidationReport(FoundationCapabilityValidationReport validationReport) { this.validationReport = validationReport; return this; }
    public Instant getCompiledAt() { return compiledAt; }
    public List<String> getRegisteredAgents() { return Collections.unmodifiableList(registeredAgents); }
    public List<String> getTeamMembers() { return Collections.unmodifiableList(teamMembers); }
    public String getLeadAgentId() { return leadAgentId; }
    public TeamBlueprintCompileResult setLeadAgentId(String leadAgentId) { this.leadAgentId = leadAgentId; return this; }
    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }

    public TeamBlueprintCompileResult addRegisteredAgent(String agentId) {
        if (agentId != null && !agentId.isBlank()) registeredAgents.add(agentId);
        return this;
    }

    public TeamBlueprintCompileResult addTeamMember(String agentId) {
        if (agentId != null && !agentId.isBlank()) teamMembers.add(agentId);
        return this;
    }

    public TeamBlueprintCompileResult warning(String warning) {
        if (warning != null && !warning.isBlank()) warnings.add(warning);
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", workspaceId);
        map.put("tenantId", tenantId);
        map.put("teamId", teamId);
        map.put("version", version);
        map.put("teamName", teamName);
        map.put("applied", applied);
        map.put("compiledAt", compiledAt.toString());
        map.put("registeredAgents", List.copyOf(registeredAgents));
        map.put("teamMembers", List.copyOf(teamMembers));
        map.put("leadAgentId", leadAgentId);
        map.put("warnings", List.copyOf(warnings));
        map.put("validation", validationReport != null ? validationReport.toMap() : null);
        return map;
    }
}
