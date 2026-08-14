package com.nousresearch.hermes.harness.goal;

import com.nousresearch.hermes.harness.loop.PreStepContext;
import com.nousresearch.hermes.harness.loop.PreStepDecision;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class GoalPreStepInterceptorTest {

    private PreStepContext ctx(String sessionId) {
        return new PreStepContext(1, 0, List.of(), sessionId, "tenant1");
    }

    @Test
    void noGoal_returnsEnter() {
        GoalService service = new GoalService();
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        PreStepDecision decision = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.ENTER, decision.kind());
    }

    @Test
    void activeGoalWithRounds_returnsEnter() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        PreStepDecision decision = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.ENTER, decision.kind());
    }

    @Test
    void blockedGoal_returnsReject() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.blockGoal("s1", "ERR", "Something went wrong");
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        PreStepDecision decision = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.REJECT, decision.kind());
        assertNotNull(decision.reason());
        assertTrue(decision.reason().contains("ERR"));
        assertTrue(decision.reason().contains("Something went wrong"));
    }

    @Test
    void completeGoal_returnsReject() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.completeGoal("s1");
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        PreStepDecision decision = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.REJECT, decision.kind());
        assertTrue(decision.reason().contains("complete"));
    }

    @Test
    void pausedGoal_returnsReject() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 5);
        service.pauseGoal("s1");
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        PreStepDecision decision = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.REJECT, decision.kind());
        assertTrue(decision.reason().contains("paused"));
    }

    @Test
    void exhaustedRounds_returnsReject() {
        GoalService service = new GoalService();
        service.createGoal("s1", "Objective", 1);
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        // First step: enters and consumes the round
        PreStepDecision d1 = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.ENTER, d1.kind());
        // Second step: exhausted
        PreStepDecision d2 = interceptor.intercept(ctx("s1"));
        assertEquals(PreStepDecision.Kind.REJECT, d2.kind());
        assertTrue(d2.reason().contains("exhausted"));
        assertTrue(d2.reason().contains("1/1"));
    }

    @Test
    void orderIs20() {
        GoalService service = new GoalService();
        GoalPreStepInterceptor interceptor = new GoalPreStepInterceptor(service);
        assertEquals(20, interceptor.order());
    }
}
