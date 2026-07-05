package com.nousresearch.hermes.org.observe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S2-2: 默认 stdout 导出器。
 *
 * <p>将完成的 span 以 JSON 行格式输出到 stdout（或日志）。
 * 格式兼容 OpenTelemetry Collector 的 JSON 输入。</p>
 */
public class StdoutTraceExporter implements TraceExporter {
    private static final Logger logger = LoggerFactory.getLogger(StdoutTraceExporter.class);

    private final boolean enabled;

    public StdoutTraceExporter() {
        this(Boolean.parseBoolean(System.getProperty("hermes.trace.stdout", "true")));
    }

    public StdoutTraceExporter(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void export(Span span) {
        if (!enabled) return;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"traceId\":\"").append(span.traceId()).append("\"");
        sb.append(",\"spanId\":\"").append(span.spanId()).append("\"");
        if (span.parentSpanId() != null) {
            sb.append(",\"parentSpanId\":\"").append(span.parentSpanId()).append("\"");
        }
        sb.append(",\"name\":\"").append(span.name()).append("\"");
        sb.append(",\"startMs\":").append(span.startTime().toEpochMilli());
        sb.append(",\"endMs\":").append(span.endTime().toEpochMilli());
        sb.append(",\"durationMs\":").append(span.durationMs());
        sb.append(",\"status\":\"").append(span.status()).append("\"");

        if (!span.attributes().isEmpty()) {
            sb.append(",\"attrs\":{");
            boolean first = true;
            for (var entry : span.attributes().entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                first = false;
            }
            sb.append("}");
        }

        if (!span.events().isEmpty()) {
            sb.append(",\"events\":[");
            boolean first = true;
            for (var event : span.events()) {
                if (!first) sb.append(",");
                sb.append("{\"name\":\"").append(event.name()).append("\"");
                sb.append(",\"ts\":").append(event.timestamp().toEpochMilli()).append("}");
                first = false;
            }
            sb.append("]");
        }

        sb.append("}");
        logger.info("[TRACE] {}", sb);
    }
}
