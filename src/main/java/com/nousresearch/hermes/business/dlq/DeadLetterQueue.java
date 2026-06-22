package com.nousresearch.hermes.business.dlq;

import com.nousresearch.hermes.business.event.BusinessEventBus;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.config.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dead Letter Queue for failed business runs.
 *
 * <p>Captures runs that failed permanently (including after retries and SLA breaches)
 * and provides a management interface for manual inspection and retry.</p>
 */
public class DeadLetterQueue {
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueue.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path queueDir;
    private final ConcurrentHashMap<String, DeadLetterItem> items = new ConcurrentHashMap<>();
    private volatile BusinessEventBus eventBus;

    public DeadLetterQueue() {
        this(Constants.getHermesHome().resolve("business/dead-letter-queue"));
    }

    public DeadLetterQueue(Path queueDir) {
        this.queueDir = queueDir;
        try {
            Files.createDirectories(queueDir);
        } catch (IOException e) {
            logger.error("Failed to create DLQ directory", e);
        }
        loadPersisted();
    }

    public void setEventBus(BusinessEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Enqueue a failed run.
     */
    public DeadLetterItem enqueue(BusinessRunRecord run, String reason) {
        DeadLetterItem item = new DeadLetterItem(
            "dlq-" + UUID.randomUUID().toString().substring(0, 10),
            run.getRunId(),
            run.getWorkspaceId(),
            run.getTeamId(),
            run.getScenarioId(),
            run.getTaskTitle(),
            reason,
            Instant.now(),
            0,
            null,
            "PENDING"
        );
        items.put(item.itemId(), item);
        persist(item);
        if (eventBus != null) {
            eventBus.dlqEnqueue(run.getWorkspaceId(), item.itemId(), run.getRunId(), reason);
        }
        logger.warn("Run {} enqueued to DLQ: {}", run.getRunId(), reason);
        return item;
    }

    /**
     * List all DLQ items for a workspace.
     */
    public List<DeadLetterItem> list(String workspaceId) {
        return items.values().stream()
            .filter(i -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(i.workspaceId()))
            .sorted(Comparator.comparing(DeadLetterItem::enqueuedAt).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Mark an item as retried.
     */
    public void markRetried(String itemId, String newRunId) {
        DeadLetterItem existing = items.get(itemId);
        if (existing != null) {
            DeadLetterItem updated = new DeadLetterItem(
                existing.itemId(), existing.runId(), existing.workspaceId(),
                existing.teamId(), existing.scenarioId(), existing.taskTitle(),
                existing.reason(), existing.enqueuedAt(),
                existing.retryCount() + 1, newRunId, "RETRIED"
            );
            items.put(itemId, updated);
            persist(updated);
            if (eventBus != null) {
                eventBus.dlqStatusChange(existing.workspaceId(), itemId, "RETRIED");
            }
        }
    }

    /**
     * Mark an item as resolved (discarded/acknowledged).
     */
    public void markResolved(String itemId) {
        DeadLetterItem existing = items.get(itemId);
        if (existing != null) {
            DeadLetterItem updated = new DeadLetterItem(
                existing.itemId(), existing.runId(), existing.workspaceId(),
                existing.teamId(), existing.scenarioId(), existing.taskTitle(),
                existing.reason(), existing.enqueuedAt(),
                existing.retryCount(), existing.newRunId(), "RESOLVED"
            );
            items.put(itemId, updated);
            persist(updated);
            if (eventBus != null) {
                eventBus.dlqStatusChange(existing.workspaceId(), itemId, "RESOLVED");
            }
        }
    }

    /**
     * Get DLQ statistics.
     */
    public Map<String, Object> stats(String workspaceId) {
        List<DeadLetterItem> filtered = list(workspaceId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", filtered.size());
        stats.put("pending", filtered.stream().filter(i -> "PENDING".equals(i.status())).count());
        stats.put("retried", filtered.stream().filter(i -> "RETRIED".equals(i.status())).count());
        stats.put("resolved", filtered.stream().filter(i -> "RESOLVED".equals(i.status())).count());
        return stats;
    }

    // ---- Persistence ----

    private void persist(DeadLetterItem item) {
        try {
            Path file = queueDir.resolve(item.itemId() + ".json");
            MAPPER.writeValue(file.toFile(), item);
        } catch (IOException e) {
            logger.error("Failed to persist DLQ item {}", item.itemId(), e);
        }
    }

    private void loadPersisted() {
        try (var stream = Files.list(queueDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    DeadLetterItem item = MAPPER.readValue(p.toFile(), DeadLetterItem.class);
                    items.put(item.itemId(), item);
                } catch (IOException e) {
                    logger.warn("Failed to load DLQ item: {}", p.getFileName());
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to scan DLQ directory", e);
        }
    }

    /**
     * DLQ item record.
     */
    public record DeadLetterItem(
        String itemId,
        String runId,
        String workspaceId,
        String teamId,
        String scenarioId,
        String taskTitle,
        String reason,
        Instant enqueuedAt,
        int retryCount,
        String newRunId,
        String status
    ) {}
}
