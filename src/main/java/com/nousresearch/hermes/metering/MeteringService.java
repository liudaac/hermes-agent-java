package com.nousresearch.hermes.metering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * S3-1: Metering 服务 — 统一埋点入口。
 *
 * <p>三处埋点位置：</p>
 * <ul>
 *   <li>LLM 调用后：记录 input/output tokens</li>
 *   <li>工具执行后：记录 tool.exec</li>
 *   <li>Sandbox 生命周期：记录 cpu/gpu seconds</li>
 * </ul>
 */
public class MeteringService {
    private static final Logger logger = LoggerFactory.getLogger(MeteringService.class);

    private final UsageStore store;

    public MeteringService(UsageStore store) {
        this.store = store;
    }

    /**
     * 记录 LLM token 用量。
     */
    public void recordLlmCall(String tenantId, String workspaceId, String model,
                               long inputTokens, long outputTokens, String correlationId) {
        if (inputTokens > 0) {
            store.append(new UsageEvent(
                tenantId, workspaceId, UsageEvent.SKU_LLM_INPUT_TOKEN,
                inputTokens, UsageEvent.UNIT_TOKENS, correlationId,
                Map.of("model", model != null ? model : "unknown")
            ));
        }
        if (outputTokens > 0) {
            store.append(new UsageEvent(
                tenantId, workspaceId, UsageEvent.SKU_LLM_OUTPUT_TOKEN,
                outputTokens, UsageEvent.UNIT_TOKENS, correlationId,
                Map.of("model", model != null ? model : "unknown")
            ));
        }
    }

    /**
     * 记录工具执行。
     */
    public void recordToolExec(String tenantId, String workspaceId, String toolName,
                                long durationMs, String correlationId) {
        store.append(new UsageEvent(
            tenantId, workspaceId, UsageEvent.SKU_TOOL_EXEC,
            1, UsageEvent.UNIT_COUNT, correlationId,
            Map.of(
                "tool", toolName != null ? toolName : "unknown",
                "duration_ms", String.valueOf(durationMs)
            )
        ));
    }

    /**
     * 记录沙箱资源使用。
     */
    public void recordSandboxUsage(String tenantId, String workspaceId,
                                    long cpuSeconds, long gpuSeconds, String correlationId) {
        if (cpuSeconds > 0) {
            store.append(new UsageEvent(
                tenantId, workspaceId, UsageEvent.SKU_SANDBOX_CPU,
                cpuSeconds, UsageEvent.UNIT_SECONDS, correlationId,
                Map.of()
            ));
        }
        if (gpuSeconds > 0) {
            store.append(new UsageEvent(
                tenantId, workspaceId, UsageEvent.SKU_SANDBOX_GPU,
                gpuSeconds, UsageEvent.UNIT_SECONDS, correlationId,
                Map.of()
            ));
        }
    }

    /**
     * 查询用量。
     */
    public java.util.List<UsageStore.UsageSummary> queryUsage(
            String tenantId, java.time.Instant from, java.time.Instant to, String granularity) {
        return store.query(tenantId, from, to, granularity);
    }

    /**
     * 按 SKU 查询用量。
     */
    public java.util.List<UsageStore.UsageSummary> queryUsageBySku(
            String tenantId, java.time.Instant from, java.time.Instant to) {
        return store.queryBySku(tenantId, from, to);
    }
}
