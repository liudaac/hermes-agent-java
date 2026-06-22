package com.nousresearch.hermes.collaboration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AI原生组织 第三刀：TeamRuntime + TeamRuntimeRegistry
 */
class TeamRuntimeTest {

    private TeamRuntimeRegistry manager;
    private TeamRuntime team;

    @BeforeEach
    void setUp() {
        manager = new TeamRuntimeRegistry("test-tenant");
        team = manager.createTeam("team-1", "Engineering Team", "Build the dashboard", "test-user");
    }

    @Test
    void teamBasicProperties() {
        assertEquals("team-1", team.getTeamId());
        assertEquals("Engineering Team", team.getName());
        assertEquals("Build the dashboard", team.getMission());
        assertEquals("test-tenant", team.getTenantId());
        assertEquals(0, team.size());
    }

    @Test
    void addAndRemoveMember() {
        assertTrue(team.addMember("agent-1"));
        assertTrue(team.addMember("agent-2"));
        assertEquals(2, team.size());
        assertTrue(team.hasMember("agent-1"));
        assertTrue(team.hasMember("agent-2"));
        assertTrue(team.removeMember("agent-1"));
        assertEquals(1, team.size());
        assertFalse(team.hasMember("agent-1"));
    }

    @Test
    void addSameMemberIdempotent() {
        assertTrue(team.addMember("agent-1"));
        assertFalse(team.addMember("agent-1"));
        assertEquals(1, team.size());
    }

    @Test
    void setAndClearLead() {
        team.addMember("agent-1");
        team.setLead("agent-1");
        assertEquals("agent-1", team.getLead());
        team.setLead(null);
        assertNull(team.getLead());
    }

    @Test
    void setLeadAutoAddsMember() {
        team.setLead("agent-1");
        assertTrue(team.hasMember("agent-1"));
    }

    @Test
    void removeLeadClearsLeadership() {
        team.addMember("agent-1");
        team.setLead("agent-1");
        team.removeMember("agent-1");
        assertNull(team.getLead());
    }

    @Test
    void sharedStatePutAndGet() {
        team.putState("decision:auth", "Use OIDC");
        team.putState("finding:race", "Found race in handler");
        var state = team.getState();
        assertEquals(2, state.size());
        assertEquals("Use OIDC", state.get("decision:auth"));
    }

    @Test
    void describeForPrompt() {
        team.addMember("agent-1");
        team.addMember("agent-2");
        team.setLead("agent-1");
        team.putState("decision:auth", "Use OIDC");

        String desc = team.describeForPrompt();
        assertTrue(desc.contains("Engineering Team"));
        assertTrue(desc.contains("Build the dashboard"));
        assertTrue(desc.contains("agent-1"));
        assertTrue(desc.contains("agent-2"));
    }

    @Test
    void teamToMapContainsAllFields() {
        team.addMember("agent-1");
        var map = team.toMap();
        assertEquals("team-1", map.get("team_id"));
        assertEquals("Engineering Team", map.get("name"));
        assertEquals("Build the dashboard", map.get("mission"));
        assertTrue(map.containsKey("members"));
        assertTrue(map.containsKey("created_at"));
    }

    // ======== TeamRuntimeRegistry ========

    @Test
    void managerCreateAndListTeams() {
        var team2 = manager.createTeam("team-2", "Ops TeamRuntime", "Operate the cluster", "ops-user");
        assertEquals(2, manager.teamCount());
        assertEquals(2, manager.listTeams().size());
        assertNotNull(manager.getTeam("team-1"));
        assertNotNull(manager.getTeam("team-2"));
    }

    @Test
    void managerDeleteTeam() {
        assertTrue(manager.deleteTeam("team-1"));
        assertEquals(0, manager.teamCount());
        assertNull(manager.getTeam("team-1"));
    }

    @Test
    void getTeamsForAgent() {
        var team2 = manager.createTeam("team-2", "Ops TeamRuntime", "Ops", "user");
        team.addMember("agent-1");
        team2.addMember("agent-1");
        team2.addMember("agent-2");

        var teamsForAgent1 = manager.getTeamsForAgent("agent-1");
        assertEquals(2, teamsForAgent1.size());

        var teamsForAgent2 = manager.getTeamsForAgent("agent-2");
        assertEquals(1, teamsForAgent2.size());
    }

    @Test
    void getOrCreateDefaultTeamCreatesSingleton() {
        var defaultTeam = manager.getOrCreateDefaultTeam("agent-lonely");
        assertNotNull(defaultTeam);
        assertTrue(defaultTeam.hasMember("agent-lonely"));
        assertEquals("agent-lonely", defaultTeam.getLead());

        // Second call returns same team
        var sameTeam = manager.getOrCreateDefaultTeam("agent-lonely");
        assertEquals(defaultTeam.getTeamId(), sameTeam.getTeamId());
    }

    @Test
    void getOrCreateDefaultTeamReusesExisting() {
        team.addMember("agent-1");
        var teamForAgent = manager.getOrCreateDefaultTeam("agent-1");
        // Should reuse the existing team-1 that agent-1 is already a member of
        assertEquals("team-1", teamForAgent.getTeamId());
    }

    @Test
    void createDuplicateTeamReturnsExisting() {
        var duplicate = manager.createTeam("team-1", "Different Name", "Different Mission", "user");
        assertEquals("Engineering Team", duplicate.getName()); // first wins
        assertEquals(1, manager.teamCount());
    }

    @Test
    void teamSummary() {
        team.addMember("agent-1");
        team.addMember("agent-2");
        var summary = manager.toSummary();
        assertEquals(1, summary.get("total_teams"));
        assertEquals(2, summary.get("total_members"));
    }

    // ======== Activity Log ========

    @Test
    void activityRecordedOnMemberJoin() {
        team.addMember("agent-1");
        var activity = team.getRecentActivity(10);
        assertTrue(activity.stream().anyMatch(a -> a.type().equals("member_joined")));
    }

    @Test
    void activityRecordedOnLeadAssignment() {
        team.addMember("agent-1");
        team.setLead("agent-1");
        var activity = team.getRecentActivity(10);
        assertTrue(activity.stream().anyMatch(a -> a.type().equals("lead_assigned")));
    }

    @Test
    void activityCappedAtMax() {
        for (int i = 0; i < 100; i++) {
            team.putState("key" + i, "value" + i);
        }
        var activity = team.getRecentActivity(1000);
        assertTrue(activity.size() <= 50, "Activity should be capped, got " + activity.size());
    }
}
