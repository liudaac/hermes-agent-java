package com.nousresearch.hermes.observability;

import java.time.Instant;
import java.util.*;

/**
 * F2: Execution trace for observability - queryable by business systems.
 *
 * <p>Represents a single execution trace (one message or task lifecycle):
 * input -> agent processing -> tool calls -> model calls -> output.</p>
 *
 * <p>Stored in-memory (ring buffer, last 10k traces) + optional export to
 * OpenTelemetry/Jeager via OTLP.</p>
 */
public class ExecutionTrace {

    private final String traceId;
    private final String tenantId;
    private final String sessionId;
    private final String agentId;
    private final Instant startTime;
    private Instant endTime;
    private String status; // RUNNING, COMPLETED, FAILED
    private final List<TraceSpan> spans;

    public ExecutionTrace(String tenantId, String sessionId, String agentId) {
        this.traceId = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.startTime = Instant.now();
        this.status = "RUNNING";
        this.spans = Collections.synchronizedList(new ArrayList<>());
    }

    public String getTraceId() { return traceId; }
    public String getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public String getAgentId() { return agentId; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public String getStatus() { return status; }
    public List<TraceSpan> getSpans() { return List.copyOf(spans); }

    public TraceSpan addSpan(String name, String type) {
        TraceSpan span = new TraceSpan(name, type);
        spans.add(span);
        return span;
    }

    public void complete() {
        this.status = "COMPLETED";
        this.endTime = Instant.now();
    }

    public void fail(String error) {
        this.status = "FAILED";
        this.endTime = Instant.now();
        TraceSpan errorSpan = new TraceSpan("error", "error");
        errorSpan.addAttribute("error", error);
        spans.add(errorSpan);
    }

    public long getDurationMs() {
        Instant end = endTime != null ? endTime : Instant.now();
        return end.toEpochMilli() - startTime.toEpochMilli();
    }

    public Map<String, Object> toApi() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", traceId);
        m.put("tenantId", tenantId);
        m.put("agentId", agentId);
        m.put("sessionId", sessionId);
        m.put("status", status);
        m.put("startTime", startTime.toString());
        if (endTime != null) m.put("endTime", endTime.toString());
        m.put("durationMs", getDurationMs());
        m.put("spans", spans.stream().map(TraceSpan::toApi).toList());
        return m;
    }

    public static class TraceSpan {
        private final String spanId;
        private final String name;
        private final String type; // model_call, tool_call, agent_message, webhook
        private final Instant startTime;
        private Instant endTime;
        private final Map<String, Object> attributes;

        public TraceSpan(String name, String type) {
            this.spanId = "span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            this.name = name;
            this.type = type;
            this.startTime = Instant.now();
            this.attributes = new LinkedHashMap<>();
        }

        public TraceSpan addAttribute(String key, Object value) {
            attributes.put(key, value);
            return this;
        }

        public String name() { return name; }
        public String type() { return type; }
        public String spanId() { return spanId; }

        public void complete() {
            this.endTime = Instant.now();
        }

        public long getDurationMs() {
            Instant end = endTime != null ? endTime : Instant.now();
            return end.toEpochMilli() - startTime.toEpochMilli();
        }

        public Map<String, Object> toApi() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("spanId", spanId);
            m.put("name", name);
            m.put("type", type);
            m.put("startTime", startTime.toString());
            if (endTime != null) m.put("endTime", endTime.toString());
            m.put("durationMs", getDurationMs());
            m.put("attributes", attributes);
            return m;
        }
    }
}
