package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.policy.PolicyService;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges TeamBlueprint (business configuration) to running Agent instances.
 *
 * <p>When a team blueprint is activated, this runtime component spins up
 * a {@link TenantAwareAIAgent} for each agent blueprint entry, wires up
 * the matching {@link AgentRuntimeProfile}, and registers each one on the TenantBus
 * so that the ScenarioOrchestrator can discover and delegate to them.</p>
 *
 * <p>Agent instances are cached per (workspaceId, teamId, version). When a
 * new version is activated, the old agents are unregistered before the new
 * ones take over, so the orchestrator never routes to stale team members.</p>
 */
public class TeamBlueprintRuntime {

    private static final Logger logger = LoggerFactory.getLogger(TeamBlueprintRuntime.class);

    private final WorkspaceService workspaceService;
    private final TeamBlueprintService blueprintService;
    private final HermesConfig baseConfig;
    private PolicyService policyService;
    private com.nousresearch.hermes.business.approval.ToolApprovalCoordinator toolApprovalCoordinator;
    private com.nousresearch.hermes.canary.CanaryReleaseService canaryReleaseService;

    /** Cache of active agent instances per team: workspaceId -> teamId -> agentId -> agent */
    private final Map<String, Map<String, Map<String, TenantAwareAIAgent>>> activeAgents = new ConcurrentHashMap<>();

    public TeamBlueprintRuntime(WorkspaceService workspaceService, TeamBlueprintService blueprintService) {
        this(workspaceService, blueprintService, new HermesConfig());
    }

    public TeamBlueprintRuntime(WorkspaceService workspaceService, TeamBlueprintService blueprintService, HermesConfig baseConfig) {
        this.workspaceService = workspaceService;
        this.blueprintService = blueprintService;
        this.baseConfig = baseConfig;
    }

    /** Wire in PolicyService for workspace+agent-level tool/skill policy enforcement. Optional. */
    public void setPolicyService(PolicyService policyService) {
        this.policyService = policyService;
    }

    /** Wire in ToolApprovalCoordinator for tool-level approval. Optional. */
    public void setToolApprovalCoordinator(com.nousresearch.hermes.business.approval.ToolApprovalCoordinator coordinator) {
        this.toolApprovalCoordinator = coordinator;
    }

    /** Wire in CanaryReleaseService for traffic-based version selection. Optional. */
    public void setCanaryReleaseService(com.nousresearch.hermes.canary.CanaryReleaseService canaryReleaseService) {
        this.canaryReleaseService = canaryReleaseService;
    }

    /**
     * Resolve which version to use for a request. Considers active canary release
     * (deterministic hash-based routing). Falls back to active version.
     */
    public int resolveVersionForRequest(String workspaceId, String teamId, String requestKey) {
        if (canaryReleaseService != null) {
            try {
                return canaryReleaseService.resolveVersion(workspaceId, teamId,
                    requestKey != null ? requestKey : java.util.UUID.randomUUID().toString());
            } catch (Exception e) {
                logger.debug("Canary version resolve failed for {}/{}: {}", workspaceId, teamId, e.getMessage());
            }
        }
        var team = blueprintService.requireTeamBlueprint(workspaceId, teamId);
        return team.getActiveVersion();
    }

    /**
     * Ensure runtime for a specific version (used by canary routing).
     * Different from ensureTeamRuntime which only sets up the active version.
     */
    public void ensureTeamRuntimeForVersion(String workspaceId, String teamId, int version) {
        var blueprint = blueprintService.requireTeamBlueprint(workspaceId, teamId);
        var versionRecord = blueprint.getVersions().stream()
            .filter(v -> v.getVersion() == version)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Version " + version + " not found for team " + workspaceId + "/" + teamId));

        TenantContext tenantCtx = workspaceService.resolveTenantContext(workspaceId);
        if (tenantCtx == null) {
            throw new IllegalStateException("Tenant context not available for workspace: " + workspaceId);
        }

        // Use a versioned key for canary agents: "<teamId>::v<version>"
        String versionedKey = teamId + "::v" + version;
        var teamAgents = activeAgents
            .computeIfAbsent(workspaceId, k -> new ConcurrentHashMap<>())
            .get(versionedKey);

        if (teamAgents != null && !teamAgents.isEmpty()) {
            boolean allPresent = versionRecord.getAgents().stream()
                .allMatch(a -> teamAgents.containsKey(a.getAgentId()));
            if (allPresent) {
                logger.debug("Versioned runtime already active for {}/{} v{}", workspaceId, teamId, version);
                return;
            }
        }

        logger.info("Spinning up versioned runtime for {}/{} v{} ({} agents)",
            workspaceId, teamId, version, versionRecord.getAgents().size());

        Map<String, TenantAwareAIAgent> newAgents = new ConcurrentHashMap<>();
        for (AgentBlueprintRecord agentBlueprint : versionRecord.getAgents()) {
            try {
                TenantAwareAIAgent agent = createAgentFromBlueprint(tenantCtx, workspaceId, teamId, agentBlueprint);
                newAgents.put(agentBlueprint.getAgentId(), agent);
            } catch (Exception e) {
                logger.error("Failed to create canary agent '{}' in team {}/{} v{}: {}",
                    agentBlueprint.getAgentId(), workspaceId, teamId, version, e.getMessage());
            }
        }

        activeAgents.get(workspaceId).put(versionedKey, newAgents);
    }

    /**
     * Ensure the active version of the given team blueprint has running agent instances
     * registered on the tenant bus. Safe to call multiple times — idempotent.
     */
    public void ensureTeamRuntime(String workspaceId, String teamId) {
        var blueprint = blueprintService.requireTeamBlueprint(workspaceId, teamId);
        int activeVersion = blueprint.getActiveVersion();
        var version = blueprint.getVersions().stream()
            .filter(v -> v.getVersion() == activeVersion)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Active version " + activeVersion + " not found for team " + workspaceId + "/" + teamId));

        var workspace = workspaceService.requireWorkspace(workspaceId);
        TenantContext tenantCtx = workspaceService.resolveTenantContext(workspaceId);
        if (tenantCtx == null) {
            throw new IllegalStateException("Tenant context not available for workspace: " + workspaceId);
        }

        // If this team's agents are already running for this version, do nothing
        var teamAgents = activeAgents
            .computeIfAbsent(workspaceId, k -> new ConcurrentHashMap<>())
            .get(teamId);
        if (teamAgents != null && !teamAgents.isEmpty()) {
            // Quick check: are all expected agentIds present?
            boolean allPresent = version.getAgents().stream()
                .allMatch(a -> teamAgents.containsKey(a.getAgentId()));
            if (allPresent) {
                logger.debug("Team runtime already active for {}/{} v{}", workspaceId, teamId, activeVersion);
                return;
            }
        }

        logger.info("Spinning up team runtime for {}/{} v{} ({} agents)",
            workspaceId, teamId, activeVersion, version.getAgents().size());

        // Tear down previous version's agents if any
        teardownTeam(workspaceId, teamId);

        Map<String, TenantAwareAIAgent> newAgents = new ConcurrentHashMap<>();
        for (AgentBlueprintRecord agentBlueprint : version.getAgents()) {
            try {
                TenantAwareAIAgent agent = createAgentFromBlueprint(tenantCtx, workspaceId, teamId, agentBlueprint);
                newAgents.put(agentBlueprint.getAgentId(), agent);
                logger.info("  ✓ Agent '{}' ({}) registered on bus",
                    agentBlueprint.getAgentId(), agentBlueprint.getDisplayName());
            } catch (Exception e) {
                logger.error("  ✗ Failed to create agent '{}' in team {}/{}: {}",
                    agentBlueprint.getAgentId(), workspaceId, teamId, e.getMessage());
            }
        }

        activeAgents.get(workspaceId).put(teamId, newAgents);
        logger.info("Team runtime ready for {}/{} v{}: {} agents active",
            workspaceId, teamId, activeVersion, newAgents.size());
    }

    /**
     * Unregister and tear down a team's running agents.
     */
    public void teardownTeam(String workspaceId, String teamId) {
        var workspaceMap = activeAgents.get(workspaceId);
        if (workspaceMap == null) return;
        var agents = workspaceMap.remove(teamId);
        if (agents == null) return;

        TenantContext tenantCtx = workspaceService.resolveTenantContext(workspaceId);
        for (var entry : agents.entrySet()) {
            String agentId = entry.getKey();
            try {
                if (tenantCtx != null && tenantCtx.getTenantBus() != null) {
                    tenantCtx.getTenantBus().unregister(agentId);
                }
                logger.debug("Unregistered agent '{}' from team {}/{}", agentId, workspaceId, teamId);
            } catch (Exception e) {
                logger.warn("Failed to unregister agent '{}': {}", agentId, e.getMessage());
            }
        }
    }

    /**
     * Create a single agent instance from a blueprint record.
     * Wires the AgentRuntimeProfile, registers on the bus.
     */
    private TenantAwareAIAgent createAgentFromBlueprint(TenantContext tenantCtx, String workspaceId, String teamId, AgentBlueprintRecord blueprint) {
        String agentId = blueprint.getAgentId();

        // Build the AgentRuntimeProfile from blueprint
        AgentRuntimeProfile role = new AgentRuntimeProfile(
            blueprint.getDisplayName() != null && !blueprint.getDisplayName().isBlank()
                ? blueprint.getDisplayName() : agentId,
            blueprint.getResponsibility() != null ? blueprint.getResponsibility() : "",
            AgentRuntimeProfile.Level.MID
        );

        if (blueprint.getResponsibility() != null && !blueprint.getResponsibility().isBlank()) {
            role.responsibilities(blueprint.getResponsibility());
        }

        // Wire allowed tools — resolve effective policy: workspace ∩ agent blueprint
        Set<String> effectiveTools = resolveEffectiveAllowedTools(workspaceId, teamId, blueprint);
        for (String tool : effectiveTools) {
            role.allowedTools(tool);
        }

        // Wire denied tools — workspace-level deny list
        Set<String> deniedTools = resolveEffectiveDeniedTools(workspaceId, teamId, blueprint);
        for (String tool : deniedTools) {
            role.deniedTools(tool);
        }

        // Wire allowed skills — resolve effective policy
        Set<String> effectiveSkills = resolveEffectiveAllowedSkills(workspaceId, teamId, blueprint);
        for (String skill : effectiveSkills) {
            role.skills(skill);
        }

        // Wire tool approval rules from blueprint
        if (blueprint.getToolApprovalRules() != null) {
            for (String rule : blueprint.getToolApprovalRules()) {
                if (rule != null && !rule.isBlank()) {
                    role.toolApprovalRules(rule);
                }
            }
        }

        // Create agent instance with explicit agentId and role
        String sessionId = "team-agent:" + blueprint.getAgentId();
        TenantAwareAIAgent agent = TenantAwareAIAgent.forBlueprint(tenantCtx, blueprint.getAgentId(), role, sessionId, baseConfig);

        // Wire tool approval coordinator if available
        if (toolApprovalCoordinator != null) {
            final String wsId = workspaceId;
            final String tId = teamId;
            final String aId = blueprint.getAgentId();
            final TenantAwareAIAgent agentRef = agent;
            agent.setToolApprovalCallback(ex -> {
                // Generate a tool call ID — the agent uses the actual ToolCall ID,
                // but our exception only has tool name. We extract from the agent's pending approval.
                var pending = agentRef.getPendingToolApproval();
                String toolCallId = pending != null
                    ? extractToolCallId(agentRef)
                    : null;
                toolApprovalCoordinator.requestToolApproval(
                    wsId, tId, aId,
                    ex.getToolName(), ex.getToolArguments(),
                    ex.getMatchedRule(), ex.getReason(),
                    toolCallId,
                    agentRef
                );
            });
        }

        return agent;
    }

    private static String extractToolCallId(TenantAwareAIAgent agent) {
        // The agent stores the pending tool call internally; use its ID for traceability.
        // We rely on the agent's internal checkpoint to track the actual ToolCall.id.
        // For now, return a placeholder — the resume() call will use the agent's stored tool call ID.
        return null;
    }

    /**
     * Resolve effective allowed tools for an agent: workspace policy ∩ agent blueprint.
     * If PolicyService is wired, use its full resolution. Otherwise fall back to blueprint-only.
     */
    private Set<String> resolveEffectiveAllowedTools(String workspaceId, String teamId, AgentBlueprintRecord blueprint) {
        if (policyService != null) {
            return policyService.resolveAllowedTools(workspaceId, teamId, blueprint.getAgentId());
        }
        // Fallback: blueprint allowedTools only
        Set<String> result = new HashSet<>();
        if (blueprint.getAllowedTools() != null) {
            for (String tool : blueprint.getAllowedTools()) {
                if (tool != null && !tool.isBlank()) {
                    result.add(tool);
                }
            }
        }
        return result;
    }

    /**
     * Resolve effective allowed skills for an agent: workspace policy ∩ agent blueprint.
     */
    private Set<String> resolveEffectiveAllowedSkills(String workspaceId, String teamId, AgentBlueprintRecord blueprint) {
        if (policyService != null) {
            return policyService.resolveAllowedSkills(workspaceId, teamId, blueprint.getAgentId());
        }
        Set<String> result = new HashSet<>();
        if (blueprint.getAllowedSkills() != null) {
            for (String skill : blueprint.getAllowedSkills()) {
                if (skill != null && !skill.isBlank()) {
                    result.add(skill);
                }
            }
        }
        return result;
    }

    /**
     * Resolve effective denied tools for an agent (workspace denied list + blueprint-level).
     */
    private Set<String> resolveEffectiveDeniedTools(String workspaceId, String teamId, AgentBlueprintRecord blueprint) {
        if (policyService != null) {
            return policyService.resolveDeniedTools(workspaceId, teamId, blueprint.getAgentId());
        }
        return Set.of();
    }

    /**
     * Resolve effective denied skills for an agent.
     */
    private Set<String> resolveEffectiveDeniedSkills(String workspaceId, String teamId, AgentBlueprintRecord blueprint) {
        if (policyService != null) {
            return policyService.resolveDeniedSkills(workspaceId, teamId, blueprint.getAgentId());
        }
        return Set.of();
    }

    public boolean isTeamActive(String workspaceId, String teamId) {
        var wm = activeAgents.get(workspaceId);
        return wm != null && wm.containsKey(teamId) && !wm.get(teamId).isEmpty();
    }
}
