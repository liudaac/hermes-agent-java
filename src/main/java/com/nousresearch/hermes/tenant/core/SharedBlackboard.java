package com.nousresearch.hermes.tenant.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li><b>JSON file persistence</b> — survives restarts</li>
 * </ul>
 */
public class SharedBlackboard {

    private static final Logger logger = LoggerFactory.getLogger(SharedBlackboard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Default TTL: 30 minutes
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000;

    private final String tenantId;
    private final Path persistPath;
    private final ConcurrentHashMap<String, BlackboardEntry> entries = new ConcurrentHashMap<>();

    public SharedBlackboard(String tenantId) {
        this(tenantId, defaultPath(tenantId));
    }

    public SharedBlackboard(String tenantId, Path persistPath) {
        this.tenantId = tenantId;
        this.persistPath = persistPath;
        load();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static Path defaultPath(String tenantId) {
        return com.nousresearch.hermes.config.Constants.getHermesHome()
            .resolve("tenants").resolve(sanitize(tenantId)).resolve("blackboard.json");
    }

    public void save() {
        try {
            purgeExpired();
            Files.createDirectories(persistPath.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            root.put("tenantId", tenantId);
            ArrayNode arr = root.putArray("entries");
            for (BlackboardEntry e : entries.values()) {
                if (e.isExpired()) continue;
                ObjectNode n = MAPPER.createObjectNode();
                n.put("key", e.key);
                n.put("value", e.value);
                n.put("author", e.author);
                n.put("topic", e.topic);
                n.put("timestamp", e.timestamp);
                n.put("ttlMs", e.ttlMs);
                arr.add(n);
            }
            Files.writeString(persistPath, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root));
        } catch (Exception e) {
            logger.warn("Failed to save blackboard: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(persistPath)) return;
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(persistPath.toFile());
            ArrayNode arr = (ArrayNode) root.get("entries");
            if (arr == null) return;
            for (var node : arr) {
                String topic = node.path("topic").asText("default");
                String key = node.path("key").asText();
                String value = node.path("value").asText();
                String author = node.path("author").asText("unknown");
                long ts = node.path("timestamp").asLong(0);
                long ttl = node.path("ttlMs").asLong(DEFAULT_TTL_MS);
                if (key == null || key.isBlank() || value == null) continue;
                BlackboardEntry entry = new BlackboardEntry(value, author, topic, ts, ttl);
                entry.key = key;
                entries.put(qualifiedKey(topic, key), entry);
            }
            int fresh = size();
            logger.info("Loaded {} blackboard entries for tenant: {}", fresh, tenantId);
        } catch (Exception e) {
            logger.warn("Failed to load blackboard: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    public void write(String key, String value, String author, String topic, long ttlMs) {
        long effectiveTtl = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        BlackboardEntry e = new BlackboardEntry(value, author, topic,
            System.currentTimeMillis(), effectiveTtl);
        e.key = key;
        entries.put(qualifiedKey(topic, key), e);
        save(); // persist on every write
        logger.debug("Blackboard write: tenant={}, topic={}, key={}, author={}",
            tenantId, topic, key, author);
    }

    public void write(String key, String value, String author) {
        write(key, value, author, "default", DEFAULT_TTL_MS);
    }

    public Optional<BlackboardEntry> read(String key, String topic) {
        BlackboardEntry e = entries.get(qualifiedKey(topic, key));
        if (e == null || e.isExpired()) return Optional.empty();
        return Optional.of(e);
    }

    public Optional<BlackboardEntry> read(String key) {
        return read(key, "default");
    }

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

    public void clear(String topic) {
        if (topic == null) {
            entries.clear();
        } else {
            String prefix = topic + ":";
            entries.keySet().removeIf(k -> k.startsWith(prefix));
        }
        save();
    }

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

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // ------------------------------------------------------------------

    public static class BlackboardEntry {
        /** Set after construction to track the key for persistence. */
        String key;
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
