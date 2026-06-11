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
    }

    @Test
    void rejectsFailedTests() {
        DelegatedTask task = new DelegatedTaskStore().createPending(envelope());

        ParentVerificationResult verification = task.submitResult(DelegatedTaskResult.of(
            "Tried implementation",
            List.of("src/main/java/Example.java"),
            List.of(DelegatedTaskResult.TestRun.failed("mvn test", "one assertion failed")),
            List.of()
        ));

        assertFalse(verification.accepted());
        assertEquals(DelegatedTask.Status.REJECTED, task.status());
        assertTrue(verification.reasons().stream().anyMatch(r -> r.contains("test failed: mvn test")));
    }

    @Test
    void rejectsDisallowedChangedFilePathWhenConstraintsExist() {
        DelegatedTask task = new DelegatedTaskStore().createPending(
            envelope(),
            ParentVerificationPolicy.allowChangedFilesUnder(List.of("src/main/java"))
        );

        ParentVerificationResult verification = task.submitResult(DelegatedTaskResult.of(
            "Updated docs unexpectedly",
            List.of("docs/architecture.md"),
            List.of(DelegatedTaskResult.TestRun.passed("mvn test")),
            List.of()
        ));

        assertFalse(verification.accepted());
        assertEquals(DelegatedTask.Status.REJECTED, task.status());
        assertTrue(verification.reasons().stream().anyMatch(r -> r.contains("outside allowed paths")));
    }

    @Test
    void serializesToMapAndJsonRoundTrips() throws Exception {
        DelegatedTask task = new DelegatedTaskStore().createPending(
            envelope(),
            ParentVerificationPolicy.allowChangedFilesUnder(List.of("src/main/java"))
        );
        task.submitResult(DelegatedTaskResult.of(
            "Done",
            List.of("src/main/java/com/nousresearch/hermes/collaboration/DelegatedTaskStore.java"),
            List.of(DelegatedTaskResult.TestRun.passed("mvn test")),
            List.of("Keep as simulation only")
        ));

        Map<String, Object> map = task.toMap();
        assertEquals("ACCEPTED", map.get("status"));
        assertNotNull(map.get("envelope"));
        assertNotNull(map.get("result"));
        assertNotNull(map.get("verification"));

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(map), Map.class);
        DelegatedTask restored = DelegatedTask.fromMap(decoded);

        assertEquals(task.taskId(), restored.taskId());
        assertEquals(DelegatedTask.Status.ACCEPTED, restored.status());
        assertEquals("Done", restored.result().summary());
        assertTrue(restored.verification().accepted());
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
