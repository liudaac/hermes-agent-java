package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Multi-target message queue for the agent loop.
 *
 * Supports three delivery targets:
 * - NEXT_TURN: messages queued for the next turn (followup from sub-agents, scheduled tasks)
 * - NEXT_STEP: messages injected into the current turn's next step (steer - user interrupts)
 * - INJECT: silently added to history without triggering a new step
 */
public class AgentInbox {
    private static final Logger logger = LoggerFactory.getLogger(AgentInbox.class);

    private final ConcurrentLinkedQueue<InboxEntry> nextTurnQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<InboxEntry> nextStepQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<InboxEntry> injectQueue = new ConcurrentLinkedQueue<>();

    /**
     * Send a message to a specific target queue.
     */
    public void send(ModelMessage message, InboxTarget target) {
        InboxEntry entry = new InboxEntry(message, target);
        switch (target) {
            case NEXT_TURN -> nextTurnQueue.add(entry);
            case NEXT_STEP -> nextStepQueue.add(entry);
            case INJECT -> injectQueue.add(entry);
        }
        logger.debug("Inbox: queued message to {} (total: turn={}, step={}, inject={})",
            target, nextTurnQueue.size(), nextStepQueue.size(), injectQueue.size());
    }

    /**
     * Convenience: send a user message to NEXT_TURN.
     */
    public void followup(ModelMessage message) {
        send(message, InboxTarget.NEXT_TURN);
    }

    /**
     * Convenience: send a user message to NEXT_STEP (steer).
     */
    public void steer(ModelMessage message) {
        send(message, InboxTarget.NEXT_STEP);
    }

    /**
     * Convenience: send a system message to INJECT.
     */
    public void inject(ModelMessage message) {
        send(message, InboxTarget.INJECT);
    }

    /**
     * Claim all pending next-turn messages.
     */
    public List<InboxEntry> claimNextTurn() {
        List<InboxEntry> result = new ArrayList<>();
        InboxEntry entry;
        while ((entry = nextTurnQueue.poll()) != null) {
            result.add(entry);
        }
        return result;
    }

    /**
     * Claim all pending next-step messages.
     */
    public List<InboxEntry> claimNextStep() {
        List<InboxEntry> result = new ArrayList<>();
        InboxEntry entry;
        while ((entry = nextStepQueue.poll()) != null) {
            result.add(entry);
        }
        return result;
    }

    /**
     * Claim all pending inject messages.
     */
    public List<InboxEntry> claimInject() {
        List<InboxEntry> result = new ArrayList<>();
        InboxEntry entry;
        while ((entry = injectQueue.poll()) != null) {
            result.add(entry);
        }
        return result;
    }

    public boolean hasNextTurn() { return !nextTurnQueue.isEmpty(); }
    public boolean hasNextStep() { return !nextStepQueue.isEmpty(); }
    public boolean hasInject() { return !injectQueue.isEmpty(); }

    public int pendingTurn() { return nextTurnQueue.size(); }
    public int pendingStep() { return nextStepQueue.size(); }
    public int pendingInject() { return injectQueue.size(); }

    /**
     * Clear all queues.
     */
    public void clear() {
        nextTurnQueue.clear();
        nextStepQueue.clear();
        injectQueue.clear();
    }
}
