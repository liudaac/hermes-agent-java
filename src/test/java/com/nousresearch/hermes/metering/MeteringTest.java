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

    }
}
