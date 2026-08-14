package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class CompactionMaintenanceJobTest {

    @Test
    void testNameAndPriority() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        CompactionMaintenanceJob job = new CompactionMaintenanceJob(ctx);

        assertEquals("compaction-check", job.name());
        assertEquals(10, job.priority());
    }

    @Test
    void testRunsWithoutErrorOnEmptyHistory() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        Mockito.when(ctx.history()).thenReturn(java.util.List.of());
        Mockito.when(ctx.modelClient()).thenReturn(null);
        Mockito.when(ctx.sessionLog()).thenReturn(Mockito.mock(com.nousresearch.hermes.harness.session.SessionLog.class));

        CompactionMaintenanceJob job = new CompactionMaintenanceJob(ctx);

        // Should not throw
        assertDoesNotThrow(() -> job.run());
    }
}
