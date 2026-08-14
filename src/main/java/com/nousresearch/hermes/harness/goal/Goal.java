package com.nousresearch.hermes.harness.goal;

/**
 * A persistent goal within a session.
 * Tracks objective, phase, round budget, and blocking reasons.
 */
public class Goal {
    private final String id;
    private final String sessionId;
    private final String objective;
    private GoalPhase phase;
    private int revision;
    private final int maxGoalRounds;
    private int roundsStarted;
    private String blockedCode;
    private String blockedMessage;
    private final long createdAt;
    private long updatedAt;

    public Goal(String id, String sessionId, String objective, int maxGoalRounds) {
        this.id = id;
        this.sessionId = sessionId;
        this.objective = objective;
        this.maxGoalRounds = maxGoalRounds;
        this.phase = GoalPhase.ACTIVE;
        this.revision = 0;
        this.roundsStarted = 0;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // Package-private constructor for snapshot
    Goal(String id, String sessionId, String objective, int maxGoalRounds, long createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.objective = objective;
        this.maxGoalRounds = maxGoalRounds;
        this.phase = GoalPhase.ACTIVE;
        this.revision = 0;
        this.roundsStarted = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String id() { return id; }
    public String sessionId() { return sessionId; }
    public String objective() { return objective; }
    public GoalPhase phase() { return phase; }
    public int revision() { return revision; }
    public int maxGoalRounds() { return maxGoalRounds; }
    public int roundsStarted() { return roundsStarted; }
    public String blockedCode() { return blockedCode; }
    public String blockedMessage() { return blockedMessage; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }

    public boolean canAdmitRound() {
        return phase == GoalPhase.ACTIVE && roundsStarted < maxGoalRounds;
    }

    public boolean admitRound() {
        if (!canAdmitRound()) return false;
        roundsStarted++;
        revision++;
        updatedAt = System.currentTimeMillis();
        return true;
    }

    public void block(String code, String message) {
        this.blockedCode = code;
        this.blockedMessage = message;
        this.phase = GoalPhase.BLOCKED;
        this.revision++;
        this.updatedAt = System.currentTimeMillis();
    }

    public void complete() {
        this.phase = GoalPhase.COMPLETE;
        this.revision++;
        this.updatedAt = System.currentTimeMillis();
    }

    public void pause() {
        if (phase == GoalPhase.ACTIVE) {
            this.phase = GoalPhase.PAUSED;
            this.revision++;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public void resume() {
        if (phase == GoalPhase.PAUSED) {
            this.phase = GoalPhase.ACTIVE;
            this.blockedCode = null;
            this.blockedMessage = null;
            this.revision++;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public void unblock() {
        if (phase == GoalPhase.BLOCKED) {
            this.phase = GoalPhase.ACTIVE;
            this.blockedCode = null;
            this.blockedMessage = null;
            this.revision++;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public Goal snapshot() {
        Goal g = new Goal(id, sessionId, objective, maxGoalRounds, createdAt);
        g.phase = this.phase;
        g.revision = this.revision;
        g.roundsStarted = this.roundsStarted;
        g.blockedCode = this.blockedCode;
        g.blockedMessage = this.blockedMessage;
        g.updatedAt = this.updatedAt;
        return g;
    }

    @Override
    public String toString() {
        return "Goal{id=" + id + ", objective='" + objective + "', phase=" + phase
            + ", rounds=" + roundsStarted + "/" + maxGoalRounds
            + (blockedCode != null ? ", blocked=" + blockedCode : "")
            + ", rev=" + revision + "}";
    }
}
