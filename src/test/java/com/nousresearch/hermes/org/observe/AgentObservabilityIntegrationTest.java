package com.nousresearch.hermes.org.observe;

import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AI原生组织 第五刀：Org-wide 可观测性
 */
class AgentObservabilityIntegrationTest {

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
            new AgentRole("engineer", "Builds things", AgentRole.Level.SENIOR));
        tenantContext.registerAgentRole("agent-B",
            new AgentRole("reviewer", "Reviews", AgentRole.Level.LEAD));

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

    @Test
    void summaryContainsAllMetrics() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "s", "task");
        trace.step(AgentTrace.Step.toolCall("test_tool", "{}", java.util.List.of(), 1.0, 100, 0.001));
        trace.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(trace);

        var summary = obs.getSummary();
        assertTrue(summary.containsKey("active_traces"));
        assertTrue(summary.containsKey("total_traces"));
        assertTrue(summary.containsKey("agent_status"));
    }

    @Test
    void errorTraceIncrementsErrorCount() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "s", "task");
        trace.step(AgentTrace.Step.error("Something went wrong"));
        trace.end(AgentTrace.Status.FAILED);
        obs.completeTrace(trace);

        assertEquals(1, trace.getErrorCount());
        assertEquals(AgentTrace.Status.FAILED, trace.getStatus());
    }

    @Test
    void traceStepTypes() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "s", "task");

        trace.step(AgentTrace.Step.thinking("think", 0.9));
        trace.step(AgentTrace.Step.toolCall("tool", "args", java.util.List.of("alt"), 0.7, 100, 0.001));
        trace.step(AgentTrace.Step.toolResult("tool", "out", 200));
        trace.step(AgentTrace.Step.decision("decide", 0.8, java.util.List.of("a", "b")));
        trace.step(AgentTrace.Step.error("oops"));
        trace.step(AgentTrace.Step.handoff("need human"));

        assertEquals(6, trace.stepCount());
    }

    @Test
    void traceForensicsAndTimeline() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "s", "investigate bug");
        trace.step(AgentTrace.Step.thinking("looking", 0.5));
        trace.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(trace);

        var forensics = trace.forensics();
        var timeline = trace.toTimeline();
        assertNotNull(forensics);
        assertNotNull(timeline);
    }

    // ======== Org Tool Integration ========

    @Test
    void orgTracesReturnsRecentTraces() {
        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("agent-A", "s", "task 1");
        trace.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(trace);

        String result = dispatcher.dispatch("org_traces", Map.of("limit", 5));
        assertTrue(result.contains("traces"));
        assertTrue(result.contains("count"));
    }

    @Test
    void orgTracesFilterByAgent() {
        var obs = tenantContext.getObservability();
        var t1 = obs.startTrace("agent-A", "s1", "task A");
        t1.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(t1);

        var t2 = obs.startTrace("agent-B", "s2", "task B");
        t2.end(AgentTrace.Status.SUCCESS);
        obs.completeTrace(t2);

        String result = dispatcher.dispatch("org_traces",
            Map.of("agent_id", "agent-A", "limit", 10));
        // The dispatcher returns "filter" as the field name for the agent filter
        assertTrue(result.contains("agent-A"));
        assertTrue(result.contains("filter"));
    }

    @Test
    void orgAnomaliesEmptyInitially() {
        String result = dispatcher.dispatch("org_anomalies", Map.of("limit", 5));
        assertTrue(result.contains("anomalies"));
        assertTrue(result.contains("count"));
    }

    @Test
    void toolsRegistered() {
        var tools = registry.getAllTools();
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("org_traces")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("org_anomalies")));
    }

    @Test
    void totalTrackedTraces() {
        var obs = tenantContext.getObservability();
        for (int i = 0; i < 5; i++) {
            var t = obs.startTrace("agent-A", "s" + i, "task " + i);
            t.end(AgentTrace.Status.SUCCESS);
            obs.completeTrace(t);
        }
        var all = obs.getAllRecentTraces(100);
        assertTrue(all.size() >= 5);
    }
}
