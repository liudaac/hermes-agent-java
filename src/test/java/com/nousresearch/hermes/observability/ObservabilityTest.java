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

    // ============ ExecutionTrace ============

    // ============ TraceStore ============

}
