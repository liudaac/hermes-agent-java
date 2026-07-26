package com.nousresearch.hermes.observability;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2: BusinessMetricsCollector + ExecutionTrace + TraceStore tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityTest {

    // ============ BusinessMetricsCollector ============

    @Test
    @Order(1)
    @DisplayName("recordApiCall increments tenant metrics")
    void metrics_apiCall() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordApiCall("t1", "sys1", "/api/v1/agents", 150);
        var summary = collector.getTenantSummary("t1");
        assertEquals(1, summary.get("api_calls_total"));
        assertEquals(150L, summary.get("api_duration_ms_total"));
    }

    @Test
    @Order(2)
    @DisplayName("recordModelCall tracks tokens and cost")
    void metrics_modelCall() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordModelCall("t1", "gpt-4o", "openai", 100, 200, 0.018, 500);
        var summary = collector.getTenantSummary("t1");
        assertEquals(1, summary.get("model_calls_total"));
        assertEquals(100L, summary.get("input_tokens_total"));
        assertEquals(200L, summary.get("output_tokens_total"));
        assertEquals(300L, summary.get("total_tokens_total"));
        assertEquals(18_000L, summary.get("estimated_cost_usd_total")); // micro-dollars

        var modelSummary = collector.getModelSummary("gpt-4o");
        assertEquals(1, modelSummary.get("calls_total"));
        assertEquals(100L, modelSummary.get("input_tokens_total"));
    }

    @Test
    @Order(3)
    @DisplayName("recordAgentMessage tracks success and error")
    void metrics_agentMessage() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordAgentMessage("t1", "agent-1", 300, true);
        collector.recordAgentMessage("t1", "agent-1", 500, false);

        var summary = collector.getTenantSummary("t1");
        assertEquals(2, summary.get("agent_messages_total"));
        assertEquals(1, summary.get("agent_errors_total"));
    }

    @Test
    @Order(4)
    @DisplayName("recordTask tracks status")
    void metrics_task() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordTask("t1", "COMPLETED");
        collector.recordTask("t1", "FAILED");
        collector.recordTask("t1", "COMPLETED");

        var summary = collector.getTenantSummary("t1");
        assertEquals(3, summary.get("tasks_total"));
        assertEquals(2, summary.get("tasks_completed"));
        assertEquals(1, summary.get("tasks_failed"));
    }

    @Test
    @Order(5)
    @DisplayName("exportPrometheus produces valid format")
    void metrics_export() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordModelCall("t1", "gpt-4o", "openai", 100, 200, 0.018, 500);
        collector.recordApiCall("t1", "sys1", "/test", 100);

        String prom = collector.exportPrometheus();
        assertTrue(prom.contains("hermes_model_calls_total"));
        assertTrue(prom.contains("model=\"gpt-4o\""));
        assertTrue(prom.contains("hermes_api_calls_total"));
        assertTrue(prom.contains("tenant=\"t1\""));
    }

    @Test
    @Order(6)
    @DisplayName("reset clears all metrics")
    void metrics_reset() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordApiCall("t1", null, "/test", 100);
        collector.reset();
        assertTrue(collector.getTenantSummary("t1").isEmpty());
    }

    @Test
    @Order(7)
    @DisplayName("system metrics tracked separately")
    void metrics_system() {
        BusinessMetricsCollector collector = new BusinessMetricsCollector();
        collector.recordApiCall("t1", "sys1", "/test", 100);
        collector.recordApiCall("t1", "sys2", "/test", 200);

        // Both systems should have their own metrics
        String prom = collector.exportPrometheus();
        assertTrue(prom.contains("system=\"sys1\""));
        assertTrue(prom.contains("system=\"sys2\""));
    }

    // ============ ExecutionTrace ============

    @Test
    @Order(8)
    @DisplayName("ExecutionTrace creates with unique ID")
    void trace_create() {
        ExecutionTrace trace = new ExecutionTrace("t1", "s1", "agent-1");
        assertNotNull(trace.getTraceId());
        assertTrue(trace.getTraceId().startsWith("trace_"));
        assertEquals("RUNNING", trace.getStatus());
        assertEquals("t1", trace.getTenantId());
        assertEquals("agent-1", trace.getAgentId());
    }

    @Test
    @Order(9)
    @DisplayName("addSpan creates trace spans")
    void trace_addSpan() {
        ExecutionTrace trace = new ExecutionTrace("t1", "s1", "agent-1");
        ExecutionTrace.TraceSpan span = trace.addSpan("model_call", "model");
        span.addAttribute("model", "gpt-4o");
        span.addAttribute("tokens", 500);
        span.complete();

        assertEquals(1, trace.getSpans().size());
        assertEquals("model_call", trace.getSpans().get(0).name());
        assertEquals("gpt-4o", trace.getSpans().get(0).toApi().get("attributes").toString().contains("gpt-4o")
            ? "gpt-4o" : null);
    }

    @Test
    @Order(10)
    @DisplayName("trace complete sets status and endTime")
    void trace_complete() {
        ExecutionTrace trace = new ExecutionTrace("t1", "s1", "agent-1");
        trace.complete();
        assertEquals("COMPLETED", trace.getStatus());
        assertNotNull(trace.getEndTime());
        assertTrue(trace.getDurationMs() >= 0);
    }

    @Test
    @Order(11)
    @DisplayName("trace fail records error span")
    void trace_fail() {
        ExecutionTrace trace = new ExecutionTrace("t1", "s1", "agent-1");
        trace.fail("API timeout");
        assertEquals("FAILED", trace.getStatus());
        boolean hasErrorSpan = trace.getSpans().stream()
            .anyMatch(s -> "error".equals(s.type()));
        assertTrue(hasErrorSpan);
    }

    @Test
    @Order(12)
    @DisplayName("toApi returns JSON-serializable map")
    void trace_toApi() {
        ExecutionTrace trace = new ExecutionTrace("t1", "s1", "agent-1");
        trace.addSpan("tool_call", "tool").addAttribute("tool", "search").complete();
        trace.complete();

        var api = trace.toApi();
        assertEquals("t1", api.get("tenantId"));
        assertEquals("COMPLETED", api.get("status"));
        assertNotNull(api.get("spans"));
    }

    // ============ TraceStore ============

    @Test
    @Order(13)
    @DisplayName("TraceStore store + get by ID")
    void traceStore_storeGet() {
        TraceStore store = new TraceStore();
        ExecutionTrace trace = new ExecutionTrace("t1", "s1", "agent-1");
        store.store(trace);

        ExecutionTrace found = store.get(trace.getTraceId());
        assertNotNull(found);
        assertEquals(trace.getTraceId(), found.getTraceId());
    }

    @Test
    @Order(14)
    @DisplayName("TraceStore list filters by tenant")
    void traceStore_listFilter() {
        TraceStore store = new TraceStore();
        store.store(new ExecutionTrace("t1", "s1", "a1"));
        store.store(new ExecutionTrace("t1", "s2", "a2"));
        store.store(new ExecutionTrace("t2", "s3", "a3"));

        List<ExecutionTrace> t1Traces = store.list("t1", null, 10);
        assertEquals(2, t1Traces.size());

        List<ExecutionTrace> t2Traces = store.list("t2", null, 10);
        assertEquals(1, t2Traces.size());
    }

    @Test
    @Order(15)
    @DisplayName("TraceStore list filters by agent")
    void traceStore_listAgent() {
        TraceStore store = new TraceStore();
        store.store(new ExecutionTrace("t1", "s1", "agent-a"));
        store.store(new ExecutionTrace("t1", "s2", "agent-b"));

        List<ExecutionTrace> traces = store.list("t1", "agent-a", 10);
        assertEquals(1, traces.size());
    }

    @Test
    @Order(16)
    @DisplayName("TraceStore respects limit")
    void traceStore_limit() {
        TraceStore store = new TraceStore();
        for (int i = 0; i < 50; i++) {
            store.store(new ExecutionTrace("t1", "s" + i, "a1"));
        }
        assertEquals(5, store.list("t1", null, 5).size());
    }

    @Test
    @Order(17)
    @DisplayName("TraceStore evicts oldest when full")
    void traceStore_eviction() {
        TraceStore store = new TraceStore(3);
        ExecutionTrace t1 = new ExecutionTrace("t1", "s1", "a1");
        ExecutionTrace t2 = new ExecutionTrace("t2", "s2", "a2");
        ExecutionTrace t3 = new ExecutionTrace("t3", "s3", "a3");
        ExecutionTrace t4 = new ExecutionTrace("t4", "s4", "a4");

        store.store(t1);
        store.store(t2);
        store.store(t3);
        store.store(t4);

        assertEquals(3, store.size());
        assertNull(store.get(t1.getTraceId())); // evicted
        assertNotNull(store.get(t4.getTraceId())); // newest
    }

    @Test
    @Order(18)
    @DisplayName("TraceStore clear removes all")
    void traceStore_clear() {
        TraceStore store = new TraceStore();
        store.store(new ExecutionTrace("t1", "s1", "a1"));
        store.clear();
        assertEquals(0, store.size());
    }
}
