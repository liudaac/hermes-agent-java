package com.nousresearch.hermes.metering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S4-4: 用量洞察服务 — 基于 S3-1 MeteringService + S4-2 UsagePricingService
 * 生成 token/成本/工具 top-K/模型分布/最贵 session 等维度洞察。
 *
 * <p>对齐计划：GET /api/insights?tenant=&days=30</p>
 */
public class UsageInsightsService {
    private static final Logger logger = LoggerFactory.getLogger(UsageInsightsService.class);

    private final MeteringService meteringService;
    private final UsagePricingService pricingService;

    public UsageInsightsService(MeteringService meteringService, UsagePricingService pricingService) {
        this.meteringService = meteringService;
        this.pricingService = pricingService;
    }

    /**
     * 生成用量洞察报告。
     *
     * @param tenantId 租户 ID
     * @param days 统计天数
     * @return 洞察报告
     */
    public UsageInsightReport generateReport(String tenantId, int days) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);

        List<UsageStore.UsageSummary> hourlySummaries = meteringService.queryUsage(tenantId, from, now, "hour");
        List<UsageStore.UsageSummary> bySku = meteringService.queryUsageBySku(tenantId, from, now);

        // 总 token 用量
        long totalInputTokens = sumQuantity(bySku, UsageEvent.SKU_LLM_INPUT_TOKEN);
        long totalOutputTokens = sumQuantity(bySku, UsageEvent.SKU_LLM_OUTPUT_TOKEN);
        long totalTokens = totalInputTokens + totalOutputTokens;

        // 总成本估算（基于 SKU 汇总 × 模型定价）
        double estimatedCost = estimateTotalCost(bySku);

        // 工具调用 top-K
        long totalToolCalls = sumQuantity(bySku, UsageEvent.SKU_TOOL_EXEC);
        List<ToolUsageStat> toolTopK = extractToolStats(hourlySummaries);

        // 按 SKU 汇总
        List<SkuSummary> skuSummaries = bySku.stream()
            .map(s -> new SkuSummary(s.sku(), s.totalQuantity(), s.unit(), s.eventCount()))
            .sorted(Comparator.comparing(SkuSummary::totalQuantity).reversed())
            .toList();

        // 小时趋势
        List<HourlyTrend> hourlyTrend = hourlySummaries.stream()
            .filter(s -> s.sku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN) || s.sku().equals(UsageEvent.SKU_LLM_OUTPUT_TOKEN))
            .collect(Collectors.groupingBy(UsageStore.UsageSummary::bucketStart))
            .entrySet().stream()
            .map(e -> {
                long tokens = e.getValue().stream().mapToLong(UsageStore.UsageSummary::totalQuantity).sum();
                double cost = e.getValue().stream()
                    .mapToDouble(s -> estimateSkuCost(s.sku(), s.totalQuantity()))
                    .sum();
                return new HourlyTrend(e.getKey(), tokens, cost);
            })
            .sorted(Comparator.comparing(HourlyTrend::bucket))
            .toList();

        // 最贵小时
        HourlyTrend mostExpensiveHour = hourlyTrend.stream()
            .max(Comparator.comparing(HourlyTrend::cost))
            .orElse(null);

        return new UsageInsightReport(
            tenantId, days, from, now,
            totalInputTokens, totalOutputTokens, totalTokens,
            estimatedCost, totalToolCalls,
            toolTopK, skuSummaries, hourlyTrend, mostExpensiveHour
        );
    }

    private long sumQuantity(List<UsageStore.UsageSummary> summaries, String sku) {
        return summaries.stream()
            .filter(s -> s.sku().equals(sku))
            .mapToLong(UsageStore.UsageSummary::totalQuantity)
            .sum();
    }

    private double estimateTotalCost(List<UsageStore.UsageSummary> bySku) {
        return bySku.stream()
            .mapToDouble(s -> estimateSkuCost(s.sku(), s.totalQuantity()))
            .sum();
    }

    private double estimateSkuCost(String sku, long quantity) {
        // 粗略估算：input/output token 用平均价格 $3/M
        // 精确估算需要按 model dimension 拆分（未来增强）
        return switch (sku) {
            case UsageEvent.SKU_LLM_INPUT_TOKEN -> (quantity / 1_000_000.0) * 3.0;  // 平均 $3/M
            case UsageEvent.SKU_LLM_OUTPUT_TOKEN -> (quantity / 1_000_000.0) * 15.0; // 平均 $15/M
            case UsageEvent.SKU_SANDBOX_CPU -> (quantity / 3600.0) * 0.05; // $0.05/hour
            case UsageEvent.SKU_SANDBOX_GPU -> (quantity / 3600.0) * 0.80; // $0.80/hour
            default -> 0;
        };
    }

    private List<ToolUsageStat> extractToolStats(List<UsageStore.UsageSummary> hourlySummaries) {
        // 从 dimensions 提取 tool name（如果有的话）
        // 目前 UsageSummary 不含 dimensions，返回空列表占位
        // 未来增强：从 UsageEvent 级别查询提取
        return List.of();
    }

    // ============ 报告数据类 ============

    public record UsageInsightReport(
        String tenantId,
        int days,
        Instant from,
        Instant to,
        long totalInputTokens,
        long totalOutputTokens,
        long totalTokens,
        double estimatedCost,
        long totalToolCalls,
        List<ToolUsageStat> toolTopK,
        List<SkuSummary> skuSummaries,
        List<HourlyTrend> hourlyTrend,
        HourlyTrend mostExpensiveHour
    ) {
        public double avgTokensPerDay() {
            return days > 0 ? (double) totalTokens / days : 0;
        }

        public double avgCostPerDay() {
            return days > 0 ? estimatedCost / days : 0;
        }
    }

    public record ToolUsageStat(String toolName, long callCount, long avgDurationMs) {}

    public record SkuSummary(String sku, long totalQuantity, String unit, long eventCount) {
        public double average() {
            return eventCount > 0 ? (double) totalQuantity / eventCount : 0;
        }
    }

    public record HourlyTrend(Instant bucket, long tokens, double cost) {}
}
