package com.nousresearch.hermes.business.humanintheloop;

import com.nousresearch.hermes.business.event.BusinessEventBus;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Human-in-the-loop override service.
 *
 * <p>Allows human operators to:
 * <ul>
 *   <li>Request real-time takeover of an active agent session</li>
 *   <li>Pause agent execution and provide manual input</li>
 *   <li>Annotate/correct agent outputs for feedback loop</li>
 * </ul>
 */
public class HumanOverrideService {
    private static final Logger logger = LoggerFactory.getLogger(HumanOverrideService.class);

    private final WorkspaceService workspaceService;
    private final BusinessRunService runService;
    private final ConcurrentHashMap<String, TakeoverSession> activeTakeovers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingHumanInputs = new ConcurrentHashMap<>();
    private volatile BusinessEventBus eventBus;

    public HumanOverrideService(WorkspaceService workspaceService, BusinessRunService runService) {
        this.workspaceService = workspaceService;
        this.runService = runService;
    }

    public void setEventBus(BusinessEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Request a real-time takeover of an active agent session.
     */
    public TakeoverSession requestTakeover(String workspaceId, String runId, String operatorId) {
        workspaceService.requireWorkspace(workspaceId);
        BusinessRunRecord run = runService.requireRun(workspaceId, runId);

        String takeoverId = "tko-" + runId + "-" + operatorId;
        TakeoverSession session = new TakeoverSession(
            takeoverId, workspaceId, runId, operatorId,
            run.getTeamId(), Instant.now(), TakeoverStatus.REQUESTED, null
        );
        activeTakeovers.put(takeoverId, session);
        if (eventBus != null) {
            eventBus.takeoverRequested(workspaceId, takeoverId, runId, operatorId);
        }
        logger.info("Takeover requested: {} by operator {} for run {}", takeoverId, operatorId, runId);
        return session;
    }

    /**
     * Confirm takeover and pause the agent.
     */
    public void confirmTakeover(String takeoverId) {
        TakeoverSession session = activeTakeovers.get(takeoverId);
        if (session == null) throw new IllegalArgumentException("Unknown takeover: " + takeoverId);

        activeTakeovers.put(takeoverId, session.withStatus(TakeoverStatus.ACTIVE));
        if (eventBus != null) {
            eventBus.takeoverConfirmed(session.workspaceId(), takeoverId, session.runId());
        }
        logger.info("Takeover confirmed: {}", takeoverId);

        // Pause the run
        runService.updateRunStatus(session.workspaceId(), session.runId(), BusinessRunService.RUNNING,
            "Paused for human takeover by " + session.operatorId());
    }

    /**
     * Operator sends a message to the agent during takeover.
     */
    public void sendOperatorMessage(String takeoverId, String message) {
        TakeoverSession session = activeTakeovers.get(takeoverId);
        if (session == null || session.status() != TakeoverStatus.ACTIVE) {
            throw new IllegalStateException("Takeover not active: " + takeoverId);
        }

        CompletableFuture<String> future = pendingHumanInputs.get(session.runId());
        if (future != null && !future.isDone()) {
            future.complete(message);
        }

        logger.debug("Operator message sent to run {}: {}", session.runId(), message);
    }

    /**
     * Agent requests human input and blocks until received.
     */
    public String pauseForHumanInput(String workspaceId, String runId, String agentId, String reason) {
        workspaceService.requireWorkspace(workspaceId);

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingHumanInputs.put(runId, future);

        runService.updateRunStatus(workspaceId, runId, BusinessRunService.RUNNING,
            "Waiting for human input: " + reason);

        try {
            logger.info("Agent {} paused run {} waiting for human input: {}", agentId, runId, reason);
            return future.get(300_000, java.util.concurrent.TimeUnit.MILLISECONDS); // 5min timeout
        } catch (java.util.concurrent.TimeoutException e) {
            future.complete("TIMEOUT: No human input received within 5 minutes");
            return "TIMEOUT";
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw new RuntimeException("Human input interrupted", e);
        } finally {
            pendingHumanInputs.remove(runId);
        }
    }

    /**
     * Submit feedback/correction for an agent output.
     */
    public void submitFeedback(String workspaceId, String runId, String stepId,
                                String operatorId, String originalOutput, String correctedOutput,
                                String feedbackType) {
        logger.info("Feedback submitted for run {} step {} by {}: type={}",
            runId, stepId, operatorId, feedbackType);

        // Store feedback for future model fine-tuning or prompt improvement
        // This would be persisted to a feedback store
    }

    /**
     * Release takeover and resume normal agent execution.
     */
    public void releaseTakeover(String takeoverId) {
        TakeoverSession session = activeTakeovers.remove(takeoverId);
        if (session != null) {
            if (eventBus != null) {
                eventBus.takeoverReleased(session.workspaceId(), takeoverId, session.runId());
            }
            logger.info("Takeover released: {}", takeoverId);
        }
    }

    /**
     * List active takeovers for a workspace.
     */
    public java.util.List<TakeoverSession> listActiveTakeovers(String workspaceId) {
        return activeTakeovers.values().stream()
            .filter(t -> workspaceId == null || workspaceId.equals(t.workspaceId()))
            .filter(t -> t.status() == TakeoverStatus.ACTIVE)
            .toList();
    }

    // ---- Data classes ----

    public enum TakeoverStatus { REQUESTED, ACTIVE, RELEASED }

    public record TakeoverSession(
        String takeoverId,
        String workspaceId,
        String runId,
        String operatorId,
        String teamId,
        Instant startedAt,
        TakeoverStatus status,
        Instant endedAt
    ) {
        public TakeoverSession withStatus(TakeoverStatus newStatus) {
            return new TakeoverSession(takeoverId, workspaceId, runId, operatorId, teamId,
                startedAt, newStatus, newStatus == TakeoverStatus.RELEASED ? Instant.now() : endedAt);
        }
    }
}
