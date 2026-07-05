package com.nousresearch.hermes.metering;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4-4: UsageInsightsService 测试
 */
class UsageInsightsTest {

    private InMemoryUsageStore store;
    private MeteringService meteringService;
    private UsagePricingService pricingService;
    private UsageInsightsService insightsService;

    @BeforeEach
    void setUp() {
        store = new InMemoryUsageStore();
        meteringService = new MeteringService(store);
        pricingService = new UsagePricingService();
        insightsService = new UsageInsightsService(meteringService, pricingService);
    }

    // ========================================================================
    // generateReport
    // ========================================================================

    @Nested
    @DisplayName("generateReport")
    class GenerateReportTest {

        @Test
        @DisplayName("空数据 → 零值报告")
        void emptyData() {
            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 30);
            assertEquals("t1", report.tenantId());
            assertEquals(30, report.days());
            assertEquals(0, report.totalInputTokens());
            assertEquals(0, report.totalOutputTokens());
            assertEquals(0, report.totalTokens());
            assertEquals(0, report.estimatedCost(), 0.001);
            assertEquals(0, report.totalToolCalls());
        }

        @Test
        @DisplayName("有 LLM 调用数据 → 正确汇总")
        void withLlmData() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000, 500, "c1");
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 2000, 1000, "c2");
            meteringService.recordLlmCall("t2", "ws-2", "gpt-4o", 999, 0, "c3"); // 不同租户

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertEquals(3000, report.totalInputTokens());
            assertEquals(1500, report.totalOutputTokens());
            assertEquals(4500, report.totalTokens());
            assertTrue(report.estimatedCost() > 0);
        }

        @Test
        @DisplayName("有工具调用数据")
        void withToolData() {
            meteringService.recordToolExec("t1", "ws-1", "web_search", 500, "c1");
            meteringService.recordToolExec("t1", "ws-1", "file_read", 100, "c2");
            meteringService.recordToolExec("t1", "ws-1", "web_search", 300, "c3");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertEquals(3, report.totalToolCalls());
        }

        @Test
        @DisplayName("多 SKU 汇总")
        void multipleSku() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000, 500, "c1");
            meteringService.recordToolExec("t1", "ws-1", "search", 100, "c2");
            meteringService.recordSandboxUsage("t1", "ws-1", 60, 10, "c3");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertFalse(report.skuSummaries().isEmpty());
            // 应包含 input_token, output_token, tool.exec, sandbox.cpu, sandbox.gpu
            assertTrue(report.skuSummaries().size() >= 4);
        }

        @Test
        @DisplayName("租户隔离")
        void tenantIsolation() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000, 500, "c1");
            meteringService.recordLlmCall("t2", "ws-2", "gpt-4o", 2000, 1000, "c2");

            UsageInsightsService.UsageInsightReport r1 = insightsService.generateReport("t1", 1);
            UsageInsightsService.UsageInsightReport r2 = insightsService.generateReport("t2", 1);
            assertEquals(1500, r1.totalTokens());
            assertEquals(3000, r2.totalTokens());
        }

        @Test
        @DisplayName("avgTokensPerDay 计算")
        void avgTokensPerDay() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 3000, 1000, "c1");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 7);
            double avg = report.avgTokensPerDay();
            assertTrue(avg > 0);
            assertEquals(4000.0 / 7, avg, 0.01);
        }

        @Test
        @DisplayName("avgCostPerDay 计算")
        void avgCostPerDay() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000000, 500000, "c1");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 30);
            assertTrue(report.avgCostPerDay() > 0);
        }

        @Test
        @DisplayName("hourlyTrend 非空（有数据时）")
        void hourlyTrend() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000, 500, "c1");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertFalse(report.hourlyTrend().isEmpty());
        }

        @Test
        @DisplayName("mostExpensiveHour 有值（有数据时）")
        void mostExpensiveHour() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000, 500, "c1");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertNotNull(report.mostExpensiveHour());
            assertTrue(report.mostExpensiveHour().cost() > 0);
        }

        @Test
        @DisplayName("mostExpensiveHour 为 null（无数据时）")
        void mostExpensiveHourNull() {
            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertNull(report.mostExpensiveHour());
        }
    }

    // ========================================================================
    // SkuSummary
    // ========================================================================

    @Nested
    @DisplayName("SkuSummary")
    class SkuSummaryTest {

        @Test
        @DisplayName("average 计算")
        void average() {
            UsageInsightsService.SkuSummary s = new UsageInsightsService.SkuSummary(
                "llm.input_token", 3000, "tokens", 3);
            assertEquals(1000.0, s.average(), 0.01);
        }

        @Test
        @DisplayName("eventCount=0 → average=0")
        void zeroCount() {
            UsageInsightsService.SkuSummary s = new UsageInsightsService.SkuSummary(
                "sku", 0, "unit", 0);
            assertEquals(0, s.average(), 0.01);
        }
    }

    // ========================================================================
    // HourlyTrend
    // ========================================================================

    @Nested
    @DisplayName("HourlyTrend")
    class HourlyTrendTest {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            Instant now = Instant.now();
            UsageInsightsService.HourlyTrend t = new UsageInsightsService.HourlyTrend(now, 5000, 0.15);
            assertEquals(now, t.bucket());
            assertEquals(5000, t.tokens());
            assertEquals(0.15, t.cost(), 0.001);
        }
    }

    // ========================================================================
    // 报告完整性
    // ========================================================================

    @Nested
    @DisplayName("报告完整性")
    class ReportIntegrityTest {

        @Test
        @DisplayName("from < to")
        void fromBeforeTo() {
            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 7);
            assertTrue(report.from().isBefore(report.to()));
        }

        @Test
        @DisplayName("days=1 最小范围")
        void oneDay() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 100, 50, "c1");
            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 1);
            assertEquals(1, report.days());
            assertEquals(150, report.totalTokens());
        }

        @Test
        @DisplayName("days=90 最大范围")
        void ninetyDays() {
            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 90);
            assertEquals(90, report.days());
        }

        @Test
        @DisplayName("报告包含完整字段")
        void allFieldsPresent() {
            meteringService.recordLlmCall("t1", "ws-1", "gpt-4o", 1000, 500, "c1");
            meteringService.recordToolExec("t1", "ws-1", "search", 100, "c2");

            UsageInsightsService.UsageInsightReport report = insightsService.generateReport("t1", 7);
            assertNotNull(report.tenantId());
            assertNotNull(report.from());
            assertNotNull(report.to());
            assertTrue(report.totalTokens() > 0);
            assertTrue(report.estimatedCost() >= 0);
            assertNotNull(report.skuSummaries());
            assertNotNull(report.hourlyTrend());
        }
    }
}
