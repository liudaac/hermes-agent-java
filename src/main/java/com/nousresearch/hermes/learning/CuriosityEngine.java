package com.nousresearch.hermes.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.trajectory.TrajectoryCollector;
import com.nousresearch.hermes.trajectory.TrajectoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Curiosity-driven learning engine.
 *
 * <p>Automatically identifies topics where the agent performed poorly
 * (failed sessions, low reflection scores, repeated user corrections)
 * and proactively researches them to fill knowledge gaps.</p>
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Scan recent trajectories for weak signals</li>
 *   <li>Cluster weak topics via LLM</li>
 *   <li>Pick top-K under-served topics</li>
 *   <li>Generate search queries</li>
 *   <li>Execute search (via WebSearchTool or browser)</li>
 *   <li>Summarise findings and write to MEMORY.md</li>
 * </ol>
 */
public class CuriosityEngine {

    private static final Logger logger = LoggerFactory.getLogger(CuriosityEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelClient modelClient;
    private final MemoryManager memoryManager;
    private final TrajectoryCollector trajectoryCollector;
    private final ConfigManager config;

    private final boolean enabled;
    private final int scanWindowDays;
    private final int maxTopicsPerRun;
    private final double minWeakSignalThreshold;

    public CuriosityEngine(ModelClient modelClient, MemoryManager memoryManager,
                           TrajectoryCollector trajectoryCollector) {
        this.modelClient = modelClient;
        this.memoryManager = memoryManager;
        this.trajectoryCollector = trajectoryCollector;
        this.config = ConfigManager.getInstance();

        this.enabled = config.getBoolean("curiosity.enabled", true);
        this.scanWindowDays = config.getInt("curiosity.scan_window_days", 7);
        this.maxTopicsPerRun = config.getInt("curiosity.max_topics", 3);
        this.minWeakSignalThreshold = config.getDouble("curiosity.threshold", 0.3);
    }

    /**
     * Run a curiosity scan. Usually triggered by cron / heartbeat.
     *
     * @return number of topics researched and stored
     */
    public int run() {
        if (!enabled) {
            logger.debug("Curiosity engine disabled");
            return 0;
        }

        logger.info("Running curiosity scan (window={} days)", scanWindowDays);

        // 1. Load recent trajectories
        List<TrajectoryEntry> recent = loadRecentTrajectories();
        if (recent.isEmpty()) {
            logger.debug("No recent trajectories to analyse");
            return 0;
        }

        // 2. Extract weak topics
        List<WeakTopic> weakTopics = extractWeakTopics(recent);
        if (weakTopics.isEmpty()) {
            logger.info("No weak topics detected — agent is doing well!");
            return 0;
        }

        // 3. Rank and deduplicate
        List<WeakTopic> topTopics = rankTopics(weakTopics);

        // 4. Research each topic
        int stored = 0;
        for (WeakTopic topic : topTopics.stream().limit(maxTopicsPerRun).toList()) {
            try {
                String finding = researchTopic(topic);
                if (finding != null && !finding.isBlank()) {
                    String tagged = "[AUTO-LEARNED] " + topic.name + ": " + finding;
                    memoryManager.addMemory(tagged);
                    stored++;
                    logger.info("Curiosity stored finding for topic: {}", topic.name);
                }
            } catch (Exception e) {
                logger.warn("Failed to research topic {}: {}", topic.name, e.getMessage());
            }
        }

        return stored;
    }

    // ------------------------------------------------------------------

    private List<TrajectoryEntry> loadRecentTrajectories() {
        Instant since = Instant.now().minusSeconds(scanWindowDays * 24 * 3600L);
        return trajectoryCollector.loadTrajectories(true, 200).stream()
            .filter(e -> e.getTimestamp() != null && e.getTimestamp().isAfter(since))
            .collect(Collectors.toList());
    }

    /**
     * Use LLM to identify weak topics from trajectories.
     */
    private List<WeakTopic> extractWeakTopics(List<TrajectoryEntry> entries) {
        // Build a summary of each trajectory
        StringBuilder sb = new StringBuilder();
        for (TrajectoryEntry e : entries) {
            sb.append("---\nSession: ").append(e.getSessionId())
              .append(" | Completed: ").append(e.isCompleted()).append("\n");
            if (e.getConversations() != null) {
                // Just first user message + first assistant response
                var msgs = e.getConversations();
                String firstUser = msgs.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .findFirst()
                    .map(m -> m.getContent())
                    .orElse("");
                String firstAsst = msgs.stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .findFirst()
                    .map(m -> m.getContent())
                    .orElse("");
                sb.append("User: ").append(truncate(firstUser, 200)).append("\n");
                sb.append("Assistant: ").append(truncate(firstAsst, 200)).append("\n");
            }
        }

        String prompt = """
            You are a learning-assistant. Given session summaries below, identify
            3-5 topics where the assistant may have struggled, lacked knowledge,
            or where the user seemed dissatisfied. For each topic, output:
            {"name": "short topic name", "reason": "why it was weak", "confidence": 0.0-1.0}

            Return STRICT JSON array only. No prose.

            Sessions:
            %s
            """.formatted(sb.toString());

        try {
            List<ModelMessage> msgs = List.of(
                new ModelMessage("system", "Output JSON array only."),
                new ModelMessage("user", prompt)
            );
            var resp = modelClient.chatCompletion(msgs, List.of(), false);
            String raw = resp.getMessage() != null ? resp.getMessage().getContent() : "";
            return parseWeakTopics(raw);
        } catch (Exception e) {
            logger.warn("LLM weak-topic extraction failed: {}", e.getMessage());
            return heuristicWeakTopics(entries);
        }
    }

    private List<WeakTopic> parseWeakTopics(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        // Strip fences
        if (raw.startsWith("```")) {
            int s = raw.indexOf('\n');
            int e = raw.lastIndexOf("```");
            if (s > 0 && e > s) raw = raw.substring(s + 1, e).trim();
        }
        try {
            var arr = MAPPER.readTree(raw);
            List<WeakTopic> out = new ArrayList<>();
            for (var node : arr) {
                String name = node.path("name").asText(null);
                String reason = node.path("reason").asText("");
                double conf = node.path("confidence").asDouble(0.5);
                if (name != null && !name.isBlank() && conf >= minWeakSignalThreshold) {
                    out.add(new WeakTopic(name, reason, conf));
                }
            }
            return out;
        } catch (Exception e) {
            logger.debug("Failed to parse curiosity JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Heuristic fallback: look for incomplete sessions with tool failures.
     */
    private List<WeakTopic> heuristicWeakTopics(List<TrajectoryEntry> entries) {
        List<WeakTopic> out = new ArrayList<>();
        for (TrajectoryEntry e : entries) {
            if (!e.isCompleted()) {
                String firstUser = e.getConversations() != null
                    ? e.getConversations().stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .findFirst()
                        .map(m -> m.getContent())
                        .orElse("")
                    : "";
                if (firstUser.length() > 10) {
                    out.add(new WeakTopic(firstUser.substring(0, Math.min(40, firstUser.length())),
                        "incomplete session", 0.4));
                }
            }
        }
        return out;
    }

    private List<WeakTopic> rankTopics(List<WeakTopic> topics) {
        // Deduplicate by name (case-insensitive), keep highest confidence
        Map<String, WeakTopic> map = new LinkedHashMap<>();
        for (WeakTopic t : topics) {
            String key = t.name.toLowerCase();
            if (!map.containsKey(key) || map.get(key).confidence < t.confidence) {
                map.put(key, t);
            }
        }
        return map.values().stream()
            .sorted((a, b) -> Double.compare(b.confidence, a.confidence))
            .collect(Collectors.toList());
    }

    /**
     * Research a topic: generate search query, get results, summarise.
     * In a real deployment this would call WebSearchTool / BrowserTool.
     * For now, we ask the LLM to synthesise what it knows.
     */
    private String researchTopic(WeakTopic topic) throws Exception {
        String prompt = """
            The assistant has been struggling with this topic:
            Topic: %s
            Reason: %s

            Provide a concise, factual summary (under 400 chars) that would help
            the assistant answer better next time. Focus on practical guidance,
            common pitfalls, and best practices.
            """.formatted(topic.name, topic.reason);

        List<ModelMessage> msgs = List.of(
            new ModelMessage("system", "You are a knowledge-compiler. Be concise and actionable."),
            new ModelMessage("user", prompt)
        );
        var resp = modelClient.chatCompletion(msgs, List.of(), false);
        String content = resp.getMessage() != null ? resp.getMessage().getContent() : "";
        return content != null ? content.trim() : "";
    }

    // ------------------------------------------------------------------

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    public record WeakTopic(String name, String reason, double confidence) {}
}
