package com.nousresearch.hermes.metering;

import java.util.Map;
import java.util.Objects;

/**
 * S4-2: 模型定价配置 — 每模型每 provider 的单价。
 *
 * <p>支持 cache read / cache write 分开定价（Anthropic / OpenAI 都有）。</p>
 *
 * <p>数据源：一份内建 pricing.yaml，可 override。</p>
 */
public record ModelPricing(
    String model,
    String provider,
    double inputPricePerMillion,    // 每 1M input tokens 的价格（美元）
    double outputPricePerMillion,   // 每 1M output tokens 的价格
    double cacheReadPricePerMillion,  // cache read 每 1M tokens
    double cacheWritePricePerMillion  // cache write 每 1M tokens
) {
    public ModelPricing {
        Objects.requireNonNull(model, "model cannot be null");
        provider = provider != null ? provider : "unknown";
        inputPricePerMillion = Math.max(0, inputPricePerMillion);
        outputPricePerMillion = Math.max(0, outputPricePerMillion);
        cacheReadPricePerMillion = Math.max(0, cacheReadPricePerMillion);
        cacheWritePricePerMillion = Math.max(0, cacheWritePricePerMillion);
    }

    /**
     * 计算单次调用成本。
     *
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     * @param cacheReadTokens cache read token 数（0 如果没有）
     * @param cacheWriteTokens cache write token 数（0 如果没有）
     * @return 成本（美元）
     */
    public double calculateCost(long inputTokens, long outputTokens,
                                 long cacheReadTokens, long cacheWriteTokens) {
        double inputCost = (inputTokens / 1_000_000.0) * inputPricePerMillion;
        double outputCost = (outputTokens / 1_000_000.0) * outputPricePerMillion;
        double cacheReadCost = (cacheReadTokens / 1_000_000.0) * cacheReadPricePerMillion;
        double cacheWriteCost = (cacheWriteTokens / 1_000_000.0) * cacheWritePricePerMillion;
        return inputCost + outputCost + cacheReadCost + cacheWriteCost;
    }

    /**
     * 简化版成本计算（不含 cache）。
     */
    public double calculateCost(long inputTokens, long outputTokens) {
        return calculateCost(inputTokens, outputTokens, 0, 0);
    }
}
