package com.nousresearch.hermes.tenant.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Shared blackboard for multi-agent collaboration within a tenant.
 *
 * <p>When multiple sub-agents work on the same tenant, they can publish
 * intermediate findings to the blackboard instead of relying solely on
 * the one-line {@code SubAgentResult.summary}. The main agent can then
 * read all entries, rank them, and synthesise a unified response.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Key-value entries with metadata (author, timestamp, TTL)</li>
 *   <li>Auto-expiry (entries older than TTL are ignored but lazily cleaned)</li>
 *   <li>Topic-scoped namespaces so different tasks don't collide</li>
 *   <li>Read/write/list/clear operations</li>
 * </ul>
 */
public class SharedBlackboard {

    private static final Logger logger = LoggerFactory.getLogger(SharedBlackboard.class);

    // Default TTL: 30 minutes
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000;

    private final String tenantId;
    private final ConcurrentHashMap<String, BlackboardEntry> entries = new ConcurrentHashMap<>();

    public SharedBlackboard(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Write an entry to the blackboard.
     *
     * @param key      unique key within the topic
     * @param value    the content (text, JSON, markdown, etc.)
     * @param author   who wrote it (e.g. sub-agent id or "main")
     * @param topic    logical namespace (default "default")
     * @param ttlMs    time-to-live in ms (-1 for default)
     */
    public void write(String key, String value, String author, String topic, long ttlMs) {
        long effectiveTtl = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        entries.put(qualifiedKey(topic, key), new BlackboardEntry(value, author, topic,
            System.currentTimeMillis(), effectiveTtl));
        logger.debug("Blackboard write: tenant={}, topic={}, key={}, author={}",
            tenantId, topic, key, author);
    }

    public void write(String key, String value, String author) {
        write(key, value, author, "default", DEFAULT_TTL_MS);
    }

    /**
     * Read a single entry.
     */
    public Optional<BlackboardEntry> read(String key, String topic) {
        BlackboardEntry e = entries.get(qualifiedKey(topic, key));
        if (e == null || e.isExpired()) return Optional.empty();
        return Optional.of(e);
    }

    public Optional<BlackboardEntry> read(String key) {
        return read(key, "default");
    }

    /**
     * List all non-expired entries in a topic, newest first.
     */
    public List<BlackboardEntry> list(String topic) {
        String prefix = topic + ":";
        return entries.entrySet().stream()
            .filter(kv -> kv.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .filter(e -> !e.isExpired())
            .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
            .collect(Collectors.toList());
    }

    public List<BlackboardEntry> list() {
        return list("default");
    }

    /**
     * Clear entries in a topic. If topic is null, clear all.
     */
    public void clear(String topic) {
        if (topic == null) {
            entries.clear();
        } else {
            String prefix = topic + ":";
            entries.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /**
     * Lazy cleanup of expired entries.
     */
    public void purgeExpired() {
        int before = entries.size();
        entries.entrySet().removeIf(e -> e.getValue().isExpired());
        int after = entries.size();
        if (before != after) {
            logger.debug("Purged {} expired blackboard entries", before - after);
        }
    }

    public int size() {
        return (int) entries.values().stream().filter(e -> !e.isExpired()).count();
    }

    // ------------------------------------------------------------------

    private static String qualifiedKey(String topic, String key) {
        return topic + ":" + key;
    }

    public static class BlackboardEntry {
        public final String value;
        public final String author;
        public final String topic;
        public final long timestamp;
        public final long ttlMs;

        public BlackboardEntry(String value, String author, String topic, long timestamp, long ttlMs) {
            this.value = value;
            this.author = author;
            this.topic = topic;
            this.timestamp = timestamp;
            this.ttlMs = ttlMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }

        public Instant getInstant() {
            return Instant.ofEpochMilli(timestamp);
        }
    }
}
