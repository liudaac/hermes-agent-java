package com.nousresearch.hermes.org.handoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Centralized handoff protocol manager for human-agent interactions.
 *
 * <p>Handles the full lifecycle of handoffs:</p>
 * <ol>
 *   <li>Agent creates a handoff when it reaches a boundary</li>
 *   <li>Handoff is routed to the appropriate reviewer based on priority and target</li>
 *   <li>If not acknowledged within SLA, escalation triggers</li>
 *   <li>Resolution is fed back to the source agent to resume</li>
 * </ol>
 */
public class HandoffProtocol implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(HandoffProtocol.class);

    /** All active and recent handoffs. */
    private final ConcurrentHashMap<String, HandoffContext> handoffs = new ConcurrentHashMap<>();

    /** Handoffs pending review, ordered by priority and age. */
    private final PriorityBlockingQueue<PrioritizedHandoff> pendingQueue = new PriorityBlockingQueue<>();

    /** Callback invoked when a handoff needs a reviewer notified. */
    private Consumer<HandoffContext> notificationHandler;

    /** Callback invoked when a handoff is resolved. */
    private Consumer<HandoffContext.HandoffResolution> resolutionHandler;

    /** SLA monitor thread. */
    private final ScheduledExecutorService slaMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "handoff-sla-monitor");
        t.setDaemon(true);
        return t;
    });

    /** History for resolved/timed-out handoffs (max size). */
    private final ConcurrentLinkedDeque<HandoffContext> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 500;

    private volatile boolean running = false;

    // SLA thresholds by priority (seconds)
    private static final Map<HandoffContext.Priority, Long> SLA_THRESHOLDS = Map.of(
        HandoffContext.Priority.CRITICAL, 300L,   // 5 min
        HandoffContext.Priority.HIGH,     900L,   // 15 min
        HandoffContext.Priority.NORMAL,   3600L,  // 1 hour
        HandoffContext.Priority.LOW,      14400L  // 4 hours
    );

    /**
     * Create a new handoff and begin tracking it.
     */
    public HandoffContext createHandoff(HandoffContext ctx) {
        handoffs.put(ctx.getHandoffId(), ctx);
        pendingQueue.add(new PrioritizedHandoff(ctx));

        logger.info("Handoff created: {} from {} [{}] — {}",
            ctx.getHandoffId(), ctx.getSourceAgentId(), ctx.getPriority(), ctx.getSummary());

        // Notify reviewer immediately
        if (notificationHandler != null) {
            try {
                notificationHandler.accept(ctx);
            } catch (Exception e) {
                logger.error("Notification handler error for handoff {}", ctx.getHandoffId(), e);
            }
        }

        return ctx;
    }

    /**
     * Convenience: create a simple approval-style handoff.
     */
    public HandoffContext requestApproval(
            String agentId, String summary, String situation,
            String targetReviewer, long maxWaitSeconds) {
        return createHandoff(new HandoffContext.Builder(agentId, summary, situation)
            .addOption("approve", "Approve", "Proceed with the proposed action")
            .addOption("reject", "Reject", "Do not proceed", "Action will be cancelled")
            .addOption("modify", "Request Changes", "Ask agent to modify the approach")
            .targetReviewer(targetReviewer)
            .maxWaitSeconds(maxWaitSeconds)
            .build());
    }

    /**
     * Acknowledge that a human reviewer has seen the handoff.
     */
    public void acknowledge(String handoffId, String reviewer) {
        HandoffContext ctx = handoffs.get(handoffId);
        if (ctx == null) throw new IllegalArgumentException("Unknown handoff: " + handoffId);
        ctx.acknowledge(reviewer);
        pendingQueue.removeIf(p -> p.context.getHandoffId().equals(handoffId));
        logger.info("Handoff {} acknowledged by {}", handoffId, reviewer);
    }

    /**
     * Resolve a handoff with a human decision.
     */
    public HandoffContext.HandoffResolution resolve(String handoffId, String reviewer, String option, String note) {
        HandoffContext ctx = handoffs.get(handoffId);
        if (ctx == null) throw new IllegalArgumentException("Unknown handoff: " + handoffId);

        HandoffContext.HandoffResolution resolution = ctx.resolve(reviewer, option, note);

        // Move to history
        history.addFirst(ctx);
        while (history.size() > MAX_HISTORY) history.pollLast();

        // Notify resolution handler
        if (resolutionHandler != null) {
            try {
                resolutionHandler.accept(resolution);
            } catch (Exception e) {
                logger.error("Resolution handler error for handoff {}", handoffId, e);
            }
        }

        logger.info("Handoff {} resolved by {}: option={}", handoffId, reviewer, option);
        return resolution;
    }

    /**
     * Get pending handoffs for a specific reviewer.
     */
    public List<HandoffContext> getPendingFor(String reviewer) {
        return handoffs.values().stream()
            .filter(h -> h.getStatus() == HandoffContext.Status.PENDING
                || h.getStatus() == HandoffContext.Status.ACKNOWLEDGED)
            .filter(h -> h.getTargetReviewer() == null
                || h.getTargetReviewer().equals(reviewer))
            .sorted(Comparator.comparing(HandoffContext::getPriority).reversed()
                .thenComparing(HandoffContext::getCreatedAt))
            .toList();
    }

    /**
     * Get all currently pending handoffs.
     */
    public List<HandoffContext> getAllPending() {
        return handoffs.values().stream()
            .filter(h -> h.getStatus() == HandoffContext.Status.PENDING
                || h.getStatus() == HandoffContext.Status.ACKNOWLEDGED)
            .sorted(Comparator.comparing(HandoffContext::getPriority).reversed()
                .thenComparing(HandoffContext::getCreatedAt))
            .toList();
    }

    /**
     * Get a specific handoff by ID.
     */
    public Optional<HandoffContext> getHandoff(String handoffId) {
        return Optional.ofNullable(handoffs.get(handoffId));
    }

    /**
     * Summary statistics for dashboard.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("pending", getAllPending().size());
        s.put("acknowledged", handoffs.values().stream()
            .filter(h -> h.getStatus() == HandoffContext.Status.ACKNOWLEDGED).count());
        s.put("escalated", handoffs.values().stream()
            .filter(h -> h.getStatus() == HandoffContext.Status.ESCALATED).count());
        s.put("overdue", handoffs.values().stream()
            .filter(h -> h.getStatus() != HandoffContext.Status.RESOLVED && h.getStatus() != HandoffContext.Status.CANCELLED)
            .filter(HandoffContext::isOverdue).count());
        s.put("total_resolved", history.size());
        s.put("by_priority", Map.of(
            "critical", handoffs.values().stream().filter(h -> h.getPriority() == HandoffContext.Priority.CRITICAL).count(),
            "high", handoffs.values().stream().filter(h -> h.getPriority() == HandoffContext.Priority.HIGH).count(),
            "normal", handoffs.values().stream().filter(h -> h.getPriority() == HandoffContext.Priority.NORMAL).count(),
            "low", handoffs.values().stream().filter(h -> h.getPriority() == HandoffContext.Priority.LOW).count()
        ));
        return s;
    }

    /** Average resolution time for the last N handoffs. */
    public Duration getAverageResolutionTime(int lastN) {
        long sumMs = history.stream()
            .limit(lastN)
            .filter(h -> h.getResolvedAt() != null)
            .mapToLong(h -> Duration.between(h.getCreatedAt(), h.getResolvedAt()).toMillis())
            .sum();
        long count = history.stream()
            .limit(lastN)
            .filter(h -> h.getResolvedAt() != null)
            .count();
        return count > 0 ? Duration.ofMillis(sumMs / count) : Duration.ZERO;
    }

    // ---- lifecycle ----

    public synchronized void start() {
        if (running) return;
        running = true;
        slaMonitor.scheduleAtFixedRate(this::checkSla, 30, 30, TimeUnit.SECONDS);
        logger.info("HandoffProtocol started");
    }

    @Override
    public void close() {
        running = false;
        slaMonitor.shutdown();
        logger.info("HandoffProtocol stopped");
    }

    // ---- SLA monitoring ----

    private void checkSla() {
        for (HandoffContext ctx : handoffs.values()) {
            if (ctx.getStatus() == HandoffContext.Status.RESOLVED
                || ctx.getStatus() == HandoffContext.Status.CANCELLED
                || ctx.getStatus() == HandoffContext.Status.ESCALATED
                || ctx.getStatus() == HandoffContext.Status.TIMED_OUT) continue;

            long remaining = ctx.getRemainingSeconds();
            long slaThreshold = SLA_THRESHOLDS.getOrDefault(ctx.getPriority(), 3600L);

            if (remaining < -1800) {
                // >30 min overdue — escalate
                ctx.markEscalated();
                logger.warn("Handoff {} escalated ({}s overdue)", ctx.getHandoffId(), -remaining);
                if (!ctx.getEscalationChain().isEmpty()) {
                    String nextReviewer = ctx.getEscalationChain().get(0);
                    logger.info("Handoff {} escalated to {}", ctx.getHandoffId(), nextReviewer);
                }
            } else if (remaining < 0 && ctx.getStatus() != HandoffContext.Status.ESCALATED) {
                ctx.markTimeout();
                logger.warn("Handoff {} timed out", ctx.getHandoffId());
            }
        }
    }

    // ---- handlers ----

    public void setNotificationHandler(Consumer<HandoffContext> handler) { this.notificationHandler = handler; }
    public void setResolutionHandler(Consumer<HandoffContext.HandoffResolution> handler) { this.resolutionHandler = handler; }

    // ---- internal ----

    /** Wrapper for priority queue ordering. */
    private record PrioritizedHandoff(HandoffContext context) implements Comparable<PrioritizedHandoff> {
        @Override
        public int compareTo(PrioritizedHandoff o) {
            int p = o.context.getPriority().compareTo(context.getPriority());
            return p != 0 ? p : context.getCreatedAt().compareTo(o.context.getCreatedAt());
        }
    }
}
