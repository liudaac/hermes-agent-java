package com.nousresearch.hermes.collaboration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DelegatedTaskExecutorTest {

    @Test
    void noopExecutorDoesNotExecuteOrSubmit() {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(envelope());

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "noop", DelegatedTaskExecutionPolicy.safeDefault());

        assertFalse(result.executed());
        assertFalse(result.submitted());
        assertEquals("EXTERNAL_EXECUTOR_REQUIRED", result.status());
        assertNull(result.delegatedTaskResult());
        assertNull(result.verificationResult());
        assertEquals(DelegatedTask.Status.PENDING, task.status());
        assertNull(task.result());
        assertTrue(task.verificationHistory().isEmpty());
    }

    @Test
    void blankExecutorDefaultsToNoop() {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(envelope());

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "  ", null);

        assertEquals("noop", result.executorName());
        assertFalse(result.executed());
        assertEquals(DelegatedTask.Status.PENDING, task.status());
    }

    @Test
    void mockExecutorGeneratesDeterministicResultAndSubmitsForVerification() {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(envelope());

        DelegatedTaskExecutionResult result = store.executePending(task.taskId(), "mock", DelegatedTaskExecutionPolicy.mockOnly());

        assertTrue(result.executed());
        assertTrue(result.submitted());
        assertEquals("ACCEPTED", result.status());
        assertNotNull(result.delegatedTaskResult());
        assertEquals(List.of(), result.delegatedTaskResult().changedFiles());
        assertEquals(1, result.delegatedTaskResult().testsRun().size());
        assertEquals("mock-delegated-task-executor", result.delegatedTaskResult().testsRun().get(0).name());
        assertEquals(java.time.Instant.EPOCH, result.delegatedTaskResult().submittedAt());
        assertNotNull(result.verificationResult());
        assertTrue(result.verificationResult().accepted());
        assertEquals(DelegatedTask.Status.ACCEPTED, task.status());
        assertEquals(result.delegatedTaskResult(), task.result());
        assertEquals(1, task.verificationHistory().size());
    }

    private static DelegatedTaskEnvelope envelope() {
        ContextPressureReport report = new ContextPressureReport(
            List.of("compacted"),
            0.91,
            "HIGH",
            true,
            false,
            false,
            false,
            false,
            List.of("tool output compacted")
        );
        DelegationDecision decision = new DelegationDecision(
            true,
            "context pressure",
            report,
            "release",
            "specialist"
        );
        return DelegatedTaskEnvelope.of("implement delegated executor abstraction", "run_99", decision);
    }
}
