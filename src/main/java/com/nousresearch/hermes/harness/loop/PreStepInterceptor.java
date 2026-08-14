package com.nousresearch.hermes.harness.loop;

/**
 * Interceptor invoked before each model step in the agent loop.
 * Can reject the step, rewrite messages, or allow it through.
 */
public interface PreStepInterceptor {
    /** Priority - lower runs first. Convention: 10=compaction, 20=goal, 30=plan, 100+=custom */
    int order();
    /** Decision: ENTER, REJECT, or REWRITE */
    PreStepDecision intercept(PreStepContext ctx);
}
