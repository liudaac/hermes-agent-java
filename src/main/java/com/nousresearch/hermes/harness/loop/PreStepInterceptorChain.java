package com.nousresearch.hermes.harness.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Executes a chain of PreStepInterceptors in order.
 * First REJECT wins. First REWRITE wins (subsequent interceptors see rewritten messages).
 * If all ENTER, the step proceeds normally.
 */
public class PreStepInterceptorChain {
    private static final Logger logger = LoggerFactory.getLogger(PreStepInterceptorChain.class);

    private final List<PreStepInterceptor> interceptors = new ArrayList<>();

    public void add(PreStepInterceptor interceptor) {
        interceptors.add(interceptor);
        interceptors.sort(Comparator.comparingInt(PreStepInterceptor::order));
    }

    public boolean remove(PreStepInterceptor interceptor) {
        return interceptors.remove(interceptor);
    }

    public void clear() {
        interceptors.clear();
    }

    public int size() {
        return interceptors.size();
    }

    /**
     * Run all interceptors. Returns the final decision.
     * - If any returns REJECT, immediately return that decision.
     * - If any returns REWRITE, update messages for subsequent interceptors and continue.
     * - If all return ENTER, return ENTER.
     */
    public PreStepDecision intercept(PreStepContext ctx) {
        PreStepDecision current = PreStepDecision.enter();
        List<PreStepInterceptor> snapshot = new ArrayList<>(interceptors);

        for (PreStepInterceptor interceptor : snapshot) {
            try {
                PreStepDecision decision = interceptor.intercept(ctx);
                if (decision == null) continue; // treat null as ENTER

                if (decision.kind() == PreStepDecision.Kind.REJECT) {
                    logger.debug("Step rejected by {} (order={}): {}",
                        interceptor.getClass().getSimpleName(), interceptor.order(), decision.reason());
                    return decision;
                }

                if (decision.kind() == PreStepDecision.Kind.REWRITE && decision.messages() != null) {
                    logger.debug("Messages rewritten by {} (order={})",
                        interceptor.getClass().getSimpleName(), interceptor.order());
                    current = decision;
                    // Continue chain with rewritten messages - create new context
                    ctx = new PreStepContext(ctx.turn(), ctx.step(),
                        decision.messages(), ctx.sessionId(), ctx.tenantId());
                }
            } catch (Exception e) {
                logger.warn("PreStepInterceptor {} threw exception, skipping: {}",
                    interceptor.getClass().getSimpleName(), e.getMessage());
            }
        }

        return current;
    }
}
