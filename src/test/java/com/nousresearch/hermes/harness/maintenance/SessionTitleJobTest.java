package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import com.nousresearch.hermes.harness.session.SessionLog;
import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTitleJobTest {

    @Test
    void testNameAndPriority() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        SessionTitleJob job = new SessionTitleJob(ctx);

        assertEquals("session-title", job.name());
        assertEquals(40, job.priority());
    }

    @Test
    void testRunsWithoutErrorOnHistoryWithUserMessage() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        SessionLog sessionLog = Mockito.mock(SessionLog.class);

        Mockito.when(ctx.history()).thenReturn(List.of(
            ModelMessage.system("system prompt"),
            ModelMessage.user("Hello, can you help me?")
        ));
        Mockito.when(ctx.sessionLog()).thenReturn(sessionLog);

        SessionTitleJob job = new SessionTitleJob(ctx);

        assertDoesNotThrow(() -> job.run());
        Mockito.verify(sessionLog).appendTrace(
            Mockito.eq(com.nousresearch.hermes.harness.session.SessionEventType.CUSTOM),
            Mockito.any());
    }

    @Test
    void testSkipsOnEmptyHistory() {
        AgentContext ctx = Mockito.mock(AgentContext.class);
        SessionLog sessionLog = Mockito.mock(SessionLog.class);

        Mockito.when(ctx.history()).thenReturn(java.util.List.of());
        Mockito.when(ctx.sessionLog()).thenReturn(sessionLog);

        SessionTitleJob job = new SessionTitleJob(ctx);

        assertDoesNotThrow(() -> job.run());
        Mockito.verifyNoInteractions(sessionLog);
    }
}
