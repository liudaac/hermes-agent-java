package com.nousresearch.hermes.harness.goal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages goal lifecycle per session.
 * Thread-safe, in-memory (per JVM).
 */
public class GoalService {
    private static final Logger logger = LoggerFactory.getLogger(GoalService.class);

    private static final int DEFAULT_MAX_ROUNDS = 10;

    private final Map<String, Goal> goalsBySession = new ConcurrentHashMap<>();

    /**
     * Create a new goal for a session.
     * Replaces any existing goal for the session.
     */
    public Goal createGoal(String sessionId, String objective, int maxRounds) {
        String goalId = UUID.randomUUID().toString().substring(0, 8);
        Goal goal = new Goal(goalId, sessionId, objective, maxRounds);
        goalsBySession.put(sessionId, goal);
        logger.info("Goal created: {} for session {}", goal, sessionId);
        return goal;
    }

    /**
     * Create a goal with default max rounds.
     */
    public Goal createGoal(String sessionId, String objective) {
        return createGoal(sessionId, objective, DEFAULT_MAX_ROUNDS);
    }

    /**
     * Get the current goal for a session.
     */
    public Goal getCurrentGoal(String sessionId) {
        return goalsBySession.get(sessionId);
    }

    /**
     * Get the current goal as a snapshot (safe to share).
     */
    public Goal getSnapshot(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        return goal != null ? goal.snapshot() : null;
    }

    /**
     * Try to admit a round for the session's goal.
     * @return true if a round was consumed, false if goal is exhausted/blocked/complete
     */
    public boolean admitRound(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        if (goal == null) return true; // no goal = unlimited
        return goal.admitRound();
    }

    /**
     * Block the goal for a session.
     */
    public void blockGoal(String sessionId, String code, String message) {
        Goal goal = goalsBySession.get(sessionId);
        if (goal != null) {
            goal.block(code, message);
            logger.warn("Goal blocked: {} code={} msg={}", goal.id(), code, message);
        }
    }

    /**
     * Complete the goal for a session.
     */
    public void completeGoal(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        if (goal != null) {
            goal.complete();
            logger.info("Goal completed: {}", goal.id());
        }
    }

    /**
     * Pause the goal.
     */
    public void pauseGoal(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        if (goal != null) goal.pause();
    }

    /**
     * Resume the goal.
     */
    public void resumeGoal(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        if (goal != null) goal.resume();
    }

    /**
     * Unblock the goal.
     */
    public void unblockGoal(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        if (goal != null) goal.unblock();
    }

    /**
     * Remove the goal for a session.
     */
    public void removeGoal(String sessionId) {
        goalsBySession.remove(sessionId);
    }

    /**
     * Check if the session has an active goal.
     */
    public boolean hasActiveGoal(String sessionId) {
        Goal goal = goalsBySession.get(sessionId);
        return goal != null && goal.phase() == GoalPhase.ACTIVE;
    }
}
