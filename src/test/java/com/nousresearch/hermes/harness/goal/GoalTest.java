package com.nousresearch.hermes.harness.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalTest {

    @Test
    void goalCreation_withObjectiveAndMaxRounds() {
        Goal goal = new Goal("g1", "s1", "Build a website", 5);
        assertEquals("g1", goal.id());
        assertEquals("s1", goal.sessionId());
        assertEquals("Build a website", goal.objective());
        assertEquals(5, goal.maxGoalRounds());
        assertEquals(0, goal.roundsStarted());
        assertEquals(GoalPhase.ACTIVE, goal.phase());
        assertEquals(0, goal.revision());
        assertNull(goal.blockedCode());
        assertNull(goal.blockedMessage());
        assertTrue(goal.createdAt() > 0);
        assertTrue(goal.updatedAt() >= goal.createdAt());
    }

    @Test
    void admitRound_consumesRoundsCorrectly() {
        Goal goal = new Goal("g1", "s1", "Objective", 3);
        assertTrue(goal.admitRound());
        assertEquals(1, goal.roundsStarted());
        assertEquals(1, goal.revision());
        assertTrue(goal.admitRound());
        assertEquals(2, goal.roundsStarted());
        assertTrue(goal.admitRound());
        assertEquals(3, goal.roundsStarted());
    }

    @Test
    void admitRound_returnsFalseWhenExhausted() {
        Goal goal = new Goal("g1", "s1", "Objective", 2);
        assertTrue(goal.admitRound());
        assertTrue(goal.admitRound());
        assertFalse(goal.admitRound());
        assertEquals(2, goal.roundsStarted());
    }

    @Test
    void block_setsBlockedPhaseWithCodeAndMessage() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.block("ERR_001", "Missing dependency");
        assertEquals(GoalPhase.BLOCKED, goal.phase());
        assertEquals("ERR_001", goal.blockedCode());
        assertEquals("Missing dependency", goal.blockedMessage());
        assertTrue(goal.revision() > 0);
    }

    @Test
    void complete_setsCompletePhase() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.complete();
        assertEquals(GoalPhase.COMPLETE, goal.phase());
        assertTrue(goal.phase().isTerminal());
    }

    @Test
    void pauseResume_transitionsCorrectly() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.pause();
        assertEquals(GoalPhase.PAUSED, goal.phase());
        goal.resume();
        assertEquals(GoalPhase.ACTIVE, goal.phase());
    }

    @Test
    void unblock_fromBlockedToActive() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.block("ERR", "Blocked");
        assertEquals(GoalPhase.BLOCKED, goal.phase());
        goal.unblock();
        assertEquals(GoalPhase.ACTIVE, goal.phase());
        assertNull(goal.blockedCode());
        assertNull(goal.blockedMessage());
    }

    @Test
    void snapshot_returnsACopy() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.admitRound();
        goal.block("ERR", "msg");
        Goal snap = goal.snapshot();
        assertEquals(goal.id(), snap.id());
        assertEquals(goal.objective(), snap.objective());
        assertEquals(goal.phase(), snap.phase());
        assertEquals(goal.roundsStarted(), snap.roundsStarted());
        assertEquals(goal.revision(), snap.revision());
        assertEquals(goal.blockedCode(), snap.blockedCode());
        assertEquals(goal.blockedMessage(), snap.blockedMessage());
        // Mutating snapshot should not affect original
        snap.unblock();
        assertEquals(GoalPhase.BLOCKED, goal.phase());
        assertEquals(GoalPhase.ACTIVE, snap.phase());
    }

    @Test
    void canAdmitRound_logicForEachPhase() {
        Goal goal = new Goal("g1", "s1", "Objective", 1);
        // ACTIVE with remaining rounds
        assertTrue(goal.canAdmitRound());
        goal.admitRound();
        // ACTIVE but exhausted
        assertFalse(goal.canAdmitRound());

        Goal goal2 = new Goal("g2", "s1", "Objective", 5);
        goal2.block("E", "M");
        assertFalse(goal2.canAdmitRound()); // BLOCKED

        Goal goal3 = new Goal("g3", "s1", "Objective", 5);
        goal3.pause();
        assertFalse(goal3.canAdmitRound()); // PAUSED

        Goal goal4 = new Goal("g4", "s1", "Objective", 5);
        goal4.complete();
        assertFalse(goal4.canAdmitRound()); // COMPLETE
    }

    @Test
    void pause_onlyWorksFromActive() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.block("E", "M");
        goal.pause(); // should not change from BLOCKED
        assertEquals(GoalPhase.BLOCKED, goal.phase());
    }

    @Test
    void resume_onlyWorksFromPaused() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.block("E", "M");
        goal.resume(); // should not change from BLOCKED
        assertEquals(GoalPhase.BLOCKED, goal.phase());
    }

    @Test
    void unblock_onlyWorksFromBlocked() {
        Goal goal = new Goal("g1", "s1", "Objective", 5);
        goal.unblock(); // should not change from ACTIVE
        assertEquals(GoalPhase.ACTIVE, goal.phase());
    }

    @Test
    void toString_containsKeyInfo() {
        Goal goal = new Goal("g1", "s1", "Build feature", 5);
        String s = goal.toString();
        assertTrue(s.contains("g1"));
        assertTrue(s.contains("Build feature"));
        assertTrue(s.contains("ACTIVE"));
        assertTrue(s.contains("0/5"));
    }
}
