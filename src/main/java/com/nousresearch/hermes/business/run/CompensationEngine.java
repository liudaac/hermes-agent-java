package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.business.dlq.DeadLetterQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compensation framework for failed business runs.
 *
 * <p>Allows registration of compensator functions per action type.
 * When a run fails, registered compensators are invoked to undo side effects.</p>
 */
public class CompensationEngine {
    private static final Logger logger = LoggerFactory.getLogger(CompensationEngine.class);

    private final BusinessRunService runService;
    private final DeadLetterQueue deadLetterQueue;
    private final Map<String, Compensator> compensators = new ConcurrentHashMap<>();

    public CompensationEngine(BusinessRunService runService, DeadLetterQueue deadLetterQueue) {
        this.runService = runService;
        this.deadLetterQueue = deadLetterQueue;
    }

    /**
     * Register a compensator for an action type.
     */
    public void register(String actionType, Compensator compensator) {
        compensators.put(actionType, compensator);
        logger.info("Registered compensator for action type: {}", actionType);
    }

    /**
     * Compensate a failed run by invoking compensators for each failed step.
     */
    public void compensate(String workspaceId, String runId) {
        try {
            BusinessRunRecord run = runService.requireRun(workspaceId, runId);
            if (!BusinessRunService.FAILED.equals(run.getStatus())) {
                logger.debug("Run {} is not failed, skipping compensation", runId);
                return;
            }

            boolean allCompensated = true;
            for (BusinessRunStep step : run.getSteps()) {
                if (!"FAILED".equals(step.getStatus())) continue;

                String actionType = step.getMetadata() != null
                    ? String.valueOf(step.getMetadata().getOrDefault("actionType", "unknown"))
                    : "unknown";

                Compensator compensator = compensators.get(actionType);
                if (compensator != null) {
                    try {
                        CompensationResult result = compensator.compensate(run, step);
                        if (result.success()) {
                            step.setStatus("COMPENSATED");
                            step.setSummary("Compensated: " + result.message());
                            logger.info("Compensated step {} in run {}", step.getStepId(), runId);
                        } else {
                            step.setStatus("COMPENSATION_FAILED");
                            step.setSummary("Compensation failed: " + result.message());
                            allCompensated = false;
                            logger.error("Compensation failed for step {} in run {}: {}",
                                step.getStepId(), runId, result.message());
                        }
                    } catch (Exception e) {
                        step.setStatus("COMPENSATION_FAILED");
                        allCompensated = false;
                        logger.error("Compensation exception for step {} in run {}", step.getStepId(), runId, e);
                    }
                } else {
                    logger.warn("No compensator registered for action type: {}", actionType);
                    allCompensated = false;
                }
            }

            run.setUpdatedAt(java.time.Instant.now());
            runService.updateRun(run);

            if (!allCompensated) {
                deadLetterQueue.enqueue(run, "Compensation partially failed");
            }

        } catch (Exception e) {
            logger.error("Compensation engine error for run {}", runId, e);
        }
    }

    /**
     * Functional interface for compensation logic.
     */
    @FunctionalInterface
    public interface Compensator {
        CompensationResult compensate(BusinessRunRecord run, BusinessRunStep failedStep);
    }

    /**
     * Result of a compensation attempt.
     */
    public record CompensationResult(boolean success, String message) {
        public static CompensationResult ok(String message) {
            return new CompensationResult(true, message);
        }
        public static CompensationResult fail(String message) {
            return new CompensationResult(false, message);
        }
    }
}
