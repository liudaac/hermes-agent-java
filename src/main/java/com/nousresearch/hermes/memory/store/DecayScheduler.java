package com.nousresearch.hermes.memory.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Background scheduler that runs decay cycles for active sessions.
 *
 * <p>Each tenant's sessions are scanned at the interval defined by their
 * {@link DecayPolicy#getDecayCycleInterval()}. The scheduler tracks which
 * sessions have been seen and their last decay run time.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DecayScheduler scheduler = new DecayScheduler(memoryStore);
 * scheduler.start();
 * // ... during application lifecycle ...
 * scheduler.registerSession("tenant-1", "session-abc", DecayPolicy.standard());
 * // ... when session ends ...
 * scheduler.unregisterSession("tenant-1", "session-abc");
 * // ... on shutdown ...
 * scheduler.stop();
 * }</pre>
 *
 * <p>The scheduler uses a single-thread ScheduledExecutorService by default.
 * For high-throughput deployments, increase the thread pool size via the
 * constructor.</p>
 */
public class DecayScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DecayScheduler.class);

    private final MemoryStore memoryStore;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, SessionEntry> activeSessions = new ConcurrentHashMap<>();
    private final MemoryStore.SummaryFunction summariser;
    private final MemoryStore.FactExtractor factExtractor;
    private volatile boolean running = false;

    /** Tracks one session's decay configuration. */
    private record SessionEntry(
            String tenantId,
            String sessionId,
            DecayPolicy policy,
            Instant registeredAt
    ) {}

    /**
     * Create with default summariser (concat) and fact extractor (empty).
     *
     * <p>In production, provide real LLM-backed implementations via
     * {@link #DecayScheduler(MemoryStore, SummaryFunction, FactExtractor)}.</p>
     */
    public DecayScheduler(MemoryStore memoryStore) {
        this(memoryStore,
             msgs -> {
                 // Default: simple concatenation summary
                 StringBuilder sb = new StringBuilder();
                 for (var msg : msgs) {
                     sb.append("[").append(msg.role()).append("] ")
                       .append(msg.content(), 0, Math.min(200, msg.content().length()))
                       .append("\n");
                 }
                 return sb.toString();
             },
             (summary, maxFacts) -> {
                 // Default: no fact extraction (return summary as single fact)
                 return maxFacts > 0 ? List.of(summary) : List.of();
             });
    }

    /**
     * Create with custom LLM-backed summariser and fact extractor.
     */
    public DecayScheduler(MemoryStore memoryStore,
                           MemoryStore.SummaryFunction summariser,
                           MemoryStore.FactExtractor factExtractor) {
        this.memoryStore = memoryStore;
        this.summariser = summariser;
        this.factExtractor = factExtractor;
        this.executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "memory-decay-scheduler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════

    /**
     * Start the scheduler. Scans every 60 seconds for sessions needing decay.
     */
    public void start() {
        if (running) return;
        running = true;
        executor.scheduleAtFixedRate(this::runDecayScan, 60, 60, TimeUnit.SECONDS);
        logger.info("DecayScheduler started (scan interval: 60s)");
    }

    /**
     * Stop the scheduler and release resources.
     */
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        activeSessions.clear();
        logger.info("DecayScheduler stopped");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Session registration
    // ══════════════════════════════════════════════════════════════════

    /**
     * Register a session for decay management.
     */
    public void registerSession(String tenantId, String sessionId, DecayPolicy policy) {
        String key = sessionKey(tenantId, sessionId);
        activeSessions.put(key, new SessionEntry(tenantId, sessionId, policy, Instant.now()));
        logger.debug("Registered session for decay: {} (policy: full={} warm={} cool={})",
            key, policy.getFullWindow(), policy.getWarmWindow(), policy.getCoolWindow());
    }

    /**
     * Unregister a session (e.g. when session ends).
     */
    public void unregisterSession(String tenantId, String sessionId) {
        String key = sessionKey(tenantId, sessionId);
        SessionEntry removed = activeSessions.remove(key);
        if (removed != null) {
            logger.debug("Unregistered session from decay: {}", key);
        }
    }

    /**
     * Get the number of active sessions being tracked.
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Decay scan
    // ══════════════════════════════════════════════════════════════════

    /**
     * Scan all registered sessions and run decay cycles for those that need it.
     */
    private void runDecayScan() {
        if (activeSessions.isEmpty()) return;

        Instant now = Instant.now();
        int totalCompressed = 0;
        int totalEvicted = 0;
        int totalFacts = 0;
        int sessionsProcessed = 0;
        int errors = 0;

        for (SessionEntry entry : activeSessions.values()) {
            // Check if enough time has passed since the last decay run
            var stats = memoryStore.getSessionStats(entry.tenantId(), entry.sessionId());
            if (stats.lastDecayRun() != null) {
                Duration sinceLast = Duration.between(stats.lastDecayRun(), now);
                if (sinceLast.compareTo(entry.policy().getDecayCycleInterval()) < 0) {
                    continue;  // Not enough time since last run
                }
            }

            // Only process sessions that have messages
            int totalMessages = stats.fullCount() + stats.warmCount() + stats.coolCount();
            if (totalMessages == 0) continue;

            try {
                MemoryStore.DecayResult result = memoryStore.runDecayCycle(
                    entry.tenantId(), entry.sessionId(), entry.policy(),
                    summariser, factExtractor);

                totalCompressed += result.compressedToCool();
                totalEvicted += result.evictedFromCool();
                totalFacts += result.factsExtracted();
                sessionsProcessed++;

                if (result.compressedToCool() > 0 || result.evictedFromCool() > 0) {
                    logger.info("Decay for {}/{}: compressed={}, evicted={}, facts={}, duration={}ms",
                        entry.tenantId(), entry.sessionId(),
                        result.compressedToCool(), result.evictedFromCool(),
                        result.factsExtracted(), result.duration().toMillis());
                }

                // Record metrics
                MemorySkillMetrics.getInstance().recordDecay(
                    entry.tenantId(),
                    result.compressedToCool(),
                    result.evictedFromCool(),
                    result.factsExtracted(),
                    result.duration().toMillis()
                );

            } catch (Exception e) {
                errors++;
                logger.warn("Decay cycle failed for {}/{}: {}",
                    entry.tenantId(), entry.sessionId(), e.getMessage());
            }
        }

        if (sessionsProcessed > 0) {
            logger.info("Decay scan complete: {} sessions processed, {} compressed, {} evicted, {} facts extracted, {} errors",
                sessionsProcessed, totalCompressed, totalEvicted, totalFacts, errors);
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    private static String sessionKey(String tenantId, String sessionId) {
        return tenantId + ":" + sessionId;
    }

    /**
     * Get a snapshot of all registered sessions (for observability).
     */
    public List<Map<String, Object>> getRegisteredSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionEntry entry : activeSessions.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("tenantId", entry.tenantId());
            info.put("sessionId", entry.sessionId());
            info.put("policy", entry.policy().getFullWindow() + "/" +
                entry.policy().getWarmWindow() + "/" + entry.policy().getCoolWindow());
            info.put("registeredAt", entry.registeredAt().toString());
            result.add(info);
        }
        return result;
    }
}
