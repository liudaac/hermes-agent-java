package com.nousresearch.hermes.harness.maintenance;

/**
 * A background job that runs when the agent is idle.
 */
public interface MaintenanceJob {
    /** Unique name */
    String name();
    /** Priority - lower runs first */
    int priority();
    /** Execute the job. Called when agent is idle. */
    void run();
}
