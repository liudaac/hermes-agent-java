package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.collaboration.TeamRuntime;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compiles a Business Portal TeamBlueprint into existing collaboration
 * foundation objects.
 *
 * <p>This is an adapter, not a new runtime. It maps design-time records to
 * {@link Team}, {@link AgentRuntimeProfile} and tenant collaboration state owned by
 * {@link TenantContext}. It refuses to apply when foundation validation has
 * errors.</p>
 */
public class TeamBlueprintCompiler {
    private final WorkspaceService workspaceService;
    private final TenantManager tenantManager;
    private final FoundationCapabilityValidator validator;

    public TeamBlueprintCompiler(WorkspaceService workspaceService, TenantManager tenantManager,
                                 FoundationCapabilityValidator validator) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public TeamBlueprintCompileResult compileActiveVersion(String workspaceId, TeamBlueprintRecord teamBlueprint) {
        FoundationCapabilityValidationReport validation = validator.validateTeamBlueprint(workspaceId, teamBlueprint);
        TeamBlueprintCompileResult result = baseResult(workspaceId, teamBlueprint)
            .setTenantId(validation.getTenantId())
            .setVersion(validation.getVersion())
            .setValidationReport(validation);

        if (validation.hasErrors()) {
            return result.warning("Compilation skipped because foundation validation has errors");
        }
        if (teamBlueprint == null) {
            return result.warning("Compilation skipped because team blueprint is missing");
        }

        TeamBlueprintVersion active = findActiveVersion(teamBlueprint).orElse(null);
        if (active == null) {
            return result.warning("Compilation skipped because active team blueprint version is missing");
        }
        return apply(workspaceId, teamBlueprint, active, result);
    }

    public TeamBlueprintCompileResult compileVersion(String workspaceId, TeamBlueprintRecord teamBlueprint, TeamBlueprintVersion version) {
        String teamId = teamBlueprint != null ? teamBlueprint.getTeamId() : null;
        FoundationCapabilityValidationReport validation = validator.validateVersion(workspaceId, teamId, version);
        TeamBlueprintCompileResult result = baseResult(workspaceId, teamBlueprint)
            .setTenantId(validation.getTenantId())
            .setVersion(validation.getVersion())
            .setValidationReport(validation);

        if (validation.hasErrors()) {
            return result.warning("Compilation skipped because foundation validation has errors");
        }
        if (teamBlueprint == null || version == null) {
            return result.warning("Compilation skipped because team blueprint or version is missing");
        }
        return apply(workspaceId, teamBlueprint, version, result);
    }

    private TeamBlueprintCompileResult apply(String workspaceId, TeamBlueprintRecord teamBlueprint,
                                             TeamBlueprintVersion version, TeamBlueprintCompileResult result) {
        WorkspaceRecord workspace = workspaceService.requireWorkspace(workspaceId);
        TenantContext tenant = tenantManager.getTenant(workspace.getTenantId());
        if (tenant == null) {
            throw new IllegalStateException("Workspace tenant is not available: " + workspace.getTenantId());
        }
        tenant.initCollaboration();

        String teamId = requireNonBlank(teamBlueprint.getTeamId(), "teamId");
        String teamName = nonBlank(teamBlueprint.getName(), teamId);
        String mission = compileMission(teamBlueprint, version);
        TeamRuntime team = tenant.getTeamManager().createTeam(teamId, teamName, mission, "business-portal");
        team.putState("business_blueprint_version", version.getVersion());
        team.putState("business_workspace_id", workspaceId);
        team.putState("business_scenario_id", teamBlueprint.getScenarioId());
        team.putState("business_prompt_asset_refs", List.copyOf(version.getPromptAssetRefs()));
        team.putState("business_operating_manual", version.getOperatingManual());

        List<AgentBlueprintRecord> agents = version.getAgents() != null ? version.getAgents() : List.of();
        for (int i = 0; i < agents.size(); i++) {
            AgentBlueprintRecord agent = agents.get(i);
            if (agent == null || agent.getAgentId() == null || agent.getAgentId().isBlank()) {
                result.warning("Skipped blank agent blueprint at index " + i);
                continue;
            }
            AgentRuntimeProfile role = toAgentRole(agent, i, version);
            tenant.registerAgentRole(agent.getAgentId(), role);
            team.addMember(agent.getAgentId());
            result.addRegisteredAgent(agent.getAgentId()).addTeamMember(agent.getAgentId());
        }

        if (!agents.isEmpty()) {
            String lead = chooseLead(agents);
            if (lead != null) {
                team.setLead(lead);
                result.setLeadAgentId(lead);
            }
        }

        tenant.save();
        return result
            .setTenantId(tenant.getTenantId())
            .setTeamId(team.getTeamId())
            .setTeamName(team.getName())
            .setVersion(version.getVersion())
            .setApplied(true);
    }

    private AgentRuntimeProfile toAgentRole(AgentBlueprintRecord agent, int index, TeamBlueprintVersion version) {
        AgentRuntimeProfile.Level level = index == 0 ? AgentRuntimeProfile.Level.LEAD : AgentRuntimeProfile.Level.MID;
        AgentRuntimeProfile role = new AgentRuntimeProfile(
            nonBlank(agent.getDisplayName(), agent.getAgentId()),
            nonBlank(agent.getResponsibility(), "Business blueprint role " + agent.getAgentId()),
            level
        );
        if (agent.getResponsibility() != null && !agent.getResponsibility().isBlank()) {
            role.responsibilities(agent.getResponsibility());
        }
        if (agent.getKnowledgeRefs() != null) {
            for (String ref : agent.getKnowledgeRefs()) {
                if (ref != null && !ref.isBlank()) role.addSkill(ref);
            }
        }
        if (agent.getAllowedTools() != null) {
            for (String tool : agent.getAllowedTools()) {
                if (tool != null && !tool.isBlank()) role.allowedTools(tool);
            }
        }
        if (agent.getApprovalRules() != null && !agent.getApprovalRules().isEmpty()) {
            role.maxAutoRisk(ToolRisk.LOW);
            role.updateMetric("business_approval_rules", List.copyOf(agent.getApprovalRules()));
        }
        role.updateMetric("business_blueprint_version", version.getVersion());
        role.updateMetric("business_blueprint_compiled", true);
        if (agent.getMetadata() != null && !agent.getMetadata().isEmpty()) {
            role.updateMetric("business_agent_metadata", Map.copyOf(agent.getMetadata()));
        }
        return role;
    }

    private TeamBlueprintCompileResult baseResult(String workspaceId, TeamBlueprintRecord teamBlueprint) {
        return new TeamBlueprintCompileResult()
            .setWorkspaceId(workspaceId)
            .setTeamId(teamBlueprint != null ? teamBlueprint.getTeamId() : null)
            .setTeamName(teamBlueprint != null ? teamBlueprint.getName() : null);
    }

    private String compileMission(TeamBlueprintRecord teamBlueprint, TeamBlueprintVersion version) {
        StringBuilder sb = new StringBuilder();
        String description = nonBlank(teamBlueprint.getDescription(), teamBlueprint.getScenario());
        if (description != null && !description.isBlank()) {
            sb.append(description);
        }
        if (version.getOperatingManual() != null && !version.getOperatingManual().isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("Operating manual:\n").append(version.getOperatingManual());
        }
        return sb.toString();
    }

    private static String chooseLead(List<AgentBlueprintRecord> agents) {
        for (AgentBlueprintRecord agent : agents) {
            if (agent != null && agent.getAgentId() != null && !agent.getAgentId().isBlank()) {
                return agent.getAgentId();
            }
        }
        return null;
    }

    private static Optional<TeamBlueprintVersion> findActiveVersion(TeamBlueprintRecord team) {
        if (team == null || team.getVersions() == null || team.getVersions().isEmpty()) {
            return Optional.empty();
        }
        Optional<TeamBlueprintVersion> byActiveNumber = team.getVersions().stream()
            .filter(version -> version.getVersion() == team.getActiveVersion())
            .findFirst();
        if (byActiveNumber.isPresent()) return byActiveNumber;
        return team.getVersions().stream()
            .filter(version -> "ACTIVE".equalsIgnoreCase(version.getStatus()))
            .findFirst();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
