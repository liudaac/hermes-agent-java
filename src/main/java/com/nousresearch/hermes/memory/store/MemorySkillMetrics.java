package com.nousresearch.hermes.memory.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics collector for MemoryStore and SkillStore operations.
 *
 * <p>Tracks operation counts, latency, decay cycle stats, and cache hit rates.
 * Exports in Prometheus text format for Grafana dashboards.</p>
 *
 * <h2>Metrics exposed</h2>
 * <pre>
 *   hermes_memory_writes_total{tenant, type=session|longterm|experience}
 *   hermes_memory_reads_total{tenant, type=session|longterm|experience}
 *   hermes_memory_search_latency_ms{tenant}
 *   hermes_memory_search_results_count{tenant}
 *   hermes_memory_decay_compressed_total{tenant}
 *   hermes_memory_decay_evicted_total{tenant}
 *   hermes_memory_decay_facts_extracted_total{tenant}
 *   hermes_memory_decay_duration_ms{tenant}
 *   hermes_memory_longterm_count{tenant}
 *   hermes_memory_session_count{tenant}
 *
 *   hermes_skill_registered_total{tenant, scope}
 *   hermes_skill_unregistered_total{tenant}
 *   hermes_skill_enabled_total{tenant}
 *   hermes_skill_disabled_total{tenant}
 *   hermes_skill_version_published_total{tenant}
 *   hermes_skill_version_rolled_back_total{tenant}
 *   hermes_skill_active_count{tenant, scope}
 *   hermes_skill_change_notifications_total
 * </pre>
 */
public class MemorySkillMetrics {

    private static final Logger logger = LoggerFactory.getLogger(MemorySkillMetrics.class);
    private static final MemorySkillMetrics INSTANCE = new MemorySkillMetrics();

    // Memory metrics: tenant -> metric -> value
    private final ConcurrentHashMap<String, Map<String, AtomicLong>> memoryMetrics = new ConcurrentHashMap<>();
    // Skill metrics: tenant -> metric -> value
    private final ConcurrentHashMap<String, Map<String, AtomicLong>> skillMetrics = new ConcurrentHashMap<>();
    // Global skill change notification count
    private final AtomicLong skillChangeNotifications = new AtomicLong(0);
    // Latency tracking: tenant -> total ms + count for avg
    private final ConcurrentHashMap<String, long[]> searchLatency = new ConcurrentHashMap<>();

    public static MemorySkillMetrics getInstance() {
        return INSTANCE;
    }

    private MemorySkillMetrics() {}

    // ══════════════════════════════════════════════════════════════════
    //  Memory recording
    // ══════════════════════════════════════════════════════════════════

    public void recordSessionWrite(String tenantId) {
        increment(memoryMetrics, tenantId, "memory_session_writes_total");
    }

    public void recordSessionRead(String tenantId) {
        increment(memoryMetrics, tenantId, "memory_session_reads_total");
    }

    public void recordLongTermWrite(String tenantId) {
        increment(memoryMetrics, tenantId, "memory_longterm_writes_total");
    }

    public void recordLongTermRead(String tenantId) {
        increment(memoryMetrics, tenantId, "memory_longterm_reads_total");
    }

    public void recordExperienceWrite(String tenantId) {
        increment(memoryMetrics, tenantId, "memory_experience_writes_total");
    }

    public void recordExperienceRead(String tenantId) {
        increment(memoryMetrics, tenantId, "memory_experience_reads_total");
    }

    public void recordSearch(String tenantId, long durationMs, int resultCount) {
        increment(memoryMetrics, tenantId, "memory_search_total");
        increment(memoryMetrics, tenantId, "memory_search_latency_ms_total", durationMs);
        increment(memoryMetrics, tenantId, "memory_search_results_count_total", resultCount);
        // Track for average
        searchLatency.computeIfAbsent(tenantId, k -> new long[]{0, 0});
        searchLatency.get(tenantId)[0] += durationMs;
        searchLatency.get(tenantId)[1]++;
    }

    public void recordDecay(String tenantId, int compressed, int evicted,
                            int factsExtracted, long durationMs) {
        if (compressed > 0) {
            increment(memoryMetrics, tenantId, "memory_decay_compressed_total", compressed);
        }
        if (evicted > 0) {
            increment(memoryMetrics, tenantId, "memory_decay_evicted_total", evicted);
        }
        if (factsExtracted > 0) {
            increment(memoryMetrics, tenantId, "memory_decay_facts_extracted_total", factsExtracted);
        }
        increment(memoryMetrics, tenantId, "memory_decay_duration_ms_total", durationMs);
        increment(memoryMetrics, tenantId, "memory_decay_cycles_total");
    }

    public void recordSessionCount(String tenantId, int count) {
        set(memoryMetrics, tenantId, "memory_session_count", count);
    }

    public void recordLongTermCount(String tenantId, int count) {
        set(memoryMetrics, tenantId, "memory_longterm_count", count);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Skill recording
    // ══════════════════════════════════════════════════════════════════

    public void recordSkillRegistered(String tenantId, String scope) {
        increment(skillMetrics, tenantId, "skill_registered_total");
        increment(skillMetrics, tenantId, "skill_registered_" + scope.toLowerCase() + "_total");
    }

    public void recordSkillUnregistered(String tenantId) {
        increment(skillMetrics, tenantId, "skill_unregistered_total");
    }

    public void recordSkillEnabled(String tenantId) {
        increment(skillMetrics, tenantId, "skill_enabled_total");
    }

    public void recordSkillDisabled(String tenantId) {
        increment(skillMetrics, tenantId, "skill_disabled_total");
    }

    public void recordVersionPublished(String tenantId) {
        increment(skillMetrics, tenantId, "skill_version_published_total");
    }

    public void recordVersionRollback(String tenantId) {
        increment(skillMetrics, tenantId, "skill_version_rolled_back_total");
    }

    public void recordSkillChangeNotification() {
        skillChangeNotifications.incrementAndGet();
    }

    public void recordActiveSkillCount(String tenantId, String scope, int count) {
        set(skillMetrics, tenantId, "skill_active_" + scope.toLowerCase() + "_count", count);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Prometheus export
    // ══════════════════════════════════════════════════════════════════

    public String exportPrometheus() {
        StringBuilder sb = new StringBuilder(4096);

        // Memory metrics
        for (var tenantEntry : memoryMetrics.entrySet()) {
            String tenant = tenantEntry.getKey();
            for (var metricEntry : tenantEntry.getValue().entrySet()) {
                String metric = metricEntry.getKey();
                long value = metricEntry.getValue().get();
                sb.append("# TYPE hermes_").append(metric).append(" counter\n");
                sb.append("hermes_").append(metric)
                  .append("{tenant=\"").append(tenant).append("\"} ")
                  .append(value).append('\n');
            }
        }

        // Search latency average
        for (var entry : searchLatency.entrySet()) {
            String tenant = entry.getKey();
            long[] data = entry.getValue();
            if (data[1] > 0) {
                double avg = (double) data[0] / data[1];
                sb.append("# TYPE hermes_memory_search_latency_avg_ms gauge\n");
                sb.append("hermes_memory_search_latency_avg_ms")
                  .append("{tenant=\"").append(tenant).append("\"} ")
                  .append(String.format("%.2f", avg)).append('\n');
            }
        }

        // Skill metrics
        for (var tenantEntry : skillMetrics.entrySet()) {
            String tenant = tenantEntry.getKey();
            for (var metricEntry : tenantEntry.getValue().entrySet()) {
                String metric = metricEntry.getKey();
                long value = metricEntry.getValue().get();
                sb.append("# TYPE hermes_").append(metric).append(" counter\n");
                sb.append("hermes_").append(metric)
                  .append("{tenant=\"").append(tenant).append("\"} ")
                  .append(value).append('\n');
            }
        }

        // Global skill change notifications
        sb.append("# TYPE hermes_skill_change_notifications_total counter\n");
        sb.append("hermes_skill_change_notifications_total ")
          .append(skillChangeNotifications.get()).append('\n');

        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    //  API summary (for Portal)
    // ══════════════════════════════════════════════════════════════════

    public Map<String, Long> getMemorySummary(String tenantId) {
        Map<String, Long> summary = new java.util.LinkedHashMap<>();
        Map<String, AtomicLong> metrics = memoryMetrics.get(tenantId);
        if (metrics != null) {
            for (var entry : metrics.entrySet()) {
                summary.put(entry.getKey(), entry.getValue().get());
            }
        }
        // Add avg latency
        long[] lat = searchLatency.get(tenantId);
        if (lat != null && lat[1] > 0) {
            summary.put("memory_search_latency_avg_ms", (long)((double) lat[0] / lat[1]));
        }
        return summary;
    }

    public Map<String, Long> getSkillSummary(String tenantId) {
        Map<String, Long> summary = new java.util.LinkedHashMap<>();
        Map<String, AtomicLong> metrics = skillMetrics.get(tenantId);
        if (metrics != null) {
            for (var entry : metrics.entrySet()) {
                summary.put(entry.getKey(), entry.getValue().get());
            }
        }
        summary.put("skill_change_notifications_total", skillChangeNotifications.get());
        return summary;
    }

    public void reset() {
        memoryMetrics.clear();
        skillMetrics.clear();
        skillChangeNotifications.set(0);
        searchLatency.clear();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Internal
    // ══════════════════════════════════════════════════════════════════

    private void increment(ConcurrentHashMap<String, Map<String, AtomicLong>> container,
                           String key, String metric) {
        container.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(metric, m -> new AtomicLong(0))
            .incrementAndGet();
    }

    private void increment(ConcurrentHashMap<String, Map<String, AtomicLong>> container,
                           String key, String metric, long delta) {
        container.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(metric, m -> new AtomicLong(0))
            .addAndGet(delta);
    }

    private void set(ConcurrentHashMap<String, Map<String, AtomicLong>> container,
                     String key, String metric, long value) {
        container.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(metric, m -> new AtomicLong(0))
            .set(value);
    }
}
