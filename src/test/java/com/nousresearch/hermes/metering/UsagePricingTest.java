package com.nousresearch.hermes.metering;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4-2: UsagePricingService 测试
 */
class UsagePricingTest {

    private UsagePricingService service;

    @BeforeEach
    void setUp() {
        service = new UsagePricingService();
    }

    // ========================================================================
    // ModelPricing
    // ========================================================================

    @Nested
    @DisplayName("ModelPricing")
    class PricingTest {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            ModelPricing p = new ModelPricing("gpt-4o", "openai", 2.5, 10.0, 1.25, 2.5);
            assertEquals("gpt-4o", p.model());
            assertEquals("openai", p.provider());
            assertEquals(2.5, p.inputPricePerMillion());
            assertEquals(10.0, p.outputPricePerMillion());
            assertEquals(1.25, p.cacheReadPricePerMillion());
            assertEquals(2.5, p.cacheWritePricePerMillion());
        }

        @Test
        @DisplayName("null provider → unknown")
        void nullProvider() {
            ModelPricing p = new ModelPricing("model", null, 1, 2, 0, 0);
            assertEquals("unknown", p.provider());
        }

        @Test
        @DisplayName("负价格归零")
        void negativePriceClamped() {
            ModelPricing p = new ModelPricing("model", "p", -1, -2, -3, -4);
            assertEquals(0, p.inputPricePerMillion());
            assertEquals(0, p.outputPricePerMillion());
            assertEquals(0, p.cacheReadPricePerMillion());
            assertEquals(0, p.cacheWritePricePerMillion());
        }

        @Test
        @DisplayName("calculateCost 不含 cache")
        void calculateCostNoCache() {
            ModelPricing p = new ModelPricing("test", "p", 3.0, 15.0, 0.3, 3.75);
            // 1M input × $3 = $3, 1M output × $15 = $15, total = $18
            double cost = p.calculateCost(1_000_000, 1_000_000);
            assertEquals(18.0, cost, 0.001);
        }

        @Test
        @DisplayName("calculateCost 含 cache")
        void calculateCostWithCache() {
            ModelPricing p = new ModelPricing("claude", "anthropic", 3.0, 15.0, 0.3, 3.75);
            // 1M input × $3 + 500K output × $15 + 100K cacheRead × $0.3 + 200K cacheWrite × $3.75
            double cost = p.calculateCost(1_000_000, 500_000, 100_000, 200_000);
            double expected = 3.0 + 7.5 + 0.03 + 0.75;
            assertEquals(expected, cost, 0.001);
        }

        @Test
        @DisplayName("calculateCost 零 token")
        void zeroTokens() {
            ModelPricing p = new ModelPricing("test", "p", 3.0, 15.0, 0.3, 3.75);
            assertEquals(0.0, p.calculateCost(0, 0, 0, 0), 0.0001);
        }
    }

    // ========================================================================
    // UsagePricingService — 定价表
    // ========================================================================

    @Nested
    @DisplayName("UsagePricingService 定价表")
    class PricingTableTest {

        @Test
        @DisplayName("内建定价表非空")
        void defaultTableNotEmpty() {
            assertFalse(service.getAllPricing().isEmpty());
        }

        @Test
        @DisplayName("getPricing 精确匹配")
        void exactMatch() {
            assertTrue(service.getPricing("gpt-4o").isPresent());
            assertTrue(service.getPricing("claude-3.5-sonnet").isPresent());
            assertTrue(service.getPricing("deepseek-chat").isPresent());
            assertTrue(service.getPricing("qwen-max").isPresent());
        }

        @Test
        @DisplayName("getPricing 大小写不敏感")
        void caseInsensitive() {
            assertTrue(service.getPricing("GPT-4O").isPresent());
            assertTrue(service.getPricing("Claude-3.5-Sonnet").isPresent());
        }

        @Test
        @DisplayName("getPricing 模糊匹配（包含）")
        void fuzzyMatch() {
            // "gpt-4o-2024-08-06" 应匹配 "gpt-4o"
            assertTrue(service.getPricing("gpt-4o-2024-08-06").isPresent());
            // "claude-3-5-sonnet-20241022" 应匹配包含 "claude-3.5-sonnet" 或反过来
            assertTrue(service.getPricing("claude-3-5-sonnet-latest").isPresent());
        }

        @Test
        @DisplayName("getPricing 未知模型 → empty")
        void unknownModel() {
            assertTrue(service.getPricing("unknown-model-xyz").isEmpty());
        }

        @Test
        @DisplayName("getPricing null/空 → empty")
        void nullOrEmpty() {
            assertTrue(service.getPricing(null).isEmpty());
            assertTrue(service.getPricing("").isEmpty());
            assertTrue(service.getPricing("   ").isEmpty());
        }

        @Test
        @DisplayName("register 新模型")
        void registerNew() {
            service.register(new ModelPricing("custom-model", "custom", 1.0, 2.0, 0, 0));
            assertTrue(service.getPricing("custom-model").isPresent());
        }

        @Test
        @DisplayName("register override 已有模型")
        void registerOverride() {
            service.register(new ModelPricing("gpt-4o", "openai", 999, 999, 0, 0));
            ModelPricing p = service.getPricing("gpt-4o").get();
            assertEquals(999, p.inputPricePerMillion());
        }

        @Test
        @DisplayName("registerAll 批量注册")
        void registerAll() {
            List<ModelPricing> batch = List.of(
                new ModelPricing("batch-1", "p", 1, 2, 0, 0),
                new ModelPricing("batch-2", "p", 3, 4, 0, 0)
            );
            service.registerAll(batch);
            assertTrue(service.getPricing("batch-1").isPresent());
            assertTrue(service.getPricing("batch-2").isPresent());
        }
    }

    // ========================================================================
    // estimateCost
    // ========================================================================

    @Nested
    @DisplayName("estimateCost 成本估算")
    class EstimateCostTest {

        @Test
        @DisplayName("gpt-4o 基本估算")
        void gpt4oBasic() {
            // gpt-4o: input $2.5/M, output $10/M
            UsagePricingService.CostEstimate est = service.estimateCost("gpt-4o", 1_000_000, 500_000);
            assertTrue(est.hasPricing());
            assertEquals(2.5, est.inputCost(), 0.001);
            assertEquals(5.0, est.outputCost(), 0.001);
            assertEquals(0, est.cacheReadCost(), 0.001);
            assertEquals(0, est.cacheWriteCost(), 0.001);
            assertEquals(7.5, est.total(), 0.001);
        }

        @Test
        @DisplayName("claude-3.5-sonnet 含 cache")
        void claudeWithCache() {
            // claude-3.5-sonnet: input $3/M, output $15/M, cacheRead $0.3/M, cacheWrite $3.75/M
            UsagePricingService.CostEstimate est = service.estimateCost(
                "claude-3.5-sonnet", 1_000_000, 500_000, 200_000, 100_000);
            assertTrue(est.hasPricing());
            assertEquals(3.0, est.inputCost(), 0.001);
            assertEquals(7.5, est.outputCost(), 0.001);
            assertEquals(0.06, est.cacheReadCost(), 0.001);
            assertEquals(0.375, est.cacheWriteCost(), 0.001);
            assertTrue(est.total() > 10.0);
        }

        @Test
        @DisplayName("deepseek-chat 低价模型")
        void deepseekLowCost() {
            // deepseek-chat: input $0.14/M, output $0.28/M
            UsagePricingService.CostEstimate est = service.estimateCost("deepseek-chat", 1_000_000, 1_000_000);
            assertTrue(est.hasPricing());
            assertEquals(0.14, est.inputCost(), 0.001);
            assertEquals(0.28, est.outputCost(), 0.001);
            assertEquals(0.42, est.total(), 0.001);
        }

        @Test
        @DisplayName("未知模型 → pricingFound=false, cost=0")
        void unknownModel() {
            UsagePricingService.CostEstimate est = service.estimateCost("unknown-xyz", 1000, 1000);
            assertFalse(est.hasPricing());
            assertEquals(0, est.total(), 0.0001);
        }

        @Test
        @DisplayName("零 token → 零成本")
        void zeroTokens() {
            UsagePricingService.CostEstimate est = service.estimateCost("gpt-4o", 0, 0);
            assertTrue(est.hasPricing());
            assertEquals(0, est.total(), 0.0001);
        }

        @Test
        @DisplayName("简化版 estimateCost（不含 cache）")
        void simplifiedVersion() {
            UsagePricingService.CostEstimate est = service.estimateCost("gpt-4o", 500_000, 250_000);
            assertEquals(1.25, est.inputCost(), 0.001);
            assertEquals(2.5, est.outputCost(), 0.001);
            assertEquals(0, est.cacheReadCost(), 0.001);
            assertEquals(0, est.cacheWriteCost(), 0.001);
        }

        @Test
        @DisplayName("CostEstimate toString")
        void toStringTest() {
            UsagePricingService.CostEstimate est = service.estimateCost("gpt-4o", 1000, 500);
            String s = est.toString();
            assertTrue(s.contains("gpt-4o"));
            assertTrue(s.contains("$"));

            UsagePricingService.CostEstimate notFound = service.estimateCost("unknown", 1, 1);
            String s2 = notFound.toString();
            assertTrue(s2.contains("NOT_FOUND"));
        }

        @Test
        @DisplayName("多模型成本对比")
        void multiModelComparison() {
            long inputTokens = 2_000_000;
            long outputTokens = 1_000_000;

            double gpt4oCost = service.estimateCost("gpt-4o", inputTokens, outputTokens).total();
            double deepseekCost = service.estimateCost("deepseek-chat", inputTokens, outputTokens).total();
            double claudeCost = service.estimateCost("claude-3.5-sonnet", inputTokens, outputTokens).total();

            // DeepSeek 应该最便宜
            assertTrue(deepseekCost < gpt4oCost, "DeepSeek should be cheaper than GPT-4o");
            assertTrue(deepseekCost < claudeCost, "DeepSeek should be cheaper than Claude");
            // Claude 应该最贵
            assertTrue(claudeCost > gpt4oCost, "Claude should be more expensive than GPT-4o");
        }
    }
}
