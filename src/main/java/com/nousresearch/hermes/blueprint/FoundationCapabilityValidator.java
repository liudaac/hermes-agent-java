package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates Business Portal design-time artifacts against existing Hermes
 * foundation capabilities.
 *
 * <p>This is the first adapter-first convergence point: Business Portal may
 * describe desired tools, prompts and agent roles, but executable truth remains
 * in tenant, tool, skill, approval and runtime foundations. This validator only
 * reports mismatches; it does not mutate or execute anything.</p>
 */
public class FoundationCapabilityValidator {
    private final WorkspaceService workspaceService;
    private final TenantManager tenantManager;
    private final ToolRegistry toolRegistry;
    private final com.nousresearch.hermes.prompt.FoundationPromptAssetBridge promptAssetService;

    public FoundationCapabilityValidator(WorkspaceService workspaceService, TenantManager tenantManager) {
        this(workspaceService, tenantManager, ToolRegistry.getInstance(), null);
    }

    public FoundationCapabilityValidator(WorkspaceService workspaceService, TenantManager tenantManager, ToolRegistry toolRegistry) {
        this(workspaceService, tenantManager, toolRegistry, null);
    }

    public FoundationCapabilityValidator(WorkspaceService workspaceService, TenantManager tenantManager,
                                         ToolRegistry toolRegistry, com.nousresearch.hermes.prompt.FoundationPromptAssetBridge promptAssetService) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.promptAssetService = promptAssetService;
    }

    public FoundationCapabilityValidationReport validateTeamBlueprint(String workspaceId, TeamBlueprintRecord team) {
        FoundationCapabilityValidationReport report = new FoundationCapabilityValidationReport()
            .setWorkspaceId(workspaceId)
            .setTeamId(team != null ? team.getTeamId() : null);

        WorkspaceRecord workspace;
        try {
            workspace = workspaceService.requireWorkspace(workspaceId);
        } catch (RuntimeException e) {
            return report.error("workspace_missing", "Workspace does not exist", "workspaceId",
                Map.of("workspaceId", workspaceId, "error", e.getMessage()));
        }

        report.setTenantId(workspace.getTenantId());
        TenantContext tenant = tenantManager.getTenant(workspace.getTenantId());
        if (tenant == null) {
            report.error("tenant_missing", "Workspace does not resolve to a loaded or registered tenant", "tenantId",
                Map.of("tenantId", workspace.getTenantId()));
        }

        if (team == null) {
            return report.error("team_blueprint_missing", "Team blueprint is required", "team", Map.of());
        }

        TeamBlueprintVersion active = findActiveVersion(team).orElse(null);
        if (active == null) {
            report.error("active_version_missing", "Team blueprint has no active version to validate", "versions", Map.of(
                "activeVersion", team.getActiveVersion()
            ));
            return report;
        }
        report.setVersion(active.getVersion());
        validateVersion(report, tenant, active);
        return report;
    }

    public FoundationCapabilityValidationReport validateVersion(String workspaceId, String teamId, TeamBlueprintVersion version) {
        FoundationCapabilityValidationReport report = new FoundationCapabilityValidationReport()
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setVersion(version != null ? version.getVersion() : null);

        WorkspaceRecord workspace;
        try {
            workspace = workspaceService.requireWorkspace(workspaceId);
        } catch (RuntimeException e) {
            return report.error("workspace_missing", "Workspace does not exist", "workspaceId",
                Map.of("workspaceId", workspaceId, "error", e.getMessage()));
        }

        report.setTenantId(workspace.getTenantId());
        TenantContext tenant = tenantManager.getTenant(workspace.getTenantId());
        if (tenant == null) {
            report.error("tenant_missing", "Workspace does not resolve to a loaded or registered tenant", "tenantId",
                Map.of("tenantId", workspace.getTenantId()));
        }

        validateVersion(report, tenant, version);
        return report;
    }

    private void validateVersion(FoundationCapabilityValidationReport report, TenantContext tenant, TeamBlueprintVersion version) {
        if (version == null) {
            report.error("team_blueprint_version_missing", "Team blueprint version is required", "version", Map.of());
            return;
        }

        validatePromptAssetRefs(report, version.getPromptAssetRefs());
        validateAgents(report, tenant, version.getAgents());
    }

    private void validatePromptAssetRefs(FoundationCapabilityValidationReport report, List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            report.info("prompt_refs_empty", "No prompt asset refs declared", "promptAssetRefs", Map.of());
            return;
        }
        for (int i = 0; i < refs.size(); i++) {
            String path = "promptAssetRefs[" + i + "]";
            String ref = refs.get(i);
            if (ref == null || ref.isBlank()) {
                report.warning("prompt_ref_blank", "Blank prompt asset ref ignored", path, Map.of());
                continue;
            }
            PromptAssetRef parsed = parsePromptAssetRef(ref);
            if (parsed == null) {
                report.error("prompt_ref_invalid", "Prompt asset ref must use prompt://assetId or prompt://assetId#vN", path,
                    Map.of("ref", ref));
                continue;
            }
            if (promptAssetService != null && !promptAssetService.exists(report.getWorkspaceId(), parsed.assetId(), parsed.version())) {
                report.error("prompt_ref_missing", "Prompt asset ref does not resolve through PromptAssetService", path,
                    Map.of("ref", ref, "assetId", parsed.assetId(), "version", parsed.version()));
            }
        }
    }

    private void validateAgents(FoundationCapabilityValidationReport report, TenantContext tenant, List<AgentBlueprintRecord> agents) {
        if (agents == null || agents.isEmpty()) {
            report.warning("agents_empty", "Team blueprint has no agent role cards", "agents", Map.of());
            return;
        }

        Set<String> seenAgentIds = new HashSet<>();
        for (int i = 0; i < agents.size(); i++) {
            AgentBlueprintRecord agent = agents.get(i);
            String path = "agents[" + i + "]";
            if (agent == null) {
                report.error("agent_missing", "Agent blueprint entry is null", path, Map.of());
                continue;
            }
            validateAgentIdentity(report, agent, path, seenAgentIds);
            validateAllowedTools(report, tenant, agent, path + ".allowedTools");
        }
    }

    private void validateAgentIdentity(FoundationCapabilityValidationReport report, AgentBlueprintRecord agent,
                                       String path, Set<String> seenAgentIds) {
        String agentId = agent.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            report.error("agent_id_missing", "Agent blueprint must declare agentId", path + ".agentId", Map.of());
        } else if (!seenAgentIds.add(agentId)) {
            report.error("agent_id_duplicate", "Agent blueprint declares duplicate agentId", path + ".agentId", Map.of("agentId", agentId));
        }
        if (agent.getResponsibility() == null || agent.getResponsibility().isBlank()) {
            report.warning("agent_responsibility_missing", "Agent blueprint has no responsibility text", path + ".responsibility",
                Map.of("agentId", agentId));
        }
    }

    private void validateAllowedTools(FoundationCapabilityValidationReport report, TenantContext tenant,
                                      AgentBlueprintRecord agent, String path) {
        List<String> tools = agent.getAllowedTools();
        if (tools == null || tools.isEmpty()) {
            report.info("allowed_tools_empty", "Agent declares no allowed tools", path,
                Map.of("agentId", agent.getAgentId()));
            return;
        }

        Set<String> seenTools = new HashSet<>();
        Map<String, ToolEntry> entriesByName = toolRegistry.getAllTools().stream()
            .collect(Collectors.toMap(ToolEntry::getName, entry -> entry, (left, right) -> right, LinkedHashMap::new));

        for (int i = 0; i < tools.size(); i++) {
            String toolName = tools.get(i);
            String toolPath = path + "[" + i + "]";
            if (toolName == null || toolName.isBlank()) {
                report.warning("allowed_tool_blank", "Blank allowed tool ignored", toolPath,
                    Map.of("agentId", agent.getAgentId()));
                continue;
            }
            if (!seenTools.add(toolName)) {
                report.warning("allowed_tool_duplicate", "Duplicate allowed tool declaration", toolPath,
                    Map.of("agentId", agent.getAgentId(), "tool", toolName));
            }

            ToolEntry entry = entriesByName.get(toolName);
            if (entry == null) {
                report.error("requested_tool_unavailable", "Allowed tool is not registered in ToolRegistry", toolPath,
                    Map.of("agentId", agent.getAgentId(), "tool", toolName));
                continue;
            }

            if (!toolRegistry.isToolsetAvailable(entry.getToolset())) {
                report.error("toolset_unavailable", "Allowed tool belongs to an unavailable toolset", toolPath,
                    Map.of("agentId", agent.getAgentId(), "tool", toolName, "toolset", entry.getToolset(),
                        "requiresEnv", entry.getRequiresEnv() != null ? entry.getRequiresEnv() : List.of()));
            }

            if (entry.requiresApproval()) {
                report.warning("tool_requires_approval", "Allowed tool requires foundation approval before execution", toolPath,
                    Map.of("agentId", agent.getAgentId(), "tool", toolName, "risk", entry.getRisk().name(),
                        "approvalType", entry.getApprovalType().name()));
            }

            if (tenant != null) {
                validateTenantToolPolicy(report, tenant, agent, toolName, toolPath);
            }
        }
    }

    private void validateTenantToolPolicy(FoundationCapabilityValidationReport report, TenantContext tenant,
                                          AgentBlueprintRecord agent, String toolName, String toolPath) {
        TenantSecurityPolicy policy = tenant.getSecurityPolicy();
        if (policy == null) {
            report.warning("tenant_policy_missing", "Tenant has no security policy; cannot validate tool permission", toolPath,
                Map.of("agentId", agent.getAgentId(), "tool", toolName, "tenantId", tenant.getTenantId()));
            return;
        }
        if (policy.getDeniedTools().contains(toolName)) {
            report.error("requested_tool_denied_by_tenant_policy", "Allowed tool is denied by tenant security policy", toolPath,
                Map.of("agentId", agent.getAgentId(), "tool", toolName, "tenantId", tenant.getTenantId()));
            return;
        }
        if (!policy.getAllowedTools().isEmpty() && !policy.getAllowedTools().contains(toolName)) {
            report.error("requested_tool_not_allowed_by_tenant_policy", "Allowed tool is not in tenant security policy allow-list", toolPath,
                Map.of("agentId", agent.getAgentId(), "tool", toolName, "tenantId", tenant.getTenantId()));
        }
    }

    private static Optional<TeamBlueprintVersion> findActiveVersion(TeamBlueprintRecord team) {
        if (team.getVersions() == null || team.getVersions().isEmpty()) {
            return Optional.empty();
        }
        Optional<TeamBlueprintVersion> byActiveNumber = team.getVersions().stream()
            .filter(version -> version.getVersion() == team.getActiveVersion())
            .findFirst();
        if (byActiveNumber.isPresent()) {
            return byActiveNumber;
        }
        return team.getVersions().stream()
            .filter(version -> "ACTIVE".equalsIgnoreCase(version.getStatus()))
            .findFirst();
    }

    private static PromptAssetRef parsePromptAssetRef(String ref) {
        String prefix = "prompt://";
        if (ref == null || !ref.startsWith(prefix)) {
            return null;
        }
        String value = ref.substring(prefix.length()).trim();
        if (value.isBlank() || value.contains("/")) {
            return null;
        }
        String assetId = value;
        Integer version = null;
        int versionSeparator = value.indexOf("#v");
        if (versionSeparator >= 0) {
            assetId = value.substring(0, versionSeparator);
            String versionText = value.substring(versionSeparator + 2);
            if (assetId.isBlank() || versionText.isBlank() || versionText.contains("#")) {
                return null;
            }
            try {
                version = Integer.parseInt(versionText);
            } catch (NumberFormatException e) {
                return null;
            }
            if (version <= 0) {
                return null;
            }
        } else if (value.contains("#")) {
            return null;
        }
        return new PromptAssetRef(assetId, version);
    }

    private record PromptAssetRef(String assetId, Integer version) {}

}
