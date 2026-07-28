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

    // ======== TeamRuntimeRegistry ========

    // ======== Activity Log ========

}
