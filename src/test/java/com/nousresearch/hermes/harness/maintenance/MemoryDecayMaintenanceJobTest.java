package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class MemoryDecayMaintenanceJobTest {

    @Test
    void testNameAndPriority() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        MemoryDecayMaintenanceJob job = new MemoryDecayMaintenanceJob(ctx);

        assertEquals("memory-decay", job.name());
        assertEquals(20, job.priority());
    }

    @Test
    void testRunsWithoutError() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        Mockito.when(ctx.agent()).thenReturn(null);

        MemoryDecayMaintenanceJob job = new MemoryDecayMaintenanceJob(ctx);

        assertDoesNotThrow(() -> job.run());
    }
}
