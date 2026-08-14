package com.nousresearch.hermes.harness.plan;

import com.nousresearch.hermes.harness.prompt.PromptAssembleContext;
import com.nousresearch.hermes.harness.prompt.PromptSection;

/**
 * Injects plan mode guidance into the system prompt when active.
 * order=220 (after goal section).
 */
public class PlanPromptSection implements PromptSection {
    private final PlanModeController controller;

    public PlanPromptSection(PlanModeController controller) {
        this.controller = controller;
    }

    @Override
    public String name() { return "hermes:plan-mode"; }

    @Override
    public int order() { return 220; }

    @Override
    public String render(PromptAssembleContext ctx) {
        if (!controller.isActive()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Plan Mode Active\n");
        sb.append("You are in plan mode. Research and formulate a complete plan.\n");
        sb.append("Use `exit_plan_mode` to present your plan for user review.\n");
        sb.append("Do not execute any actions until the plan is approved.\n");

        if (controller.planFeedback() != null) {
            sb.append("\n### Previous Plan Feedback\n");
            sb.append(controller.planFeedback());
        }

        return sb.toString();
    }
}
