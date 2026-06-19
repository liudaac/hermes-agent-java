package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges TeamBlueprint (business configuration) to running Agent instances.
 *
 * <p>When a team blueprint is activated, this runtime component spins up
 * a {@link TenantAwareAIAgent} for each agent blueprint entry, wires up
 * the matching {@link AgentRole}, and registers each one on the TenantBus
 * so that the IntentOrchestrator can discover and delegate to them.</p>
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
                TenantAwareAIAgent agent = createAgentFromBlueprint(tenantCtx, agentBlueprint);
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
     * Wires the AgentRole, registers on the bus.
     */
    private TenantAwareAIAgent createAgentFromBlueprint(TenantContext tenantCtx, AgentBlueprintRecord blueprint) {
        String agentId = blueprint.getAgentId();

        // Build the AgentRole from blueprint
        AgentRole role = new AgentRole(
            blueprint.getDisplayName() != null && !blueprint.getDisplayName().isBlank()
                ? blueprint.getDisplayName() : agentId,
            blueprint.getResponsibility() != null ? blueprint.getResponsibility() : "",
            AgentRole.Level.MID
        );

        if (blueprint.getResponsibility() != null && !blueprint.getResponsibility().isBlank()) {
            role.responsibilities(blueprint.getResponsibility());
        }

        // Wire allowed tools
        if (blueprint.getAllowedTools() != null) {
            for (String tool : blueprint.getAllowedTools()) {
                role.allowedTools(tool);
            }
        }

        // Wire allowed skills
        if (blueprint.getAllowedSkills() != null) {
            for (String skill : blueprint.getAllowedSkills()) {
                role.skills(skill);
            }
        }

        // Create agent instance with explicit agentId and role
        String sessionId = "team-agent:" + agentId;
        return TenantAwareAIAgent.forBlueprint(tenantCtx, agentId, role, sessionId, baseConfig);
    }

    public boolean isTeamActive(String workspaceId, String teamId) {
        var wm = activeAgents.get(workspaceId);
        return wm != null && wm.containsKey(teamId) && !wm.get(teamId).isEmpty();
    }
}
