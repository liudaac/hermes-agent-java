package com.nousresearch.hermes.business.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Generic business event bus for real-time orchestration updates.
 *
 * <p>Streams events from SLA, Workflow, DLQ, Human-in-the-Loop and Run systems
 * to SSE clients and other observers.</p>
 */
public class BusinessEventBus {
    private static final Logger logger = LoggerFactory.getLogger(BusinessEventBus.class);

    private final CopyOnWriteArrayList<Consumer<BusinessEvent>> subscribers = new CopyOnWriteArrayList<>();

    public void publish(BusinessEvent event) {
        logger.debug("Publishing business event: {} / {}", event.type(), event.entityId());
        for (var sub : subscribers) {
            try {
                sub.accept(event);
            } catch (Exception e) {
                logger.warn("Business event subscriber failed for {}: {}", event.type(), e.getMessage());
            }
        }
    }

    public void subscribe(Consumer<BusinessEvent> subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Consumer<BusinessEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    // Convenience publishers

    public void workflowStatus(String workspaceId, String workflowId, String status, double progress, String currentStep) {
        publish(new BusinessEvent(
            "WORKFLOW_STATUS", workflowId, workspaceId,
            Map.of("status", status, "progress", progress, "currentStep", currentStep, "timestamp", System.currentTimeMillis())
        ));
    }

    public void workflowCheckpoint(String workspaceId, String workflowId, String stepName, String decision) {
        publish(new BusinessEvent(
            "WORKFLOW_CHECKPOINT", workflowId, workspaceId,
            Map.of("stepName", stepName, "decision", decision, "timestamp", System.currentTimeMillis())
        ));
    }

    public void slaWarn(String workspaceId, String runId, String slaName, long elapsedMs, long thresholdMs) {
        publish(new BusinessEvent(
            "SLA_WARN", runId, workspaceId,
            Map.of("slaName", slaName, "elapsedMs", elapsedMs, "thresholdMs", thresholdMs, "timestamp", System.currentTimeMillis())
        ));
    }

    public void slaBreach(String workspaceId, String runId, String slaName, String action) {
        publish(new BusinessEvent(
            "SLA_BREACH", runId, workspaceId,
            Map.of("slaName", slaName, "action", action, "timestamp", System.currentTimeMillis())
        ));
    }

    public void dlqEnqueue(String workspaceId, String itemId, String runId, String reason) {
        publish(new BusinessEvent(
            "DLQ_ENQUEUE", itemId, workspaceId,
            Map.of("runId", runId, "reason", reason, "timestamp", System.currentTimeMillis())
        ));
    }

    public void dlqStatusChange(String workspaceId, String itemId, String newStatus) {
        publish(new BusinessEvent(
            "DLQ_STATUS_CHANGE", itemId, workspaceId,
            Map.of("status", newStatus, "timestamp", System.currentTimeMillis())
        ));
    }

    public void takeoverRequested(String workspaceId, String takeoverId, String runId, String operatorId) {
        publish(new BusinessEvent(
            "TAKEOVER_REQUESTED", takeoverId, workspaceId,
            Map.of("runId", runId, "operatorId", operatorId, "timestamp", System.currentTimeMillis())
        ));
    }

    public void takeoverConfirmed(String workspaceId, String takeoverId, String runId) {
        publish(new BusinessEvent(
            "TAKEOVER_CONFIRMED", takeoverId, workspaceId,
            Map.of("runId", runId, "timestamp", System.currentTimeMillis())
        ));
    }

    public void takeoverReleased(String workspaceId, String takeoverId, String runId) {
        publish(new BusinessEvent(
            "TAKEOVER_RELEASED", takeoverId, workspaceId,
            Map.of("runId", runId, "timestamp", System.currentTimeMillis())
        ));
    }

    public void runStatus(String workspaceId, String runId, String status, String message) {
        publish(new BusinessEvent(
            "RUN_STATUS", runId, workspaceId,
            Map.of("status", status, "message", message, "timestamp", System.currentTimeMillis())
        ));
    }

    public record BusinessEvent(
        String type,
        String entityId,
        String workspaceId,
        Map<String, Object> data
    ) {}
}
