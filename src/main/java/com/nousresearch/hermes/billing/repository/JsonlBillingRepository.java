package com.nousresearch.hermes.billing.repository;

import com.nousresearch.hermes.billing.TenantUsageRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * B8: JSONL file-based BillingRepository implementation.
 *
 * <p>Appends records to
 * {@code {baseDir}/{tenantId}/state/billing/{date}.jsonl}
 * and reads them back with simple file I/O.</p>
 *
 * <p>This is the default implementation. For cluster mode, implement
 * {@code PostgresBillingRepository} using the same interface.</p>
 */
public class JsonlBillingRepository implements BillingRepository {

    private static final Logger logger = LoggerFactory.getLogger(JsonlBillingRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path baseDir;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public JsonlBillingRepository(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public void append(TenantUsageRecord record) {
        if (record == null || record.tenantId() == null) return;

        ReentrantLock lock = locks.computeIfAbsent(record.tenantId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Path file = billingFile(record.tenantId(), record.date());
            Files.createDirectories(file.getParent());
            Files.writeString(file, record.toJsonLine() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Failed to write billing record for tenant={}: {}",
                record.tenantId(), e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TenantUsageRecord> findByTenantAndDate(String tenantId, LocalDate date) {
        Path file = billingFile(tenantId, date);
        return readRecords(file);
    }

    @Override
    public List<TenantUsageRecord> findByTenantAndDateRange(String tenantId, LocalDate fromDate, LocalDate toDate) {
        List<TenantUsageRecord> all = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            all.addAll(findByTenantAndDate(tenantId, d));
        }
        all.sort(Comparator.comparing(TenantUsageRecord::timestamp));
        return all;
    }

    @Override
    public TenantDailyTotals getDailyTotals(String tenantId, LocalDate date) {
        List<TenantUsageRecord> records = findByTenantAndDate(tenantId, date);
        return aggregate(tenantId, records);
    }

    @Override
    public TenantDailyTotals getRangeTotals(String tenantId, LocalDate fromDate, LocalDate toDate) {
        List<TenantUsageRecord> records = findByTenantAndDateRange(tenantId, fromDate, toDate);
        return aggregate(tenantId, records);
    }

    // ============ Internal ============

    private Path billingFile(String tenantId, LocalDate date) {
        return baseDir.resolve(tenantId).resolve("state").resolve("billing")
            .resolve(date.format(DateTimeFormatter.ISO_DATE) + ".jsonl");
    }

    private List<TenantUsageRecord> readRecords(Path file) {
        if (!Files.exists(file)) return List.of();
        try {
            List<String> lines = Files.readAllLines(file);
            List<TenantUsageRecord> records = new ArrayList<>(lines.size());
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    records.add(parseRecord(line));
                } catch (Exception e) {
                    logger.debug("Failed to parse billing line: {}", line);
                }
            }
            return records;
        } catch (IOException e) {
            logger.warn("Failed to read billing file {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    private TenantUsageRecord parseRecord(String json) throws Exception {
        var node = mapper.readTree(json);
        return new TenantUsageRecord(
            node.path("tenantId").asText(),
            LocalDate.parse(node.path("date").asText()),
            node.path("model").asText(),
            node.path("provider").asText(),
            node.path("inputTokens").asLong(),
            node.path("outputTokens").asLong(),
            node.path("totalTokens").asLong(),
            node.path("estimatedCostUsd").asDouble(),
            java.time.Instant.parse(node.path("timestamp").asText()),
            node.path("sessionId").asText()
        );
    }

    private TenantDailyTotals aggregate(String tenantId, List<TenantUsageRecord> records) {
        if (records.isEmpty()) return TenantDailyTotals.empty(tenantId);
        long input = 0, output = 0, total = 0;
        double cost = 0;
        for (var r : records) {
            input += r.inputTokens();
            output += r.outputTokens();
            total += r.totalTokens();
            cost += r.estimatedCostUsd();
        }
        return new TenantDailyTotals(tenantId, records.size(), input, output, total, cost);
    }
}
