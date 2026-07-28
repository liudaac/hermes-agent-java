package com.nousresearch.hermes.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DelegatedTaskLifecycleTest {

    @Test
    void createsPendingTaskFromEnvelope() {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTaskEnvelope envelope = envelope();

        DelegatedTask task = store.createPending(envelope);

        assertNotNull(task.taskId());
        assertEquals(DelegatedTask.Status.PENDING, task.status());
        assertEquals(envelope.intent(), task.envelope().intent());
        assertEquals(1, store.list().size());
        assertEquals(task, store.get(task.taskId()));
    }

    @Test
    void specialistSubmitResultAcceptedWhenTestsPass() {
        DelegatedTaskStore store = new DelegatedTaskStore();
        DelegatedTask task = store.createPending(
            envelope(),
            ParentVerificationPolicy.allowChangedFilesUnder(List.of("src/main/java", "src/test/java"))
        );

        ParentVerificationResult verification = store.submitResult(task.taskId(), DelegatedTaskResult.of(
            "Implemented delegation lifecycle foundation",
            List.of("src/main/java/com/nousresearch/hermes/collaboration/DelegatedTask.java"),
            List.of(DelegatedTaskResult.TestRun.passed("mvn -Dtest=DelegatedTaskLifecycleTest test")),
            List.of("No real subprocess execution is integrated")
        ));

        assertTrue(verification.accepted());
        assertEquals(DelegatedTask.Status.ACCEPTED, task.status());
        assertNotNull(task.result());
        assertTrue(task.result().allTestsPassed());
        assertEquals(1, task.verificationHistory().size());
        assertTrue(task.verificationHistory().get(0).result().accepted());
    }

    @Test
    void multipleVerificationsAppendImmutableHistoryAndUpdateLatestStatus() {
        DelegatedTask task = new DelegatedTaskStore().createPending(envelope());
        task.submitResult(DelegatedTaskResult.of(
            "Changed source without test",
            List.of("src/main/java/Example.java"),
            List.of(),
            List.of()
        ));

        assertEquals(DelegatedTask.Status.REJECTED, task.status());
        assertFalse(task.verification().accepted());
        assertEquals(1, task.verificationHistory().size());
        assertFalse(task.verificationHistory().get(0).result().accepted());

        ParentVerificationResult accepted = task.verifyWithPolicy(new ParentVerificationPolicy(false, false, List.of()));

        assertTrue(accepted.accepted());
        assertEquals(DelegatedTask.Status.ACCEPTED, task.status());
        assertTrue(task.verification().accepted());
        assertEquals(2, task.verificationHistory().size());
        assertFalse(task.verificationHistory().get(0).result().accepted());
        assertTrue(task.verificationHistory().get(1).result().accepted());
    }

    private static DelegatedTaskEnvelope envelope() {
        ContextPressureReport report = new ContextPressureReport(
            List.of("compacted", "critical_path"),
            0.95,
            "CRITICAL",
            true,
            true,
            false,
            false,
            false,
            List.of("tool output compacted", "critical path change")
        );
        DelegationDecision decision = new DelegationDecision(
            true,
            "tool output compacted; critical path change",
            report,
            "release",
            "critical-path-reviewer"
        );
        return DelegatedTaskEnvelope.of("ship release safely", "run_42", decision);
    }
}
