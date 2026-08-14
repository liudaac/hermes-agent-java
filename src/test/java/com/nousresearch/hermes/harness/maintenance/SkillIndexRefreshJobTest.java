package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class SkillIndexRefreshJobTest {

    @Test
    void testNameAndPriority() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        SkillIndexRefreshJob job = new SkillIndexRefreshJob(ctx);

        assertEquals("skill-index-refresh", job.name());
        assertEquals(30, job.priority());
    }

    @Test
    void testRunsWithoutError() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        Mockito.when(ctx.agent()).thenReturn(null);

        SkillIndexRefreshJob job = new SkillIndexRefreshJob(ctx);

        assertDoesNotThrow(() -> job.run());
    }
}
