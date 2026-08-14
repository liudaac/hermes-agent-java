package com.nousresearch.hermes.harness.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs maintenance jobs when the agent is idle.
 * Jobs are sorted by priority. If a new message arrives (agent becomes busy),
 * the current job is allowed to finish but no new jobs are started.
 */
public class MaintenanceScheduler {
    private static final Logger logger = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final List<MaintenanceJob> jobs = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean interrupted = false;

    public void register(MaintenanceJob job) {
        jobs.add(job);
        jobs.sort(Comparator.comparingInt(MaintenanceJob::priority));
    }

    public boolean unregister(String name) {
        return jobs.removeIf(j -> j.name().equals(name));
    }

    public List<MaintenanceJob> jobs() {
        return new ArrayList<>(jobs);
    }

    /**
     * Run all maintenance jobs. Returns true if all completed, false if interrupted.
     * This method is synchronous - the caller should run it in a background thread
     * if needed.
     */
    public boolean runAll() {
        if (!running.compareAndSet(false, true)) {
            logger.debug("Maintenance already running, skipping");
            return false;
        }

        interrupted = false;
        boolean allCompleted = true;

        try {
            List<MaintenanceJob> snapshot = new ArrayList<>(jobs);
            for (MaintenanceJob job : snapshot) {
                if (interrupted) {
                    logger.info("Maintenance interrupted at job: {} (new message arrived)", job.name());
                    allCompleted = false;
                    break;
                }

                try {
                    logger.debug("Running maintenance job: {} (priority={})", job.name(), job.priority());
                    job.run();
                    logger.debug("Maintenance job completed: {}", job.name());
                } catch (Exception e) {
                    logger.warn("Maintenance job '{}' failed: {}", job.name(), e.getMessage());
                    // Continue to next job - one failure shouldn't block others
                }
            }
        } finally {
            running.set(false);
        }

        return allCompleted;
    }

    /**
     * Signal that a new message has arrived and maintenance should stop.
     * The current job will finish, but no new jobs will start.
     */
    public void interrupt() {
        interrupted = true;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void clear() {
        jobs.clear();
    }
}
