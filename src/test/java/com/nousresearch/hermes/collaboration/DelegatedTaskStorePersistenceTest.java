package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DelegatedTaskStorePersistenceTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void tenantDelegatedTaskStorePersistsAndReloadsWithVerificationHistory() {
        TenantProvisioningRequest request = new TenantProvisioningRequest()
            .setTenantId("delegation-persistence")
            .withDefaultQuota()
            .withDefaultSecurityPolicy();
        TenantContext tenant = TenantContext.create("delegation-persistence", request);
        DelegatedTask task = tenant.getDelegatedTaskStore().createPending(envelope());
        tenant.getDelegatedTaskStore().submitResult(task.taskId(), DelegatedTaskResult.of(
            "No tests yet",
            List.of("src/main/java/Example.java"),
            List.of(),
            List.of()
        ));
        tenant.getDelegatedTaskStore().verify(task.taskId(), new ParentVerificationPolicy(false, false, List.of()));

        TenantContext reloaded = TenantContext.load("delegation-persistence");
        DelegatedTask restored = reloaded.getDelegatedTaskStore().get(task.taskId());

        assertNotNull(restored);
        assertEquals(task.taskId(), restored.taskId());
        assertEquals(DelegatedTask.Status.ACCEPTED, restored.status());
        assertEquals("No tests yet", restored.result().summary());
        assertTrue(restored.verification().accepted());
        assertEquals(2, restored.verificationHistory().size());
        assertFalse(restored.verificationHistory().get(0).result().accepted());
        assertTrue(restored.verificationHistory().get(1).result().accepted());

        DelegatedTask next = reloaded.getDelegatedTaskStore().createPending(envelope());
        assertNotEquals(task.taskId(), next.taskId());
    }

    private static DelegatedTaskEnvelope envelope() {
        ContextPressureReport report = new ContextPressureReport(
            List.of("compacted"),
            0.8,
            "HIGH",
            true,
            false,
            false,
            false,
            false,
            List.of("delegation persistence test")
        );
        DelegationDecision decision = new DelegationDecision(
            true,
            "persist delegated task state",
            report,
            "release",
            "critical-path-reviewer"
        );
        return DelegatedTaskEnvelope.of("persist delegated task", "run_persist", decision);
    }
}
