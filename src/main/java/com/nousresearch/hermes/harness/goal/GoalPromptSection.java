package com.nousresearch.hermes.harness.goal;

import com.nousresearch.hermes.harness.prompt.PromptAssembleContext;
import com.nousresearch.hermes.harness.prompt.PromptSection;

/**
 * Injects current goal context into the system prompt.
 * order=210 (after default sections, in custom module range).
 */
public class GoalPromptSection implements PromptSection {
    private final GoalService goalService;

    public GoalPromptSection(GoalService goalService) {
        this.goalService = goalService;
    }

    @Override
    public String name() { return "hermes:goal"; }

    @Override
    public int order() { return 210; }

    @Override
    public String render(PromptAssembleContext ctx) {
        Goal goal = goalService.getCurrentGoal(ctx.sessionId());
        if (goal == null || goal.phase() == GoalPhase.COMPLETE) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Current Goal\n");
        sb.append(goal.objective()).append("\n\n");
        sb.append("- Rounds: ").append(goal.roundsStarted())
          .append("/").append(goal.maxGoalRounds()).append("\n");
        sb.append("- Phase: ").append(goal.phase());

        if (goal.phase() == GoalPhase.BLOCKED) {
            sb.append("\n- Blocked: ").append(goal.blockedCode())
              .append(" - ").append(goal.blockedMessage());
        }

        return sb.toString();
    }
}
