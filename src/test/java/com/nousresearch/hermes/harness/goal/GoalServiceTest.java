package com.nousresearch.hermes.harness.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalServiceTest {

    @Test
    void createGoal_storesGoalPerSession() {
        GoalService service = new GoalService();
        Goal goal = service.createGoal("session1", "Build feature", 5);
        assertNotNull(goal);
        assertEquals("session1", goal.sessionId());
        assertEquals("Build feature", goal.objective());
        assertSame(goal, service.getCurrentGoal("session1"));
    }

    @Test
    void getCurrentGoal_returnsNullForUnknownSession() {
        GoalService service = new GoalService();
        assertNull(service.getCurrentGoal("unknown"));
    }

    @Test
    void admitRound_onNoGoalSession_returnsTrue() {
        GoalService service = new GoalService();
        assertTrue(service.admitRound("no-goal-session"));
    }

    @Test
    void admitRound_consumesRounds() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 2);
        assertTrue(service.admitRound("s1"));
        assertTrue(service.admitRound("s1"));
        assertFalse(service.admitRound("s1"));
    }

    @Test
    void blockGoal_setsBlockedState() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.blockGoal("s1", "ERR", "Something went wrong");
        Goal goal = service.getCurrentGoal("s1");
        assertEquals(GoalPhase.BLOCKED, goal.phase());
        assertEquals("ERR", goal.blockedCode());
        assertEquals("Something went wrong", goal.blockedMessage());
    }

    @Test
    void completeGoal_setsCompleteState() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.completeGoal("s1");
        assertEquals(GoalPhase.COMPLETE, service.getCurrentGoal("s1").phase());
    }

    @Test
    void removeGoal_clearsTheGoal() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        assertNotNull(service.getCurrentGoal("s1"));
        service.removeGoal("s1");
        assertNull(service.getCurrentGoal("s1"));
    }

    @Test
    void hasActiveGoal_returnsCorrectBoolean() {
        GoalService service = new GoalService();
        assertFalse(service.hasActiveGoal("s1"));
        service.createGoal("s1", "Objective", 5);
        assertTrue(service.hasActiveGoal("s1"));
        service.completeGoal("s1");
        assertFalse(service.hasActiveGoal("s1"));
    }

    @Test
    void getSnapshot_returnsCopy() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.admitRound("s1");
        Goal snap = service.getSnapshot("s1");
        assertNotNull(snap);
        assertEquals(1, snap.roundsStarted());
        // Mutating snapshot should not affect original
        snap.complete();
        assertEquals(GoalPhase.ACTIVE, service.getCurrentGoal("s1").phase());
    }

    @Test
    void createGoal_replacesExistingGoal() {
        GoalService service = new GoalService();
        Goal g1 = service.createGoal("s1", "First objective", 5);
        Goal g2 = service.createGoal("s1", "Second objective", 3);
        assertSame(g2, service.getCurrentGoal("s1"));
        assertEquals("Second objective", service.getCurrentGoal("s1").objective());
    }

    @Test
    void pauseAndResumeGoal() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.pauseGoal("s1");
        assertEquals(GoalPhase.PAUSED, service.getCurrentGoal("s1").phase());
        assertFalse(service.hasActiveGoal("s1"));
        service.resumeGoal("s1");
        assertEquals(GoalPhase.ACTIVE, service.getCurrentGoal("s1").phase());
        assertTrue(service.hasActiveGoal("s1"));
    }

    @Test
    void unblockGoal_clearsBlockedState() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.blockGoal("s1", "ERR", "msg");
        assertEquals(GoalPhase.BLOCKED, service.getCurrentGoal("s1").phase());
        service.unblockGoal("s1");
        assertEquals(GoalPhase.ACTIVE, service.getCurrentGoal("s1").phase());
        assertNull(service.getCurrentGoal("s1").blockedCode());
    }
}
