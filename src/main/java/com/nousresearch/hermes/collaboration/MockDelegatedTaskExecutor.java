package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic test executor. It does not run commands or change files.
 */
public class MockDelegatedTaskExecutor implements DelegatedTaskExecutor {
    public static final String NAME = "mock";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DelegatedTaskExecutionResult execute(DelegatedTask task, DelegatedTaskExecutionPolicy policy) {
        DelegatedTaskExecutionPolicy effective = policy != null ? policy : DelegatedTaskExecutionPolicy.mockOnly();
        if (task == null) {
            return DelegatedTaskExecutionResult.notExecuted(name(), "INVALID_TASK", "No delegated task was provided", effective);
        }
        DelegatedTaskResult result = new DelegatedTaskResult(
            "Mock executor completed delegated task " + task.taskId() + " for intent: " + safe(task.envelope().intent()),
            List.of(),
            List.of(DelegatedTaskResult.TestRun.passed("mock-delegated-task-executor")),
            List.of("Mock execution only; no files changed and no commands run"),
            Instant.EPOCH
        );
        return DelegatedTaskExecutionResult.executed(
            name(),
            "Mock delegated task result generated without external execution",
            result,
            effective
        );
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(unspecified)" : value;
    }
}
