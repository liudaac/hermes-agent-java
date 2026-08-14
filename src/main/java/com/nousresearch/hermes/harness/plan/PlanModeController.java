package com.nousresearch.hermes.harness.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Controls plan mode state for an agent session.
 * When active, the agent should research and plan before executing.
 */
public class PlanModeController {
    private static final Logger logger = LoggerFactory.getLogger(PlanModeController.class);

    private final AtomicReference<PlanModeState> state = new AtomicReference<>(PlanModeState.INACTIVE);
    private String pendingPlan;
    private String planFeedback;

    public void activate() {
        state.set(PlanModeState.ACTIVE);
        pendingPlan = null;
        planFeedback = null;
        logger.info("Plan mode activated");
    }

    public void deactivate() {
        state.set(PlanModeState.INACTIVE);
        pendingPlan = null;
        planFeedback = null;
        logger.info("Plan mode deactivated");
    }

    public boolean isActive() {
        return state.get() == PlanModeState.ACTIVE;
    }

    public PlanModeState state() {
        return state.get();
    }

    /**
     * Submit a plan for user review.
     * Returns true if approved, false if rejected (with feedback).
     */
    public boolean submitPlan(String plan) {
        this.pendingPlan = plan;
        // In a real implementation, this would trigger a user-questions seam
        // For now, the caller (ExitPlanModeTool) handles the approval flow
        return true;
    }

    public void approve(String feedback) {
        this.planFeedback = feedback;
        state.set(PlanModeState.INACTIVE);
        logger.info("Plan mode deactivated (approved)");
    }

    public void reject(String feedback) {
        this.planFeedback = feedback;
        // Stay in plan mode, let the model revise
        logger.info("Plan rejected with feedback: {}", feedback);
    }

    public String pendingPlan() { return pendingPlan; }
    public String planFeedback() { return planFeedback; }
}
