package com.nousresearch.hermes.metering;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * S3-1: 用量事件 — metering 的原子记录。
 *
 * <p>每次 LLM 调用、工具执行、沙箱使用都产生一条 UsageEvent。
 * Append-only，不可修改，用于计费和用量分析。</p>
 *
 * <p>SKU 分类：</p>
 * <ul>
 *   <li>{@code llm.input_token} / {@code llm.output_token} — LLM token 用量</li>
 *   <li>{@code tool.exec} — 工具执行次数</li>
 *   <li>{@code sandbox.cpu_second} / {@code sandbox.gpu_second} — 沙箱资源</li>
 * </ul>
 */
public class UsageEvent {

    private final String eventId;
    private final String tenantId;
    private final String workspaceId;
    private final String sku;
    private final long quantity;
    private final String unit;
    private final Instant occurredAt;
    private final String correlationId;
    private final Map<String, String> dimensions;

    public UsageEvent(String tenantId, String workspaceId, String sku,
                      long quantity, String unit, String correlationId,
                      Map<String, String> dimensions) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId cannot be null");
        this.workspaceId = workspaceId;
        this.sku = Objects.requireNonNull(sku, "sku cannot be null");
        this.quantity = quantity;
        this.unit = Objects.requireNonNull(unit, "unit cannot be null");
        this.occurredAt = Instant.now();
        this.correlationId = correlationId;
        this.dimensions = dimensions != null ? Map.copyOf(dimensions) : Map.of();
    }

    // Getters
    public String getEventId() { return eventId; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getSku() { return sku; }
    public long getQuantity() { return quantity; }
    public String getUnit() { return unit; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getCorrelationId() { return correlationId; }
    public Map<String, String> getDimensions() { return dimensions; }

    /** SKU 常量 */
    public static final String SKU_LLM_INPUT_TOKEN = "llm.input_token";
    public static final String SKU_LLM_OUTPUT_TOKEN = "llm.output_token";
    public static final String SKU_TOOL_EXEC = "tool.exec";
    public static final String SKU_SANDBOX_CPU = "sandbox.cpu_second";
    public static final String SKU_SANDBOX_GPU = "sandbox.gpu_second";

    /** 单位常量 */
    public static final String UNIT_TOKENS = "tokens";
    public static final String UNIT_COUNT = "count";
    public static final String UNIT_SECONDS = "seconds";

    @Override
    public String toString() {
        return "UsageEvent{" + tenantId + "/" + sku + "=" + quantity + unit +
            " @ " + occurredAt + "}";
    }
}
