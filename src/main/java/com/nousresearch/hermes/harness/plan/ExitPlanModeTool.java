package com.nousresearch.hermes.harness.plan;

import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.tools.ToolEntry;

import java.util.Map;

/**
 * Tool that allows the model to exit plan mode by submitting a plan for approval.
 * When the plan is approved, plan mode is deactivated and the model can execute.
 *
 * This tool has concludesTurn=true (P2-4): after plan approval, the current turn ends.
 */
public class ExitPlanModeTool {

    private final PlanModeController controller;

    public ExitPlanModeTool(PlanModeController controller) {
        this.controller = controller;
    }

    /**
     * Build a ToolEntry for registration with ToolRegistry.
     * The tool handler parses the plan from args and submits it.
     *
     * In a real implementation, this would call back to a user-questions seam
     * to ask the user to approve. Here we auto-approve for simplicity.
     */
    public ToolEntry toToolEntry() {
        return new ToolEntry.Builder()
            .name("exit_plan_mode")
            .toolset("system")
            .schema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "plan", Map.of(
                        "type", "string",
                        "description", "The complete plan to present for user approval"
                    )
                ),
                "required", java.util.List.of("plan")
            ))
            .handler(args -> {
                String plan = (String) args.get("plan");
                if (plan == null || plan.isBlank()) {
                    return "{\"error\": \"plan is required\"}";
                }

                if (!controller.isActive()) {
                    return "{\"error\": \"exit_plan_mode can only be used in plan mode\"}";
                }

                // Submit plan - in production this would trigger user approval
                // For now, auto-approve
                controller.approve("Plan auto-approved");
                return "{\"status\": \"approved\", \"message\": \"Plan approved. Execute the plan from your next step.\"}";
            })
            .description("Exit plan mode by submitting a plan for user approval")
            .emoji("📋")
            .risk(ToolRisk.NONE)
            .requiresApproval(false)
            .approvalType(ApprovalSystem.ApprovalType.TERMINAL_COMMAND)
            .concludesTurn(true)
            .build();
    }

    /**
     * Whether this tool should end the current turn after execution.
     * (P2-4: concludesTurn)
     */
    public boolean concludesTurn() {
        return true;
    }
}
