package com.nousresearch.hermes.tools;

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
 * Cross-session tool performance tracker.
 *
 * <p>Records every tool invocation and maintains per-tool statistics:</p>
 * <ul>
 *   <li>Success rate (attempts vs failures)</li>
 *   <li>Average / P95 latency</li>
 *   <li>Common failure patterns (extracted from error messages)</li>
 *   <li>Recency-weighted score (favour recent performance)</li>
 * </ul>
 *
 * <p>The tracker periodically persists to disk and can generate a
 * "tool selection hints" snippet for the system prompt, advising the
 * agent which tools are reliable, which are slow, and what pitfalls to avoid.</p>
 */
public class ToolPerformanceTracker {

    private static final Logger logger = LoggerFactory.getLogger(ToolPerformanceTracker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Minimum samples before we emit a hint about a tool
    private static final int MIN_SAMPLES_FOR_HINT = 3;
    // Latency thresholds (ms)
    private static final long SLOW_THRESHOLD_MS = 10_000;
    private static final long VERY_SLOW_THRESHOLD_MS = 30_000;

    private final Path persistPath;
    private final ConcurrentHashMap<String, ToolStats> stats = new ConcurrentHashMap<>();

    public ToolPerformanceTracker(Path dataDir) {
        this.persistPath = dataDir.resolve("tool_performance.json");
        load();
    }

    /**
     * Record a tool invocation.
     *
     * @param toolName   the tool identifier
     * @param ok         true if the tool returned a non-error result
     * @param durationMs wall-clock time of the call
     * @param hint       optional error hint / exception message for failure analysis
     */
    public void record(String toolName, boolean ok, long durationMs, String hint) {
        stats.computeIfAbsent(toolName, k -> new ToolStats(toolName))
             .record(ok, durationMs, hint);
    }

    public void record(String toolName, boolean ok, long durationMs) {
        record(toolName, ok, durationMs, null);
    }

    /**
     * Build a compact markdown hint block for the system prompt.
     * Only includes tools with enough samples and notable characteristics.
     */
    public String buildHintBlock() {
        List<ToolStats> eligible = stats.values().stream()
            .filter(s -> s.totalCalls >= MIN_SAMPLES_FOR_HINT)
            .sorted(Comparator.comparingDouble(s -> -s.recencyScore()))
            .limit(12)
            .toList();

        if (eligible.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Tool Performance Notes\n\n");
        sb.append("_Based on recent usage. Prioritise high-reliability tools; avoid known pitfalls._\n\n");

        for (ToolStats s : eligible) {
            sb.append("- **").append(s.toolName).append("**");
            sb.append(" — success ").append(String.format("%.0f%%", s.successRate() * 100));
            sb.append(", avg ").append(formatDuration(s.avgDurationMs()));

            if (s.avgDurationMs() > VERY_SLOW_THRESHOLD_MS) {
                sb.append(" ⚠️ VERY SLOW");
            } else if (s.avgDurationMs() > SLOW_THRESHOLD_MS) {
                sb.append(" ⚠️ slow");
            }

            if (s.successRate() < 0.5) {
                sb.append(" ⚠️ unreliable");
            }

            // Failure pattern hint
            String topFailure = s.mostCommonFailure();
            if (topFailure != null) {
                sb.append(" — common failure: \"").append(truncate(topFailure, 60)).append("\"");
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Persist current stats to disk (best-effort).
     */
    public void save() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("savedAt", Instant.now().toString());
            ArrayNode arr = root.putArray("tools");
            for (ToolStats s : stats.values()) {
                arr.add(s.toJson());
            }
            Files.writeString(persistPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            logger.debug("Failed to persist tool stats: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(persistPath)) return;
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(persistPath.toFile());
            ArrayNode arr = (ArrayNode) root.get("tools");
            for (var node : arr) {
                ToolStats s = ToolStats.fromJson((ObjectNode) node);
                stats.put(s.toolName, s);
            }
            logger.info("Loaded tool performance stats for {} tools", stats.size());
        } catch (Exception e) {
            logger.debug("Failed to load tool stats: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    // ------------------------------------------------------------------

    static class ToolStats {
        String toolName;
        int totalCalls;
        int successes;
        long totalDurationMs;
        long lastUsedAt;
        // failure pattern → count
        Map<String, Integer> failures = new HashMap<>();

        ToolStats(String toolName) { this.toolName = toolName; }

        synchronized void record(boolean ok, long durationMs, String hint) {
            totalCalls++;
            if (ok) successes++;
            totalDurationMs += durationMs;
            lastUsedAt = System.currentTimeMillis();
            if (!ok && hint != null && !hint.isBlank()) {
                String key = sanitizeFailure(hint);
                failures.merge(key, 1, Integer::sum);
            }
        }

        double successRate() {
            return totalCalls == 0 ? 0 : successes / (double) totalCalls;
        }

        long avgDurationMs() {
            return totalCalls == 0 ? 0 : totalDurationMs / totalCalls;
        }

        double recencyScore() {
            long ageHours = (System.currentTimeMillis() - lastUsedAt) / (3_600_000);
            double decay = Math.exp(-ageHours / 24.0); // half-life ~24h
            return totalCalls * decay * successRate();
        }

        String mostCommonFailure() {
            return failures.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        }

        ObjectNode toJson() {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("toolName", toolName);
            n.put("totalCalls", totalCalls);
            n.put("successes", successes);
            n.put("totalDurationMs", totalDurationMs);
            n.put("lastUsedAt", lastUsedAt);
            ObjectNode f = n.putObject("failures");
            failures.forEach(f::put);
            return n;
        }

        static ToolStats fromJson(ObjectNode n) {
            ToolStats s = new ToolStats(n.get("toolName").asText());
            s.totalCalls = n.get("totalCalls").asInt();
            s.successes = n.get("successes").asInt();
            s.totalDurationMs = n.get("totalDurationMs").asLong();
            s.lastUsedAt = n.get("lastUsedAt").asLong();
            if (n.has("failures")) {
                n.get("failures").fields().forEachRemaining(e ->
                    s.failures.put(e.getKey(), e.getValue().asInt()));
            }
            return s;
        }

        private static String sanitizeFailure(String raw) {
            // Keep the first sentence or first 120 chars, remove stack traces
            String cleaned = raw.split("\\n")[0];
            cleaned = cleaned.replaceAll(" at .*", "");
            if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
            return cleaned;
        }
    }
}
