package com.nousresearch.hermes.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F2: Business metrics collector - standardized, exportable.
 *
 * <p>Collects business-level metrics beyond the existing tenant metrics:</p>
 * <ul>
 *   <li>Per-tenant: request count, token usage, cost, error rate</li>
 *   <li>Per-model: call count, avg latency, token efficiency</li>
 *   <li>Per-agent: message count, avg response time, tool calls</li>
 *   <li>Per-business-system: API call count, task count</li>
 * </ul>
 *
 * <p>Exports in Prometheus format with proper labels for Grafana dashboards.</p>
 */
public class BusinessMetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(BusinessMetricsCollector.class);

    // tenantId -> metric key -> value
    private final ConcurrentHashMap<String, Map<String, AtomicLong>> tenantMetrics = new ConcurrentHashMap<>();
    // model -> metric key -> value
    private final ConcurrentHashMap<String, Map<String, AtomicLong>> modelMetrics = new ConcurrentHashMap<>();
    // agentId -> metric key -> value
    private final ConcurrentHashMap<String, Map<String, AtomicLong>> agentMetrics = new ConcurrentHashMap<>();
    // systemId -> metric key -> value
    private final ConcurrentHashMap<String, Map<String, AtomicLong>> systemMetrics = new ConcurrentHashMap<>();

    // ============ Recording ============

    public void recordApiCall(String tenantId, String systemId, String endpoint, long durationMs) {
        increment(tenantMetrics, tenantId, "api_calls_total");
        increment(tenantMetrics, tenantId, "api_duration_ms_total", durationMs);
        if (systemId != null) {
            increment(systemMetrics, systemId, "api_calls_total");
            increment(systemMetrics, systemId, "api_duration_ms_total", durationMs);
        }
    }

    public void recordModelCall(String tenantId, String model, String provider,
                                long inputTokens, long outputTokens, double cost, long durationMs) {
        increment(tenantMetrics, tenantId, "model_calls_total");
        increment(tenantMetrics, tenantId, "input_tokens_total", inputTokens);
        increment(tenantMetrics, tenantId, "output_tokens_total", outputTokens);
        increment(tenantMetrics, tenantId, "total_tokens_total", inputTokens + outputTokens);
        increment(tenantMetrics, tenantId, "estimated_cost_usd_total", (long)(cost * 1_000_000)); // store as micro-dollars

        increment(modelMetrics, model, "calls_total");
        increment(modelMetrics, model, "input_tokens_total", inputTokens);
        increment(modelMetrics, model, "output_tokens_total", outputTokens);
        increment(modelMetrics, model, "duration_ms_total", durationMs);
    }

    public void recordAgentMessage(String tenantId, String agentId, long durationMs, boolean success) {
        increment(tenantMetrics, tenantId, "agent_messages_total");
        increment(tenantMetrics, tenantId, "agent_duration_ms_total", durationMs);
        if (!success) {
            increment(tenantMetrics, tenantId, "agent_errors_total");
        }

        increment(agentMetrics, agentId, "messages_total");
        increment(agentMetrics, agentId, "duration_ms_total", durationMs);
        if (!success) {
            increment(agentMetrics, agentId, "errors_total");
        }
    }

    public void recordTask(String tenantId, String status) {
        increment(tenantMetrics, tenantId, "tasks_total");
        increment(tenantMetrics, tenantId, "tasks_" + status.toLowerCase());
    }

    public void recordToolCall(String tenantId, String toolName, boolean success) {
        increment(tenantMetrics, tenantId, "tool_calls_total");
        if (!success) {
            increment(tenantMetrics, tenantId, "tool_errors_total");
        }
    }

    public void recordApproval(String tenantId, String status) {
        increment(tenantMetrics, tenantId, "approvals_total");
        increment(tenantMetrics, tenantId, "approvals_" + status.toLowerCase());
    }

    public void recordWebhookDispatch(String tenantId, String eventType, boolean success) {
        increment(tenantMetrics, tenantId, "webhooks_dispatched_total");
        if (!success) {
            increment(tenantMetrics, tenantId, "webhooks_failed_total");
        }
    }

    // ============ Export ============

    /**
     * Export all metrics in Prometheus text format.
     */
    public String exportPrometheus() {
        StringBuilder sb = new StringBuilder(8192);

        // Tenant metrics
        for (var tenantEntry : tenantMetrics.entrySet()) {
            String tenant = tenantEntry.getKey();
            for (var metricEntry : tenantEntry.getValue().entrySet()) {
                String metric = metricEntry.getKey();
                long value = metricEntry.getValue().get();
                sb.append("# TYPE hermes_").append(metric).append(" counter\n");
                sb.append("hermes_").append(metric)
                  .append("{tenant=\"").append(tenant).append("\"} ")
                  .append(value).append('\n');
            }
        }

        // Model metrics
        for (var modelEntry : modelMetrics.entrySet()) {
            String model = modelEntry.getKey();
            for (var metricEntry : modelEntry.getValue().entrySet()) {
                String metric = metricEntry.getKey();
                long value = metricEntry.getValue().get();
                sb.append("# TYPE hermes_model_").append(metric).append(" counter\n");
                sb.append("hermes_model_").append(metric)
                  .append("{model=\"").append(model).append("\"} ")
                  .append(value).append('\n');
            }
        }

        // Agent metrics
        for (var agentEntry : agentMetrics.entrySet()) {
            String agent = agentEntry.getKey();
            for (var metricEntry : agentEntry.getValue().entrySet()) {
                String metric = metricEntry.getKey();
                long value = metricEntry.getValue().get();
                sb.append("# TYPE hermes_agent_").append(metric).append(" counter\n");
                sb.append("hermes_agent_").append(metric)
                  .append("{agent=\"").append(agent).append("\"} ")
                  .append(value).append('\n');
            }
        }

        // System metrics
        for (var systemEntry : systemMetrics.entrySet()) {
            String system = systemEntry.getKey();
            for (var metricEntry : systemEntry.getValue().entrySet()) {
                String metric = metricEntry.getKey();
                long value = metricEntry.getValue().get();
                sb.append("# TYPE hermes_system_").append(metric).append(" counter\n");
                sb.append("hermes_system_").append(metric)
                  .append("{system=\"").append(system).append("\"} ")
                  .append(value).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Get metrics summary for a tenant (for API response).
     */
    public Map<String, Long> getTenantSummary(String tenantId) {
        Map<String, Long> summary = new LinkedHashMap<>();
        Map<String, AtomicLong> metrics = tenantMetrics.get(tenantId);
        if (metrics != null) {
            for (var entry : metrics.entrySet()) {
                summary.put(entry.getKey(), entry.getValue().get());
            }
        }
        return summary;
    }

    /**
     * Get metrics summary for a model.
     */
    public Map<String, Long> getModelSummary(String model) {
        Map<String, Long> summary = new LinkedHashMap<>();
        Map<String, AtomicLong> metrics = modelMetrics.get(model);
        if (metrics != null) {
            for (var entry : metrics.entrySet()) {
                summary.put(entry.getKey(), entry.getValue().get());
            }
        }
        return summary;
    }

    /**
     * Reset all metrics (for testing).
     */
    public void reset() {
        tenantMetrics.clear();
        modelMetrics.clear();
        agentMetrics.clear();
        systemMetrics.clear();
    }

    // ============ Internal ============

    private void increment(ConcurrentHashMap<String, Map<String, AtomicLong>> container,
                          String key, String metric) {
        container.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(metric, m -> new AtomicLong(0))
            .incrementAndGet();
    }

    private void increment(ConcurrentHashMap<String, Map<String, AtomicLong>> container,
                          String key, String metric, long delta) {
        container.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(metric, m -> new AtomicLong(0))
            .addAndGet(delta);
    }
}
