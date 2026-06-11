package com.nousresearch.hermes.collaboration;

/**
 * Default-safe executor that never executes or mutates anything.
 */
public class NoopDelegatedTaskExecutor implements DelegatedTaskExecutor {
    public static final String NAME = "noop";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DelegatedTaskExecutionResult execute(DelegatedTask task, DelegatedTaskExecutionPolicy policy) {
        return DelegatedTaskExecutionResult.notExecuted(
            name(),
            "EXTERNAL_EXECUTOR_REQUIRED",
            "Delegated task was not executed: configure an explicit delegated-task executor to perform specialist work.",
            policy != null ? policy : DelegatedTaskExecutionPolicy.safeDefault()
        );
    }
}
