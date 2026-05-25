package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class AgentCronRunnerTest {

    @Test
    @DisplayName("Local cron jobs should invoke the AIAgent processMessage")
    void localRunInvokesAgent() throws Exception {
        AIAgent agent = Mockito.mock(AIAgent.class);
        Mockito.when(agent.processMessage("Say hi")).thenReturn("hello");

        HermesConfig config = Mockito.mock(HermesConfig.class);
        AgentCronRunner runner = new AgentCronRunner(config, (cfg, sessionId) -> {
            assertNotNull(sessionId);
            assertTrue(sessionId.startsWith("cron-job_abc-"), "session id should embed safe job id");
            return agent;
        });

        CronHandler.CronJob job = new CronHandler.CronJob();
        job.id = "job/abc";
        job.prompt = "Say hi";
        job.deliver = "local";
        job.schedule = new CronHandler.CronSchedule("cron", "* * * * *", "* * * * *");

        String result = runner.run(job);
        assertEquals("hello", result);
        Mockito.verify(agent).processMessage("Say hi");
    }

    @Test
    @DisplayName("Non-local deliver targets should surface as structured failures")
    void nonLocalDeliverUnsupported() {
        AgentCronRunner runner = new AgentCronRunner(Mockito.mock(HermesConfig.class));
        CronHandler.CronJob job = new CronHandler.CronJob();
        job.id = "remote";
        job.prompt = "hi";
        job.deliver = "feishu";
        job.schedule = new CronHandler.CronSchedule("cron", "* * * * *", "* * * * *");

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () -> runner.run(job));
        assertTrue(ex.getMessage().contains("feishu"));
    }

    @Test
    @DisplayName("Empty prompt should not invoke the agent")
    void emptyPromptRejected() {
        AgentCronRunner runner = new AgentCronRunner(Mockito.mock(HermesConfig.class));
        CronHandler.CronJob job = new CronHandler.CronJob();
        job.id = "empty";
        job.prompt = "   ";
        job.deliver = "local";
        job.schedule = new CronHandler.CronSchedule("cron", "* * * * *", "* * * * *");

        assertThrows(IllegalArgumentException.class, () -> runner.run(job));
    }
}
