package com.nousresearch.hermes.business.sla;

import com.nousresearch.hermes.business.event.BusinessEventBus;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * SLA（服务级别协议）管理器 — 监控业务运行的时效性并自动处理违约。
 *
 * <p>电商物流场景对时效敏感，SLAManager 为每个 BusinessRun 附加时间监控：
 * <ul>
 *   <li>预警阈值：接近时限时发送告警（如客服响应 4 分钟）</li>
 *   <li>违约阈值：超限时自动执行预设动作（重试/升级/取消）</li>
 *   <li>事件推送：通过 BusinessEventBus 实时通知前端 SSE 客户端</li>
 * </ul>
 */
public class SLAManager {
    private static final Logger logger = LoggerFactory.getLogger(SLAManager.class);

    private final BusinessRunService runService;
    private final BusinessApprovalService approvalService;
    /** 定时调度线程池，专门用于 SLA 检查 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        4, r -> {
            Thread t = new Thread(r, "sla-monitor");
            t.setDaemon(true); // 守护线程，不阻止 JVM 退出
            return t;
        });

    /** 当前活跃的 SLA 监控器，key 为 runId */
    private final ConcurrentHashMap<String, SLAMonitor> activeMonitors = new ConcurrentHashMap<>();
    /** 业务事件总线，用于推送 SLA 告警/违约事件到 SSE 客户端 */
    private volatile BusinessEventBus eventBus;

    public SLAManager(BusinessRunService runService, BusinessApprovalService approvalService) {
        this.runService = runService;
        this.approvalService = approvalService;
    }

    public void setEventBus(BusinessEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 为一次业务运行附加 SLA 监控。
     *
     * <p>调度两个定时任务：
     * <ul>
     *   <li>warn 检查：到达 warnThresholdMs 时触发预警</li>
     *   <li>breach 检查：到达 breachThresholdMs 时触发违约处理</li>
     * </ul>
     * 若运行提前完成（COMPLETED/FAILED/NEEDS_APPROVAL），定时器自动取消。
     *
     * @param runId    业务运行 ID
     * @param sla      SLA 定义（阈值 + 违约动作）
     * @param context  额外上下文（workspaceId、teamId 等，用于升级和事件推送）
     */
    public void attachSLA(String runId, SLADefinition sla, Map<String, String> context) {
        if (sla == null || runId == null) return;

        // Cancel any existing monitor for this run
        SLAMonitor existing = activeMonitors.remove(runId);
        if (existing != null) existing.cancel();

        SLAMonitor monitor = new SLAMonitor(runId, sla, context);
        activeMonitors.put(runId, monitor);

        // Schedule warn check
        if (sla.getWarnThresholdMs() > 0) {
            monitor.warnFuture = scheduler.schedule(
                () -> checkWarn(runId, sla, context),
                sla.getWarnThresholdMs(),
                TimeUnit.MILLISECONDS
            );
        }

        // Schedule breach check
        if (sla.getBreachThresholdMs() > 0) {
            monitor.breachFuture = scheduler.schedule(
                () -> checkBreach(runId, sla, context),
                sla.getBreachThresholdMs(),
                TimeUnit.MILLISECONDS
            );
        }

        logger.info("SLA attached to run {}: warn={}ms, breach={}ms, action={}",
            runId, sla.getWarnThresholdMs(), sla.getBreachThresholdMs(), sla.getActionOnBreach());
    }

    /**
     * 从运行中移除 SLA 监控（例如运行完成时调用）。
     */
    public void detachSLA(String runId) {
        SLAMonitor monitor = activeMonitors.remove(runId);
        if (monitor != null) {
            monitor.cancel();
            logger.debug("SLA detached from run {}", runId);
        }
    }

    /**
     * 关闭所有监控器。应用关闭时调用。
     */
    public void shutdown() {
        for (SLAMonitor monitor : activeMonitors.values()) {
            monitor.cancel();
        }
        activeMonitors.clear();
        scheduler.shutdown();
    }

    // ---- 定时检查逻辑 ----

    /**
     * 预警检查 — 到达 warnThresholdMs 时触发。
     * 若运行已结束则直接清理；否则通过事件总线推送预警。
     */
    private void checkWarn(String runId, SLADefinition sla, Map<String, String> context) {
        try {
            BusinessRunRecord run = runService.requireRun(context.get("workspaceId"), runId);
            if (isTerminal(run)) {
                detachSLA(runId);
                return;
            }

            logger.warn("SLA warn threshold reached for run {}: {}ms elapsed", runId, sla.getWarnThresholdMs());

            // 推送预警事件，前端 SSE 可实时收到并显示黄色警告
            if (eventBus != null) {
                eventBus.slaWarn(context.get("workspaceId"), runId, sla.getName(),
                    sla.getWarnThresholdMs(), sla.getBreachThresholdMs());
            }

            // Publish warn event via run event bus
            runService.publishEvent(new com.nousresearch.hermes.business.run.RunEventBus.RunEvent(
                runId, context.get("workspaceId"), com.nousresearch.hermes.business.run.RunEventBus.EventType.STEP_UPDATED,
                "SLA warn: approaching time limit",
                Map.of("slaWarn", true, "thresholdMs", sla.getWarnThresholdMs(), "action", "monitor")
            ));

        } catch (Exception e) {
            logger.warn("SLA warn check failed for run {}: {}", runId, e.getMessage());
        }
    }

    private void checkBreach(String runId, SLADefinition sla, Map<String, String> context) {
        try {
            String workspaceId = context.get("workspaceId");
            BusinessRunRecord run = runService.requireRun(workspaceId, runId);
            if (isTerminal(run)) {
                detachSLA(runId);
                return;
            }

            logger.error("SLA BREACHED for run {}: {}ms exceeded, action={}",
                runId, sla.getBreachThresholdMs(), sla.getActionOnBreach());

            // 推送违约事件，前端 SSE 收到后自动刷新并显示红色告警
            if (eventBus != null) {
                eventBus.slaBreach(context.get("workspaceId"), runId, sla.getName(), sla.getActionOnBreach());
            }

            switch (sla.getActionOnBreach()) {
                case "auto_retry" -> autoRetry(run, sla, context);
                case "escalate" -> escalate(run, sla, context);
                case "cancel" -> cancel(run, context);
                default -> escalate(run, sla, context); // 默认动作：升级
            }

            // 标记运行失败
            runService.updateRunStatus(workspaceId, runId, BusinessRunService.FAILED,
                "SLA breached: exceeded " + sla.getBreachThresholdMs() + "ms limit");

        } catch (Exception e) {
            logger.error("SLA breach handling failed for run {}: {}", runId, e.getMessage());
        } finally {
            detachSLA(runId);
        }
    }

    // ---- 违约动作 ----

    /** 自动重试：触发新运行（实际实现中会重新执行 Scenario） */
    private void autoRetry(BusinessRunRecord run, SLADefinition sla, Map<String, String> context) {
        logger.info("Auto-retrying run {} due to SLA breach", run.getRunId());
        runService.publishEvent(new com.nousresearch.hermes.business.run.RunEventBus.RunEvent(
            run.getRunId(), context.get("workspaceId"), com.nousresearch.hermes.business.run.RunEventBus.EventType.RUN_FAILED,
            "Auto-retry triggered by SLA breach",
            Map.of("retry", true, "originalRunId", run.getRunId())
        ));
    }

    /** 升级：创建审批单，通知 escalationTarget（如 ops-manager） */
    private void escalate(BusinessRunRecord run, SLADefinition sla, Map<String, String> context) {
        String escalationTarget = sla.getEscalationTarget();
        if (escalationTarget == null || escalationTarget.isBlank()) {
            escalationTarget = "business-admin";
        }

        approvalService.createApproval(
            context.get("workspaceId"),
            run.getTeamId(),
            "SLA Breach Escalation: " + run.getTaskTitle(),
            "Run " + run.getRunId() + " exceeded SLA limit of " + sla.getBreachThresholdMs() + "ms",
            "Please review and decide on remediation",
            "Continue execution with extended deadline",
            "Cancel the run and notify stakeholders",
            "Escalate to team lead for manual handling",
            "HIGH",
            Map.of("runId", run.getRunId(), "slaName", sla.getName(), "breachMs", sla.getBreachThresholdMs()),
            Map.of("source", "sla_manager", "escalationTarget", escalationTarget)
        );

        logger.info("SLA breach escalated for run {} to {}", run.getRunId(), escalationTarget);
    }

    /** 取消运行 */
    private void cancel(BusinessRunRecord run, Map<String, String> context) {
        logger.info("Cancelling run {} due to SLA breach", run.getRunId());
        runService.updateRunStatus(context.get("workspaceId"), run.getRunId(), BusinessRunService.FAILED,
            "Cancelled due to SLA breach");
    }

    /** 判断运行是否已结束（无需继续监控） */
    private boolean isTerminal(BusinessRunRecord run) {
        return BusinessRunService.COMPLETED.equals(run.getStatus())
            || BusinessRunService.FAILED.equals(run.getStatus())
            || BusinessRunService.NEEDS_APPROVAL.equals(run.getStatus());
    }

    // ---- 内部类：SLA 监控器 ----

    /**
     * 单个运行的 SLA 监控状态，持有 warn/breach 两个定时 Future。
     * 运行结束或 SLA 被移除时调用 cancel() 取消定时器。
     */
    private static class SLAMonitor {
        final String runId;
        final SLADefinition sla;
        final Map<String, String> context;
        volatile ScheduledFuture<?> warnFuture;
        volatile ScheduledFuture<?> breachFuture;

        SLAMonitor(String runId, SLADefinition sla, Map<String, String> context) {
            this.runId = runId;
            this.sla = sla;
            this.context = context;
        }

        void cancel() {
            if (warnFuture != null) warnFuture.cancel(false);
            if (breachFuture != null) breachFuture.cancel(false);
        }
    }
}
