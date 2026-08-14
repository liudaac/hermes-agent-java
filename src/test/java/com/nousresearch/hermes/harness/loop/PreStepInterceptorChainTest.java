package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreStepInterceptorChainTest {

    private PreStepContext makeCtx() {
        return new PreStepContext(1, 0, List.of(ModelMessage.user("hello")), "sess", "tenant");
    }

    @Test
    void emptyChainReturnsEnter() {
        var chain = new PreStepInterceptorChain();
        var decision = chain.intercept(makeCtx());
        assertEquals(PreStepDecision.Kind.ENTER, decision.kind());
    }

    @Test
    void singleInterceptorEnterPassesThrough() {
        var chain = new PreStepInterceptorChain();
        chain.add(new InterceptorStub(10, PreStepDecision.enter()));
        var decision = chain.intercept(makeCtx());
        assertEquals(PreStepDecision.Kind.ENTER, decision.kind());
    }

    @Test
    void singleInterceptorRejectStopsChain() {
        var chain = new PreStepInterceptorChain();
        chain.add(new InterceptorStub(10, PreStepDecision.reject("too many turns")));
        var decision = chain.intercept(makeCtx());
        assertEquals(PreStepDecision.Kind.REJECT, decision.kind());
        assertEquals("too many turns", decision.reason());
    }

    @Test
    void rewriteUpdatesMessagesForSubsequentInterceptors() {
        var chain = new PreStepInterceptorChain();
        var rewrittenMessages = List.of(ModelMessage.user("rewritten"));
        var seenMessages = new java.util.ArrayList<List<ModelMessage>>();

        chain.add(new InterceptorStub(10, PreStepDecision.rewrite(rewrittenMessages)) {
            @Override
            public PreStepDecision intercept(PreStepContext ctx) {
                seenMessages.add(ctx.history());
                return PreStepDecision.rewrite(rewrittenMessages);
            }
        });
        chain.add(new InterceptorStub(20, PreStepDecision.enter()) {
            @Override
            public PreStepDecision intercept(PreStepContext ctx) {
                seenMessages.add(ctx.history());
                return PreStepDecision.enter();
            }
        });

        var decision = chain.intercept(makeCtx());
        assertEquals(PreStepDecision.Kind.REWRITE, decision.kind());
        assertEquals(2, seenMessages.size());
        // First interceptor sees original messages
        assertEquals(1, seenMessages.get(0).size());
        // Second interceptor sees rewritten messages
        assertEquals(1, seenMessages.get(1).size());
        assertEquals("rewritten", seenMessages.get(1).get(0).getContent());
    }

    @Test
    void multipleInterceptorsRunInOrder() {
        var chain = new PreStepInterceptorChain();
        var order = new java.util.ArrayList<Integer>();

        chain.add(new InterceptorStub(30, PreStepDecision.enter()) {
            @Override public PreStepDecision intercept(PreStepContext ctx) { order.add(30); return PreStepDecision.enter(); }
        });
        chain.add(new InterceptorStub(10, PreStepDecision.enter()) {
            @Override public PreStepDecision intercept(PreStepContext ctx) { order.add(10); return PreStepDecision.enter(); }
        });
        chain.add(new InterceptorStub(20, PreStepDecision.enter()) {
            @Override public PreStepDecision intercept(PreStepContext ctx) { order.add(20); return PreStepDecision.enter(); }
        });

        chain.intercept(makeCtx());
        assertEquals(List.of(10, 20, 30), order);
    }

    @Test
    void exceptionInInterceptorIsCaughtAndChainContinues() {
        var chain = new PreStepInterceptorChain();
        chain.add(new InterceptorStub(10, PreStepDecision.enter()) {
            @Override public PreStepDecision intercept(PreStepContext ctx) {
                throw new RuntimeException("boom");
            }
        });
        chain.add(new InterceptorStub(20, PreStepDecision.enter()));
        var decision = chain.intercept(makeCtx());
        assertEquals(PreStepDecision.Kind.ENTER, decision.kind());
    }

    @Test
    void removeAndClearWorkCorrectly() {
        var chain = new PreStepInterceptorChain();
        var stub1 = new InterceptorStub(10, PreStepDecision.enter());
        var stub2 = new InterceptorStub(20, PreStepDecision.enter());

        chain.add(stub1);
        chain.add(stub2);
        assertEquals(2, chain.size());

        assertTrue(chain.remove(stub1));
        assertEquals(1, chain.size());

        chain.clear();
        assertEquals(0, chain.size());
    }

    /** Simple stub interceptor for testing */
    static class InterceptorStub implements PreStepInterceptor {
        private final int order;
        private final PreStepDecision decision;

        InterceptorStub(int order, PreStepDecision decision) {
            this.order = order;
            this.decision = decision;
        }

        @Override public int order() { return order; }
        @Override public PreStepDecision intercept(PreStepContext ctx) { return decision; }
    }
}
