package com.nousresearch.hermes.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists {@link LoopState} to disk so agent loops survive JVM restarts.
 *
 * <p>Storage layout: {@code {dataDir}/harness/{sessionId}.json}</p>
 *
 * <p>Save triggers:
 * <ul>
 *   <li>After each loop iteration (debounced)</li>
 *   <li>On approval checkpoint (immediate)</li>
 *   <li>On loop end (immediate)</li>
 *   <li>On JVM shutdown (shutdown hook)</li>
 * </ul></p>
 *
 * <p>Load triggers:
 * <ul>
 *   <li>On harness creation (if checkpoint exists, restore)</li>
 *   <li>On explicit {@link #restore}</li>
 * </ul></p>
 */
public class CheckpointStore {
    private static final Logger logger = LoggerFactory.getLogger(CheckpointStore.class);

    private final Path harnessDir;
    private final ConcurrentHashMap<String, LoopState> cache = new ConcurrentHashMap<>();

    public CheckpointStore(Path dataDir) {
        this.harnessDir = dataDir.resolve("harness");
        try {
            Files.createDirectories(harnessDir);
        } catch (IOException e) {
            logger.warn("Failed to create harness dir: {}", e.getMessage());
        }
    }

    /** Save loop state for a session. */
    public void save(String sessionId, LoopState state) {
        cache.put(sessionId, state);
        try {
            Path file = harnessDir.resolve(safeName(sessionId) + ".json");
            String json = state.serialize();
            Files.writeString(file, json);
            logger.debug("Checkpoint saved: {} ({} messages, {} iters)",
                sessionId, state.historySize(), state.iterationsUsed());
        } catch (Exception e) {
            logger.warn("Failed to save checkpoint for {}: {}", sessionId, e.getMessage());
        }
    }

    /** Load loop state for a session, or null if none. */
    public LoopState load(String sessionId, int defaultMaxIterations) {
        // Check cache first
        LoopState cached = cache.get(sessionId);
        if (cached != null) return cached;

        try {
            Path file = harnessDir.resolve(safeName(sessionId) + ".json");
            if (!Files.exists(file)) return null;

            String json = Files.readString(file);
            LoopState state = LoopState.deserialize(json, defaultMaxIterations);
            cache.put(sessionId, state);
            logger.debug("Checkpoint loaded: {} ({} messages, {} iters)",
                sessionId, state.historySize(), state.iterationsUsed());
            return state;
        } catch (Exception e) {
            logger.warn("Failed to load checkpoint for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** Delete checkpoint for a session (after successful completion). */
    public void delete(String sessionId) {
        cache.remove(sessionId);
        try {
            Path file = harnessDir.resolve(safeName(sessionId) + ".json");
            Files.deleteIfExists(file);
            logger.debug("Checkpoint deleted: {}", sessionId);
        } catch (Exception e) {
            logger.debug("Failed to delete checkpoint for {}: {}", sessionId, e.getMessage());
        }
    }

    /** Check if a checkpoint exists. */
    public boolean exists(String sessionId) {
        if (cache.containsKey(sessionId)) return true;
        return Files.exists(harnessDir.resolve(safeName(sessionId) + ".json"));
    }

    /** List all checkpointed session IDs. */
    public java.util.List<String> listCheckpoints() {
        try (var stream = Files.list(harnessDir)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .map(p -> {
                    String name = p.getFileName().toString();
                    return name.substring(0, name.length() - 5); // strip .json
                })
                .toList();
        } catch (IOException e) {
            return java.util.List.of();
        }
    }

    /** Restore all checkpoints (for JVM restart recovery). */
    public java.util.List<LoopState> restoreAll(int defaultMaxIterations) {
        var ids = listCheckpoints();
        var restored = new java.util.ArrayList<LoopState>();
        for (String id : ids) {
            LoopState state = load(id, defaultMaxIterations);
            if (state != null && state.lifecycle() == LoopState.Lifecycle.PAUSED_APPROVAL) {
                restored.add(state);
                logger.info("Restored paused checkpoint: {} ({} iters, {} messages)",
                    id, state.iterationsUsed(), state.historySize());
            }
        }
        return restored;
    }

    /** Sanitize session ID for filesystem. */
    private static String safeName(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }
}
