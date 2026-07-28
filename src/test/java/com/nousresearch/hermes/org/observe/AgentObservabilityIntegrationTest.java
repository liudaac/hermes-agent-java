package com.nousresearch.hermes.org.observe;

import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests for AI原生组织 第五刀：Org-wide 可观测性
 */
class AgentObservabilityIntegrationTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    private ToolRegistry registry;
    private TenantContext tenantContext;
    private TenantAwareToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);

        var request = TenantProvisioningRequest.builder("obs-tenant", "test-user").build();
        tenantContext = TenantContext.create("obs-tenant", request);

        tenantContext.registerAgentRole("agent-A",
            new AgentRuntimeProfile("engineer", "Builds things", AgentRuntimeProfile.Level.SENIOR));
        tenantContext.registerAgentRole("agent-B",
            new AgentRuntimeProfile("reviewer", "Reviews", AgentRuntimeProfile.Level.LEAD));

        dispatcher = new TenantAwareToolDispatcher(tenantContext, registry);
    }

    // ======== AgentObservability Direct API ========

    @Test
    void observabilityAccessibleFromTenant() {
        var obs = tenantContext.getObservability();
        assertNotNull(obs);
        assertSame(obs, tenantContext.getObservability());
    }

    @Test
    void traceLifecycle() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "session-1", "test task");
        assertNotNull(trace);
        assertEquals("agent-A", trace.getAgentId());

        trace.step(AgentTrace.Step.thinking("Let me think", 0.9));
        trace.step(AgentTrace.Step.toolCall("file_read", "{}", java.util.List.of(), 0.8, 100, 0.001));
        trace.step(AgentTrace.Step.toolResult("file_read", "contents", 50));
        trace.step(AgentTrace.Step.decision("I'll proceed", 0.85, java.util.List.of()));

        trace.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(trace);

        assertEquals(4, trace.stepCount());
        var recent = obs.getRecentTraces("agent-A", 10);
        assertEquals(1, recent.size());
        assertEquals("test task", recent.get(0).getTaskDescription());
    }

    @Test
    void getAllRecentTracesAcrossAgents() {
        var obs = tenantContext.getObservability();
        var t1 = obs.startTrace("agent-A", "s1", "task 1");
        t1.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(t1);

        var t2 = obs.startTrace("agent-B", "s2", "task 2");
        t2.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(t2);

        var all = obs.getAllRecentTraces(10);
        assertTrue(all.size() >= 2);
    }

    @Test
    void agentStatusUpdatedAfterTrace() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "s", "task");
        trace.step(AgentTrace.Step.toolCall("test_tool", "{}", java.util.List.of(), 1.0, 50, 0.001));
        trace.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(trace);

        var status = obs.getStatus("agent-A");
        assertNotNull(status);
        assertEquals(1, status.getTotalTasks());
    }

    // ======== Org Tool Integration ========

}
