package com.nousresearch.hermes.metering;

import java.time.Instant;
import java.util.List;

/**
 * S3-1: 用量存储接口 — append-only 写入 + rollup 查询。
 */
public interface UsageStore {

    /**
     * 追加一条用量事件（append-only，不可修改）。
     */
    void append(UsageEvent event);

    /**
     * 查询用量汇总。
     *
     * @param tenantId 租户 ID
     * @param from 开始时间
     * @param to 结束时间
     * @param granularity 粒度：hour / day
     * @return 用量汇总列表
     */
    List<UsageSummary> query(String tenantId, Instant from, Instant to, String granularity);

    /**
     * 查询按 SKU 分组的用量。
     */
    List<UsageSummary> queryBySku(String tenantId, Instant from, Instant to);

    /**
     * 用量汇总记录。
     */
    record UsageSummary(
        Instant bucketStart,
        String tenantId,
        String sku,
        long totalQuantity,
        String unit,
        long eventCount
    ) {
        public double getAverage() {
            return eventCount > 0 ? (double) totalQuantity / eventCount : 0;
        }
    }
}
