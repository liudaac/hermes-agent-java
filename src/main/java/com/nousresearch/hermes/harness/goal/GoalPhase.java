package com.nousresearch.hermes.harness.goal;

public enum GoalPhase {
    ACTIVE,
    PAUSED,
    BLOCKED,
    COMPLETE;

    public boolean isTerminal() {
        return this == COMPLETE;
    }

    public boolean isBlocked() {
        return this == BLOCKED;
    }
}
