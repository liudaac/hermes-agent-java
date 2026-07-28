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

    // ============ ExecutionTrace ============

    // ============ TraceStore ============

}
