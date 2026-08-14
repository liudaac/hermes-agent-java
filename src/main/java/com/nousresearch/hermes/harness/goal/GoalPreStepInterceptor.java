package com.nousresearch.hermes.harness.goal;

import com.nousresearch.hermes.harness.loop.PreStepContext;
import com.nousresearch.hermes.harness.loop.PreStepDecision;
import com.nousresearch.hermes.harness.loop.PreStepInterceptor;

/**
 * Pre-step interceptor that enforces goal round limits.
 * order=20 (after compaction at 10, before plan at 30).
 */
public class GoalPreStepInterceptor implements PreStepInterceptor {
    private final GoalService goalService;

    public GoalPreStepInterceptor(GoalService goalService) {
        this.goalService = goalService;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public PreStepDecision intercept(PreStepContext ctx) {
        Goal goal = goalService.getCurrentGoal(ctx.sessionId());
        if (goal == null) return PreStepDecision.enter();

        if (goal.phase() == GoalPhase.BLOCKED) {
            return PreStepDecision.reject("Goal is blocked: " + goal.blockedCode()
                + " - " + goal.blockedMessage());
        }

        if (goal.phase() == GoalPhase.COMPLETE) {
            return PreStepDecision.reject("Goal is already complete");
        }

        if (goal.phase() == GoalPhase.PAUSED) {
            return PreStepDecision.reject("Goal is paused");
        }

        if (!goalService.admitRound(ctx.sessionId())) {
            return PreStepDecision.reject("Goal rounds exhausted ("
                + goal.roundsStarted() + "/" + goal.maxGoalRounds() + ")");
        }

        return PreStepDecision.enter();
    }
}
