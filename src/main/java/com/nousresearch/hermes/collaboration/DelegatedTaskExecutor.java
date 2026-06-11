package com.nousresearch.hermes.collaboration;

/**
 * Adapter boundary for delegated-task execution.
 *
 * <p>Implementations must be safe by default: returning a not-executed result
 * is preferred over launching external processes without an explicit future
 * integration contract. This interface models lifecycle handoff only.</p>
 */
public interface DelegatedTaskExecutor {
    String name();

    DelegatedTaskExecutionResult execute(DelegatedTask task, DelegatedTaskExecutionPolicy policy);
}
