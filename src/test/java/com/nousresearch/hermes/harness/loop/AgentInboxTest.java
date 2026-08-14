package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentInboxTest {

    @Test
    void sendToNextTurnClaimReturnsIt() {
        var inbox = new AgentInbox();
        var msg = ModelMessage.user("followup");
        inbox.send(msg, InboxTarget.NEXT_TURN);

        var entries = inbox.claimNextTurn();
        assertEquals(1, entries.size());
        assertEquals("followup", entries.get(0).message().getContent());
        assertEquals(InboxTarget.NEXT_TURN, entries.get(0).target());
    }

    @Test
    void sendToNextStepClaimReturnsIt() {
        var inbox = new AgentInbox();
        var msg = ModelMessage.user("steer");
        inbox.send(msg, InboxTarget.NEXT_STEP);

        var entries = inbox.claimNextStep();
        assertEquals(1, entries.size());
        assertEquals("steer", entries.get(0).message().getContent());
    }

    @Test
    void sendToInjectClaimReturnsIt() {
        var inbox = new AgentInbox();
        var msg = ModelMessage.system("inject");
        inbox.send(msg, InboxTarget.INJECT);

        var entries = inbox.claimInject();
        assertEquals(1, entries.size());
        assertEquals("inject", entries.get(0).message().getContent());
    }

    @Test
    void claimEmptiesQueue() {
        var inbox = new AgentInbox();
        inbox.send(ModelMessage.user("a"), InboxTarget.NEXT_TURN);
        inbox.send(ModelMessage.user("b"), InboxTarget.NEXT_TURN);

        var first = inbox.claimNextTurn();
        assertEquals(2, first.size());

        var second = inbox.claimNextTurn();
        assertTrue(second.isEmpty());
        assertFalse(inbox.hasNextTurn());
    }

    @Test
    void hasNextMethodsWork() {
        var inbox = new AgentInbox();
        assertFalse(inbox.hasNextTurn());
        assertFalse(inbox.hasNextStep());
        assertFalse(inbox.hasInject());

        inbox.send(ModelMessage.user("x"), InboxTarget.NEXT_TURN);
        assertTrue(inbox.hasNextTurn());
        assertFalse(inbox.hasNextStep());

        inbox.send(ModelMessage.user("y"), InboxTarget.NEXT_STEP);
        assertTrue(inbox.hasNextStep());

        inbox.send(ModelMessage.user("z"), InboxTarget.INJECT);
        assertTrue(inbox.hasInject());
    }

    @Test
    void convenienceMethodsWork() {
        var inbox = new AgentInbox();
        inbox.followup(ModelMessage.user("f"));
        inbox.steer(ModelMessage.user("s"));
        inbox.inject(ModelMessage.system("i"));

        assertEquals(1, inbox.pendingTurn());
        assertEquals(1, inbox.pendingStep());
        assertEquals(1, inbox.pendingInject());

        var turn = inbox.claimNextTurn();
        var step = inbox.claimNextStep();
        var inj = inbox.claimInject();

        assertEquals("f", turn.get(0).message().getContent());
        assertEquals("s", step.get(0).message().getContent());
        assertEquals("i", inj.get(0).message().getContent());
    }

    @Test
    void clearEmptiesAllQueues() {
        var inbox = new AgentInbox();
        inbox.followup(ModelMessage.user("a"));
        inbox.steer(ModelMessage.user("b"));
        inbox.inject(ModelMessage.system("c"));

        inbox.clear();

        assertEquals(0, inbox.pendingTurn());
        assertEquals(0, inbox.pendingStep());
        assertEquals(0, inbox.pendingInject());
        assertFalse(inbox.hasNextTurn());
        assertFalse(inbox.hasNextStep());
        assertFalse(inbox.hasInject());
    }

    @Test
    void concurrentAccessMultipleThreads() throws InterruptedException {
        var inbox = new AgentInbox();
        int threadCount = 10;
        int messagesPerThread = 100;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(threadCount);
        AtomicInteger total = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            pool.submit(() -> {
                for (int i = 0; i < messagesPerThread; i++) {
                    inbox.send(ModelMessage.user("msg-" + tid + "-" + i), InboxTarget.NEXT_TURN);
                    total.incrementAndGet();
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(threadCount * messagesPerThread, total.get());
        assertEquals(threadCount * messagesPerThread, inbox.pendingTurn());

        var claimed = inbox.claimNextTurn();
        assertEquals(threadCount * messagesPerThread, claimed.size());
        assertTrue(inbox.claimNextTurn().isEmpty());
    }
}
