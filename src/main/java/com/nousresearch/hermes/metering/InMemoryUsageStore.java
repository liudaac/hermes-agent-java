package com.nousresearch.hermes.metering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * S3-1: 内存用量存储（单实例 + 测试用）。
 *
 * <p>生产环境应使用 Postgres append-only 表 + 每小时 rollup。
 * 此实现用于功能验证和测试。</p>
 */
public class InMemoryUsageStore implements UsageStore {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryUsageStore.class);

    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(UsageEvent event) {
        if (event == null) return;
        events.add(event);
        logger.debug("Appended usage event: {}", event);
    }

    @Override
    public List<UsageSummary> query(String tenantId, Instant from, Instant to, String granularity) {
        ZoneId utc = ZoneOffset.UTC;

        return events.stream()
            .filter(e -> e.getTenantId().equals(tenantId))
            .filter(e -> !e.getOccurredAt().isBefore(from) && !e.getOccurredAt().isAfter(to))
            .collect(Collectors.groupingBy(
                e -> bucketStart(e.getOccurredAt(), granularity, utc),
                Collectors.groupingBy(UsageEvent::getSku,
                    Collectors.summarizingLong(UsageEvent::getQuantity))
            ))
            .entrySet().stream()
            .flatMap(bucketEntry -> bucketEntry.getValue().entrySet().stream()
                .map(skuEntry -> new UsageSummary(
                    bucketEntry.getKey(),
                    tenantId,
                    skuEntry.getKey(),
                    skuEntry.getValue().getSum(),
                    getUnitForSku(skuEntry.getKey()),
                    skuEntry.getValue().getCount()
                )))
            .sorted(Comparator.comparing(UsageSummary::bucketStart))
            .toList();
    }

    @Override
    public List<UsageSummary> queryBySku(String tenantId, Instant from, Instant to) {
        return events.stream()
            .filter(e -> e.getTenantId().equals(tenantId))
            .filter(e -> !e.getOccurredAt().isBefore(from) && !e.getOccurredAt().isAfter(to))
            .collect(Collectors.groupingBy(UsageEvent::getSku,
                Collectors.summarizingLong(UsageEvent::getQuantity)))
            .entrySet().stream()
            .map(entry -> new UsageSummary(
                from,
                tenantId,
                entry.getKey(),
                entry.getValue().getSum(),
                getUnitForSku(entry.getKey()),
                entry.getValue().getCount()
            ))
            .sorted(Comparator.comparing(UsageSummary::sku))
            .toList();
    }

    private Instant bucketStart(Instant time, String granularity, ZoneId zone) {
        if ("day".equalsIgnoreCase(granularity)) {
            return time.atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant();
        }
        // 默认 hourly
        return time.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant();
    }

    private String getUnitForSku(String sku) {
        return switch (sku) {
            case UsageEvent.SKU_LLM_INPUT_TOKEN, UsageEvent.SKU_LLM_OUTPUT_TOKEN -> UsageEvent.UNIT_TOKENS;
            case UsageEvent.SKU_TOOL_EXEC -> UsageEvent.UNIT_COUNT;
            case UsageEvent.SKU_SANDBOX_CPU, UsageEvent.SKU_SANDBOX_GPU -> UsageEvent.UNIT_SECONDS;
            default -> "unknown";
        };
    }

    /** 获取所有事件（测试用） */
    public List<UsageEvent> getAllEvents() {
        return Collections.unmodifiableList(events);
    }

    /** 清空（测试用） */
    public void clear() {
        events.clear();
    }

    /** 事件总数 */
    public int eventCount() {
        return events.size();
    }
}
