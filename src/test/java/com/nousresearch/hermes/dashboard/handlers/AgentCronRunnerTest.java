package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantAIAgent;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class AgentCronRunnerTest {

    private AgentCronRunner buildRunner(TenantAIAgent agent, boolean withWorkspace) throws Exception {
        HermesConfig config = Mockito.mock(HermesConfig.class);
        TenantManager tm = Mockito.mock(TenantManager.class);
        WorkspaceService ws = Mockito.mock(WorkspaceService.class);
        if (withWorkspace) {
            TenantContext ctx = Mockito.mock(TenantContext.class);
            Mockito.when(ws.resolveTenantContext("ws-1")).thenReturn(ctx);
            Mockito.when(ctx.getOrCreateAgent(Mockito.anyString(), Mockito.any())).thenReturn(agent);
        } else {
            TenantContext defaultCtx = Mockito.mock(TenantContext.class);
            Mockito.when(tm.getOrCreateTenant(Mockito.eq("default"), Mockito.any())).thenReturn(defaultCtx);
            Mockito.when(defaultCtx.getOrCreateAgent(Mockito.anyString(), Mockito.any())).thenReturn(agent);
        }
        return new AgentCronRunner(config, tm, ws);
    }

    @Test
    @DisplayName("Local cron jobs (legacy, no workspaceId) should invoke an agent on default tenant")
    void localRunInvokesAgent() throws Exception {
        TenantAIAgent agent = Mockito.mock(TenantAIAgent.class);
        Mockito.when(agent.processMessage("Say hi")).thenReturn("hello");
        AgentCronRunner runner = buildRunner(agent, false);

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
    @DisplayName("Local cron jobs with workspaceId should resolve via WorkspaceService")
    void localRunWithWorkspace() throws Exception {
        TenantAIAgent agent = Mockito.mock(TenantAIAgent.class);
        Mockito.when(agent.processMessage("hello world")).thenReturn("done");
        AgentCronRunner runner = buildRunner(agent, true);

        CronHandler.CronJob job = new CronHandler.CronJob();
        job.id = "wjob";
        job.prompt = "hello world";
        job.deliver = "local";
        job.workspaceId = "ws-1";
        job.schedule = new CronHandler.CronSchedule("cron", "* * * * *", "* * * * *");

        String result = runner.run(job);
        assertEquals("done", result);
        Mockito.verify(agent).processMessage("hello world");
    }

    @Test
    @DisplayName("Non-local deliver targets should surface as structured failures")
    void nonLocalDeliverUnsupported() throws Exception {
        AgentCronRunner runner = buildRunner(Mockito.mock(TenantAIAgent.class), false);
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
    void emptyPromptRejected() throws Exception {
        AgentCronRunner runner = buildRunner(Mockito.mock(TenantAIAgent.class), false);
        CronHandler.CronJob job = new CronHandler.CronJob();
        job.id = "empty";
        job.prompt = "   ";
        job.deliver = "local";
        job.schedule = new CronHandler.CronSchedule("cron", "* * * * *", "* * * * *");

        assertThrows(IllegalArgumentException.class, () -> runner.run(job));
    }
}
