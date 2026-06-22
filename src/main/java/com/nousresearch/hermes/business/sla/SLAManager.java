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
 * SLA (Service Level Agreement) manager for business runs.
 *
 * <p>Attaches to BusinessRunRecords and monitors execution against defined thresholds:
 * <ul>
 *   <li>Warn threshold: notify when approaching limit</li>
 *   <li>Breach threshold: escalate when limit exceeded</li>
 *   <li>Auto-actions: retry, escalate, or cancel on breach</li>
 * </ul>
 */
public class SLAManager {
    private static final Logger logger = LoggerFactory.getLogger(SLAManager.class);

    private final BusinessRunService runService;
    private final BusinessApprovalService approvalService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        4, r -> {
            Thread t = new Thread(r, "sla-monitor");
            t.setDaemon(true);
            return t;
        });

    /** Active SLA monitors keyed by runId */
    private final ConcurrentHashMap<String, SLAMonitor> activeMonitors = new ConcurrentHashMap<>();
    private volatile BusinessEventBus eventBus;

    public SLAManager(BusinessRunService runService, BusinessApprovalService approvalService) {
        this.runService = runService;
        this.approvalService = approvalService;
    }

    public void setEventBus(BusinessEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Attach an SLA definition to a run.
     *
     * @param runId    the business run id
     * @param sla      the SLA definition
     * @param context  additional context for escalation (workspaceId, teamId, etc.)
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
     * Detach SLA monitoring from a run (e.g., when run completes).
     */
    public void detachSLA(String runId) {
        SLAMonitor monitor = activeMonitors.remove(runId);
        if (monitor != null) {
            monitor.cancel();
            logger.debug("SLA detached from run {}", runId);
        }
    }

    /**
     * Detach all monitors. Call on shutdown.
     */
    public void shutdown() {
        for (SLAMonitor monitor : activeMonitors.values()) {
            monitor.cancel();
        }
        activeMonitors.clear();
        scheduler.shutdown();
    }

    // ---- Checks ----

    private void checkWarn(String runId, SLADefinition sla, Map<String, String> context) {
        try {
            BusinessRunRecord run = runService.requireRun(context.get("workspaceId"), runId);
            if (isTerminal(run)) {
                detachSLA(runId);
                return;
            }

            logger.warn("SLA warn threshold reached for run {}: {}ms elapsed", runId, sla.getWarnThresholdMs());

            // Publish warn event
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

            if (eventBus != null) {
                eventBus.slaBreach(context.get("workspaceId"), runId, sla.getName(), sla.getActionOnBreach());
            }

            switch (sla.getActionOnBreach()) {
                case "auto_retry" -> autoRetry(run, sla, context);
                case "escalate" -> escalate(run, sla, context);
                case "cancel" -> cancel(run, context);
                default -> escalate(run, sla, context); // default: escalate
            }

            // Publish breach event
            runService.updateRunStatus(workspaceId, runId, BusinessRunService.FAILED,
                "SLA breached: exceeded " + sla.getBreachThresholdMs() + "ms limit");

        } catch (Exception e) {
            logger.error("SLA breach handling failed for run {}: {}", runId, e.getMessage());
        } finally {
            detachSLA(runId);
        }
    }

    // ---- Actions ----

    private void autoRetry(BusinessRunRecord run, SLADefinition sla, Map<String, String> context) {
        logger.info("Auto-retrying run {} due to SLA breach", run.getRunId());
        // Create a new run with same parameters
        // In practice, this would trigger the scenario again
        runService.publishEvent(new com.nousresearch.hermes.business.run.RunEventBus.RunEvent(
            run.getRunId(), context.get("workspaceId"), com.nousresearch.hermes.business.run.RunEventBus.EventType.RUN_FAILED,
            "Auto-retry triggered by SLA breach",
            Map.of("retry", true, "originalRunId", run.getRunId())
        ));
    }

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

    private void cancel(BusinessRunRecord run, Map<String, String> context) {
        logger.info("Cancelling run {} due to SLA breach", run.getRunId());
        runService.updateRunStatus(context.get("workspaceId"), run.getRunId(), BusinessRunService.FAILED,
            "Cancelled due to SLA breach");
    }

    private boolean isTerminal(BusinessRunRecord run) {
        return BusinessRunService.COMPLETED.equals(run.getStatus())
            || BusinessRunService.FAILED.equals(run.getStatus())
            || BusinessRunService.NEEDS_APPROVAL.equals(run.getStatus());
    }

    // ---- Inner classes ----

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
