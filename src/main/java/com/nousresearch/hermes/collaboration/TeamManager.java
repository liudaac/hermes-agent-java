package com.nousresearch.hermes.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages teams within a single tenant.
 *
 * <p>Provides:
 * <ul>
 *   <li>Create/list/delete teams</li>
 *   <li>Add/remove members</li>
 *   <li>Look up teams by member</li>
 *   <li>Set team leads</li>
 * </ul>
 *
 * <p>This is tenant-scoped — each tenant has its own TeamManager.
 * Team membership and shared state are isolated across tenants.</p>
 */
public class TeamManager {
    private static final Logger logger = LoggerFactory.getLogger(TeamManager.class);

    private final String tenantId;
    private final ConcurrentHashMap<String, Team> teams = new ConcurrentHashMap<>();

    public TeamManager(String tenantId) {
        this.tenantId = tenantId;
    }

    public Team createTeam(String teamId, String name, String mission, String createdBy) {
        if (teams.containsKey(teamId)) {
            logger.warn("Team {} already exists in tenant {}", teamId, tenantId);
            return teams.get(teamId);
        }
        Team team = new Team(teamId, name, mission, tenantId, createdBy);
        teams.put(teamId, team);
        logger.info("Tenant {}: created team '{}' ({})", tenantId, name, teamId);
        return team;
    }

    public boolean deleteTeam(String teamId) {
        Team removed = teams.remove(teamId);
        if (removed != null) {
            logger.info("Tenant {}: deleted team '{}' ({})", tenantId, removed.getName(), teamId);
            return true;
        }
        return false;
    }

    public Team getTeam(String teamId) {
        return teams.get(teamId);
    }

    public List<Team> listTeams() {
        return new ArrayList<>(teams.values());
    }

    public int teamCount() {
        return teams.size();
    }

    /**
     * Find all teams a given agent belongs to.
     */
    public List<Team> getTeamsForAgent(String agentId) {
        List<Team> out = new ArrayList<>();
        for (Team t : teams.values()) {
            if (t.hasMember(agentId)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Get the default team for an agent — the smallest team they belong to.
     * Falls back to creating a singleton team if none exists.
     */
    public Team getOrCreateDefaultTeam(String agentId) {
        var existing = getTeamsForAgent(agentId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        // Create a default singleton team
        String teamId = "team_" + agentId;
        Team team = createTeam(teamId, agentId + "'s workspace", "Default workspace for " + agentId, "system");
        team.addMember(agentId);
        team.setLead(agentId);
        return team;
    }

    public Map<String, Object> toSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_teams", teams.size());
        m.put("total_members", teams.values().stream().mapToInt(Team::size).sum());
        m.put("teams", teams.values().stream().map(Team::toMap).toList());
        return m;
    }
}
