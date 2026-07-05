package com.nousresearch.hermes.metering;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3-1: Metering / 计费桩测试
 */
class MeteringTest {

    // ========================================================================
    // UsageEvent
    // ========================================================================

    @Nested
    @DisplayName("UsageEvent")
    class EventTest {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            UsageEvent e = new UsageEvent(
                "tenant-1", "ws-1", UsageEvent.SKU_LLM_INPUT_TOKEN,
                500, UsageEvent.UNIT_TOKENS, "corr-1",
                Map.of("model", "gpt-4o")
            );
            assertEquals("tenant-1", e.getTenantId());
            assertEquals("ws-1", e.getWorkspaceId());
            assertEquals(UsageEvent.SKU_LLM_INPUT_TOKEN, e.getSku());
            assertEquals(500, e.getQuantity());
            assertEquals(UsageEvent.UNIT_TOKENS, e.getUnit());
            assertEquals("corr-1", e.getCorrelationId());
            assertEquals("gpt-4o", e.getDimensions().get("model"));
            assertNotNull(e.getEventId());
            assertNotNull(e.getOccurredAt());
        }

        @Test
        @DisplayName("null tenantId 抛 NPE")
        void nullTenantThrows() {
            assertThrows(NullPointerException.class, () ->
                new UsageEvent(null, "ws", "sku", 1, "unit", null, null));
        }

        @Test
        @DisplayName("null sku 抛 NPE")
        void nullSkuThrows() {
            assertThrows(NullPointerException.class, () ->
                new UsageEvent("t1", "ws", null, 1, "unit", null, null));
        }

        @Test
        @DisplayName("null dimensions → 空_map")
        void nullDimensions() {
            UsageEvent e = new UsageEvent("t1", "ws", "sku", 1, "unit", null, null);
            assertTrue(e.getDimensions().isEmpty());
        }

        @Test
        @DisplayName("dimensions 不可变")
        void dimensionsImmutable() {
            UsageEvent e = new UsageEvent("t1", "ws", "sku", 1, "unit", null,
                Map.of("key", "val"));
            assertThrows(UnsupportedOperationException.class, () ->
                e.getDimensions().put("new", "val"));
        }

        @Test
        @DisplayName("eventId 唯一")
        void eventIdUnique() {
            Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < 100; i++) {
                UsageEvent e = new UsageEvent("t1", "ws", "sku", 1, "unit", null, null);
                ids.add(e.getEventId());
            }
            assertEquals(100, ids.size());
        }

        @Test
        @DisplayName("SKU 常量")
        void skuConstants() {
            assertEquals("llm.input_token", UsageEvent.SKU_LLM_INPUT_TOKEN);
            assertEquals("llm.output_token", UsageEvent.SKU_LLM_OUTPUT_TOKEN);
            assertEquals("tool.exec", UsageEvent.SKU_TOOL_EXEC);
            assertEquals("sandbox.cpu_second", UsageEvent.SKU_SANDBOX_CPU);
            assertEquals("sandbox.gpu_second", UsageEvent.SKU_SANDBOX_GPU);
        }
    }

    // ========================================================================
    // InMemoryUsageStore
    // ========================================================================

    @Nested
    @DisplayName("InMemoryUsageStore")
    class StoreTest {

        private InMemoryUsageStore store;

        @BeforeEach
        void setUp() {
            store = new InMemoryUsageStore();
        }

        @Test
        @DisplayName("append 写入事件")
        void append() {
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_INPUT_TOKEN,
                100, UsageEvent.UNIT_TOKENS, null, null));
            assertEquals(1, store.eventCount());
        }

        @Test
        @DisplayName("append null 不崩溃")
        void appendNull() {
            store.append(null);
            assertEquals(0, store.eventCount());
        }

        @Test
        @DisplayName("query 按小时聚合")
        void queryHourly() {
            Instant now = Instant.now();
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_INPUT_TOKEN,
                100, UsageEvent.UNIT_TOKENS, null, null));
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_INPUT_TOKEN,
                200, UsageEvent.UNIT_TOKENS, null, null));
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_OUTPUT_TOKEN,
                50, UsageEvent.UNIT_TOKENS, null, null));

            List<UsageStore.UsageSummary> result = store.query(
                "t1", now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), "hour");

            assertFalse(result.isEmpty());
            // 应有 2 个 SKU 的汇总
            assertTrue(result.stream().anyMatch(s -> s.sku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN)));
            assertTrue(result.stream().anyMatch(s -> s.sku().equals(UsageEvent.SKU_LLM_OUTPUT_TOKEN)));

            // input_token 总量 = 300
            UsageStore.UsageSummary inputSummary = result.stream()
                .filter(s -> s.sku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN))
                .findFirst().orElse(null);
            assertNotNull(inputSummary);
            assertEquals(300, inputSummary.totalQuantity());
            assertEquals(2, inputSummary.eventCount());
        }

        @Test
        @DisplayName("query 按天聚合")
        void queryDaily() {
            Instant now = Instant.now();
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_TOOL_EXEC,
                1, UsageEvent.UNIT_COUNT, null, null));

            List<UsageStore.UsageSummary> result = store.query(
                "t1", now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), "day");

            assertFalse(result.isEmpty());
            assertEquals(UsageEvent.UNIT_COUNT, result.get(0).unit());
        }

        @Test
        @DisplayName("query 不同租户隔离")
        void tenantIsolation() {
            Instant now = Instant.now();
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_TOOL_EXEC,
                1, UsageEvent.UNIT_COUNT, null, null));
            store.append(new UsageEvent("t2", "ws", UsageEvent.SKU_TOOL_EXEC,
                1, UsageEvent.UNIT_COUNT, null, null));

            List<UsageStore.UsageSummary> r1 = store.query("t1",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), "hour");
            List<UsageStore.UsageSummary> r2 = store.query("t2",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), "hour");

            assertEquals(1, r1.get(0).eventCount());
            assertEquals(1, r2.get(0).eventCount());
        }

        @Test
        @DisplayName("queryBySku 按 SKU 分组")
        void queryBySku() {
            Instant now = Instant.now();
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_INPUT_TOKEN,
                100, UsageEvent.UNIT_TOKENS, null, null));
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_INPUT_TOKEN,
                200, UsageEvent.UNIT_TOKENS, null, null));
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_LLM_OUTPUT_TOKEN,
                50, UsageEvent.UNIT_TOKENS, null, null));
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_TOOL_EXEC,
                1, UsageEvent.UNIT_COUNT, null, null));

            List<UsageStore.UsageSummary> result = store.queryBySku("t1",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

            assertEquals(3, result.size());
            // input_token: 300 total, 2 events
            UsageStore.UsageSummary input = result.stream()
                .filter(s -> s.sku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN))
                .findFirst().orElse(null);
            assertNotNull(input);
            assertEquals(300, input.totalQuantity());
            assertEquals(2, input.eventCount());
        }

        @Test
        @DisplayName("时间范围过滤")
        void timeRangeFilter() {
            Instant now = Instant.now();
            store.append(new UsageEvent("t1", "ws", UsageEvent.SKU_TOOL_EXEC,
                1, UsageEvent.UNIT_COUNT, null, null));

            // 查询未来时间范围 → 空
            List<UsageStore.UsageSummary> future = store.query("t1",
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), "hour");
            assertTrue(future.isEmpty());
        }

        @Test
        @DisplayName("clear 清空")
        void clear() {
            store.append(new UsageEvent("t1", "ws", "sku", 1, "unit", null, null));
            store.clear();
            assertEquals(0, store.eventCount());
        }
    }

    // ========================================================================
    // MeteringService
    // ========================================================================

    @Nested
    @DisplayName("MeteringService")
    class ServiceTest {

        private InMemoryUsageStore store;
        private MeteringService service;

        @BeforeEach
        void setUp() {
            store = new InMemoryUsageStore();
            service = new MeteringService(store);
        }

        @Test
        @DisplayName("recordLlmCall 写入 input + output 两条事件")
        void recordLlmCall() {
            service.recordLlmCall("t1", "ws-1", "gpt-4o", 500, 200, "corr-1");
            assertEquals(2, store.eventCount());

            List<UsageEvent> events = store.getAllEvents();
            assertTrue(events.stream().anyMatch(e -> e.getSku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN)));
            assertTrue(events.stream().anyMatch(e -> e.getSku().equals(UsageEvent.SKU_LLM_OUTPUT_TOKEN)));
        }

        @Test
        @DisplayName("recordLlmCall input=0 只写 output")
        void recordLlmCallZeroInput() {
            service.recordLlmCall("t1", "ws-1", "gpt-4o", 0, 200, "corr-1");
            assertEquals(1, store.eventCount());
            assertEquals(UsageEvent.SKU_LLM_OUTPUT_TOKEN, store.getAllEvents().get(0).getSku());
        }

        @Test
        @DisplayName("recordToolExec 写入 tool.exec 事件")
        void recordToolExec() {
            service.recordToolExec("t1", "ws-1", "web_search", 1500, "corr-1");
            assertEquals(1, store.eventCount());
            UsageEvent e = store.getAllEvents().get(0);
            assertEquals(UsageEvent.SKU_TOOL_EXEC, e.getSku());
            assertEquals("web_search", e.getDimensions().get("tool"));
            assertEquals("1500", e.getDimensions().get("duration_ms"));
        }

        @Test
        @DisplayName("recordSandboxUsage 写入 cpu + gpu 两条事件")
        void recordSandbox() {
            service.recordSandboxUsage("t1", "ws-1", 30, 10, "corr-1");
            assertEquals(2, store.eventCount());
            assertTrue(store.getAllEvents().stream().anyMatch(e -> e.getSku().equals(UsageEvent.SKU_SANDBOX_CPU)));
            assertTrue(store.getAllEvents().stream().anyMatch(e -> e.getSku().equals(UsageEvent.SKU_SANDBOX_GPU)));
        }

        @Test
        @DisplayName("recordSandboxUsage 只有 cpu")
        void recordSandboxCpuOnly() {
            service.recordSandboxUsage("t1", "ws-1", 30, 0, "corr-1");
            assertEquals(1, store.eventCount());
            assertEquals(UsageEvent.SKU_SANDBOX_CPU, store.getAllEvents().get(0).getSku());
        }

        @Test
        @DisplayName("queryUsage 委托到 store")
        void queryUsage() {
            Instant now = Instant.now();
            service.recordLlmCall("t1", "ws-1", "gpt-4o", 100, 50, "corr-1");

            List<UsageStore.UsageSummary> result = service.queryUsage("t1",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), "hour");

            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("queryUsageBySku 委托到 store")
        void queryUsageBySku() {
            Instant now = Instant.now();
            service.recordLlmCall("t1", "ws-1", "gpt-4o", 100, 50, "corr-1");
            service.recordToolExec("t1", "ws-1", "web_search", 500, "corr-1");

            List<UsageStore.UsageSummary> result = service.queryUsageBySku("t1",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

            assertEquals(3, result.size()); // input_token + output_token + tool.exec
        }

        @Test
        @DisplayName("多租户用量隔离")
        void multiTenant() {
            service.recordLlmCall("t1", "ws-1", "gpt-4o", 100, 50, "c1");
            service.recordLlmCall("t2", "ws-2", "gpt-4o", 200, 100, "c2");
            service.recordLlmCall("t1", "ws-1", "gpt-4o", 300, 150, "c3");

            Instant now = Instant.now();
            List<UsageStore.UsageSummary> t1Result = service.queryUsageBySku("t1",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));
            List<UsageStore.UsageSummary> t2Result = service.queryUsageBySku("t2",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

            // t1: input=400, output=200
            UsageStore.UsageSummary t1Input = t1Result.stream()
                .filter(s -> s.sku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN)).findFirst().orElse(null);
            assertNotNull(t1Input);
            assertEquals(400, t1Input.totalQuantity());

            // t2: input=200, output=100
            UsageStore.UsageSummary t2Input = t2Result.stream()
                .filter(s -> s.sku().equals(UsageEvent.SKU_LLM_INPUT_TOKEN)).findFirst().orElse(null);
            assertNotNull(t2Input);
            assertEquals(200, t2Input.totalQuantity());
        }
    }

    // ========================================================================
    // UsageSummary
    // ========================================================================

    @Nested
    @DisplayName("UsageSummary")
    class SummaryTest {

        @Test
        @DisplayName("getAverage 计算平均值")
        void average() {
            UsageStore.UsageSummary s = new UsageStore.UsageSummary(
                Instant.now(), "t1", "sku", 300, "tokens", 3);
            assertEquals(100.0, s.getAverage(), 0.01);
        }

        @Test
        @DisplayName("eventCount=0 时 average=0")
        void zeroCount() {
            UsageStore.UsageSummary s = new UsageStore.UsageSummary(
                Instant.now(), "t1", "sku", 0, "tokens", 0);
            assertEquals(0.0, s.getAverage(), 0.01);
        }
    }
}
