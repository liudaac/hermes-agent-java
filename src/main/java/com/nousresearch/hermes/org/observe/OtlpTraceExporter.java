package com.nousresearch.hermes.org.observe;

import java.util.List;

/**
 * S2-2: OTLP 导出器桩（预留接口，未配置 OTLP endpoint 时为 no-op）。
 *
 * <p>配置 {@code hermes.trace.otlp.endpoint=http://localhost:4317} 后启用。
 * 未来可接入真实的 OpenTelemetry OTLP gRPC/HTTP exporter。</p>
 */
public class OtlpTraceExporter implements TraceExporter {
    private final String endpoint;
    private final boolean enabled;

    public OtlpTraceExporter(String endpoint) {
        this.endpoint = endpoint;
        this.enabled = endpoint != null && !endpoint.isBlank();
    }

    public OtlpTraceExporter() {
        this(System.getProperty("hermes.trace.otlp.endpoint"));
    }

    @Override
    public void export(Span span) {
        if (!enabled) return;
        // TODO: 接入 io.opentelemetry:opentelemetry-exporter-otlp 后实现
        // 当前为 no-op 预留
    }

    public boolean isEnabled() { return enabled; }
    public String getEndpoint() { return endpoint; }
}
