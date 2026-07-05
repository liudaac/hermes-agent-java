package com.nousresearch.hermes.metering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S4-2: 用量定价服务 — 根据模型定价计算成本。
 *
 * <p>内建一份常见模型的定价表，可通过 config override。</p>
 *
 * <p>对齐 Python 原版 {@code agent/usage_pricing.py}：</p>
 * <ul>
 *   <li>支持 cache read / cache write 分开定价</li>
 *   <li>数据源：内建 pricing + config override</li>
 *   <li>输出：{@link CostEstimate} 含分项明细</li>
 * </ul>
 */
public class UsagePricingService {
    private static final Logger logger = LoggerFactory.getLogger(UsagePricingService.class);

    private final Map<String, ModelPricing> pricingTable = new ConcurrentHashMap<>();

    public UsagePricingService() {
        initDefaultPricing();
    }

    /**
     * 内建常见模型定价（2026 年市场价，可 override）。
     */
    private void initDefaultPricing() {
        // Anthropic
        register(new ModelPricing("claude-3.5-sonnet", "anthropic", 3.0, 15.0, 0.3, 3.75));
        register(new ModelPricing("claude-3.5-haiku", "anthropic", 0.8, 4.0, 0.08, 1.0));
        register(new ModelPricing("claude-3-opus", "anthropic", 15.0, 75.0, 1.5, 18.75));

        // OpenAI
        register(new ModelPricing("gpt-4o", "openai", 2.5, 10.0, 1.25, 2.5));
        register(new ModelPricing("gpt-4o-mini", "openai", 0.15, 0.6, 0.075, 0.15));
        register(new ModelPricing("gpt-4-turbo", "openai", 10.0, 30.0, 0, 0));
        register(new ModelPricing("o1", "openai", 15.0, 60.0, 7.5, 15.0));
        register(new ModelPricing("o1-mini", "openai", 3.0, 12.0, 1.5, 3.0));

        // DeepSeek
        register(new ModelPricing("deepseek-chat", "deepseek", 0.14, 0.28, 0.014, 0.14));
        register(new ModelPricing("deepseek-reasoner", "deepseek", 0.55, 2.19, 0.055, 0.55));

        // Volcengine (Doubao)
        register(new ModelPricing("doubao-pro-32k", "volcengine", 0.8, 2.0, 0, 0));
        register(new ModelPricing("doubao-pro-128k", "volcengine", 5.0, 12.0, 0, 0));
        register(new ModelPricing("doubao-lite-32k", "volcengine", 0.3, 0.6, 0, 0));

        // Alibaba (Qwen)
        register(new ModelPricing("qwen-max", "alibaba", 2.8, 8.4, 0, 0));
        register(new ModelPricing("qwen-plus", "alibaba", 0.4, 1.2, 0, 0));
        register(new ModelPricing("qwen-turbo", "alibaba", 0.05, 0.2, 0, 0));

        // OpenRouter (generic)
        register(new ModelPricing("anthropic/claude-3.5-sonnet", "openrouter", 3.0, 15.0, 0.3, 3.75));

        logger.info("Loaded {} model pricing entries", pricingTable.size());
    }

    /**
     * 注册或 override 模型定价。
     */
    public void register(ModelPricing pricing) {
        pricingTable.put(pricing.model().toLowerCase(), pricing);
    }

    /**
     * 批量注册（从 config 加载）。
     */
    public void registerAll(Collection<ModelPricing> pricings) {
        for (ModelPricing p : pricings) {
            register(p);
        }
    }

    /**
     * 获取模型定价。先精确匹配，再模糊匹配（前缀/包含）。
     */
    public Optional<ModelPricing> getPricing(String model) {
        if (model == null || model.isBlank()) return Optional.empty();

        // 精确匹配
        ModelPricing exact = pricingTable.get(model.toLowerCase());
        if (exact != null) return Optional.of(exact);

        // 模糊匹配：找包含 model 名的 key（分隔符统一比较）
        String lower = model.toLowerCase();
        String normalizedLower = lower.replace("-", "").replace(".", "").replace("_", "");
        for (var entry : pricingTable.entrySet()) {
            String normalizedKey = entry.getKey().replace("-", "").replace(".", "").replace("_", "");
            if (normalizedLower.contains(normalizedKey) || normalizedKey.contains(normalizedLower)) {
                return Optional.of(entry.getValue());
            }
        }

        return Optional.empty();
    }

    /**
     * 估算单次 LLM 调用成本。
     *
     * @param model 模型名
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @param cacheReadTokens cache read token（0 如果没有）
     * @param cacheWriteTokens cache write token（0 如果没有）
     * @return 成本估算（含分项明细），如果模型未找到则返回零成本估算
     */
    public CostEstimate estimateCost(String model, long inputTokens, long outputTokens,
                                      long cacheReadTokens, long cacheWriteTokens) {
        Optional<ModelPricing> opt = getPricing(model);
        if (opt.isEmpty()) {
            logger.debug("No pricing found for model: {}", model);
            return new CostEstimate(model, 0, 0, 0, 0, 0, false);
        }

        ModelPricing p = opt.get();
        double inputCost = (inputTokens / 1_000_000.0) * p.inputPricePerMillion();
        double outputCost = (outputTokens / 1_000_000.0) * p.outputPricePerMillion();
        double cacheReadCost = (cacheReadTokens / 1_000_000.0) * p.cacheReadPricePerMillion();
        double cacheWriteCost = (cacheWriteTokens / 1_000_000.0) * p.cacheWritePricePerMillion();
        double total = inputCost + outputCost + cacheReadCost + cacheWriteCost;

        return new CostEstimate(
            model, inputCost, outputCost, cacheReadCost, cacheWriteCost, total, true
        );
    }

    /**
     * 简化版成本估算（不含 cache）。
     */
    public CostEstimate estimateCost(String model, long inputTokens, long outputTokens) {
        return estimateCost(model, inputTokens, outputTokens, 0, 0);
    }

    /**
     * 列出所有已注册的模型定价。
     */
    public Map<String, ModelPricing> getAllPricing() {
        return Collections.unmodifiableMap(pricingTable);
    }

    /**
     * 成本估算结果（含分项明细）。
     */
    public record CostEstimate(
        String model,
        double inputCost,
        double outputCost,
        double cacheReadCost,
        double cacheWriteCost,
        double totalCost,
        boolean pricingFound
    ) {
        /** 总成本（便捷方法） */
        public double total() { return totalCost; }

        /** 是否找到了定价 */
        public boolean hasPricing() { return pricingFound; }

        @Override
        public String toString() {
            if (!pricingFound) return "CostEstimate{model=" + model + ", pricing=NOT_FOUND}";
            return String.format("CostEstimate{model=%s, input=$%.6f, output=$%.6f, cacheRead=$%.6f, cacheWrite=$%.6f, total=$%.6f}",
                model, inputCost, outputCost, cacheReadCost, cacheWriteCost, totalCost);
        }
    }
}
