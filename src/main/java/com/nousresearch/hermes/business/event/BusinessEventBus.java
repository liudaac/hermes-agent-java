package com.nousresearch.hermes.business.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 通用业务事件总线 — 实现 SLA、Workflow、DLQ、人机协同、Run 等模块的实时事件推送。
 *
 * <p>采用发布-订阅模式，内存级实现：
 * <ul>
 *   <li>生产者（SLAManager、DeadLetterQueue 等）调用 publish() 发送事件</li>
 *   <li>消费者（BusinessEventSSEHandler）通过 subscribe() 接收并推送到 SSE 客户端</li>
 * </ul>
 * 所有事件均为无状态结构，不持久化，纯内存流转。
 */
public class BusinessEventBus {
    private static final Logger logger = LoggerFactory.getLogger(BusinessEventBus.class);

    /** 订阅者列表，CopyOnWriteArrayList 保证并发安全且避免迭代时修改异常 */
    private final CopyOnWriteArrayList<Consumer<BusinessEvent>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * 发布事件到所有订阅者。
     * 异常隔离：单个订阅者失败不影响其他订阅者。
     */
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

    /** 订阅事件 */
    public void subscribe(Consumer<BusinessEvent> subscriber) {
        subscribers.add(subscriber);
    }

    /** 取消订阅 */
    public void unsubscribe(Consumer<BusinessEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    /** 当前订阅者数量，用于监控和调试 */
    public int subscriberCount() {
        return subscribers.size();
    }

    // ---- 便捷发布方法：按事件类型封装，减少调用方样板代码 ----

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
