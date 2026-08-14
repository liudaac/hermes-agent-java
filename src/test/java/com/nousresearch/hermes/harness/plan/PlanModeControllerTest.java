package com.nousresearch.hermes.harness.plan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanModeControllerTest {

    @Test
    void initialState_isInactive() {
        PlanModeController controller = new PlanModeController();
        assertEquals(PlanModeState.INACTIVE, controller.state());
        assertFalse(controller.isActive());
    }

    @Test
    void activate_setsActive() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        assertEquals(PlanModeState.ACTIVE, controller.state());
        assertTrue(controller.isActive());
    }

    @Test
    void deactivate_setsInactive() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        controller.deactivate();
        assertEquals(PlanModeState.INACTIVE, controller.state());
        assertFalse(controller.isActive());
    }

    @Test
    void approve_deactivatesAndStoresFeedback() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        controller.approve("Looks good");
        assertEquals(PlanModeState.INACTIVE, controller.state());
        assertFalse(controller.isActive());
        assertEquals("Looks good", controller.planFeedback());
    }

    @Test
    void reject_keepsActiveAndStoresFeedback() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        controller.reject("Need more detail");
        assertEquals(PlanModeState.ACTIVE, controller.state());
        assertTrue(controller.isActive());
        assertEquals("Need more detail", controller.planFeedback());
    }

    @Test
    void submitPlan_storesPendingPlan() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        assertTrue(controller.submitPlan("Step 1: Do X\nStep 2: Do Y"));
        assertEquals("Step 1: Do X\nStep 2: Do Y", controller.pendingPlan());
    }

    @Test
    void activate_clearsPreviousState() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        controller.submitPlan("Some plan");
        controller.reject("Bad");
        assertNotNull(controller.pendingPlan());
        assertNotNull(controller.planFeedback());
        // Reactivate should clear
        controller.activate();
        assertNull(controller.pendingPlan());
        assertNull(controller.planFeedback());
    }

    @Test
    void deactivate_clearsState() {
        PlanModeController controller = new PlanModeController();
        controller.activate();
        controller.submitPlan("Some plan");
        controller.deactivate();
        assertNull(controller.pendingPlan());
        assertNull(controller.planFeedback());
    }
}
