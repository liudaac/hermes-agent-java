package com.nousresearch.hermes.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Reflection engine — makes the agent reflect on its own performance at the
 * end of a session, producing structured self-assessment and storing lessons.
 *
 * <p>Design aligned with the Reflexion / Self-Critique pattern:</p>
 * <ul>
 *   <li>Task completion assessment</li>
 *   <li>Mistake identification</li>
 *   <li>Actionable next-time-do notes</li>
 *   <li>Stored in MEMORY.md under [LESSON] prefix for retrieval</li>
 * </ul>
 *
 * <p>Reflection is triggered automatically on session end. If the user
 * message count is below a threshold (default 3), reflection is skipped.</p>
 */
public class ReflectionEngine {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelClient modelClient;
    private final MemoryManager memoryManager;
    private final ConfigManager config;

    private final boolean enabled;
    private final String reflectionModel;
    private final int minMessagesForReflection;

    public ReflectionEngine(ModelClient modelClient, MemoryManager memoryManager) {
        this.modelClient = modelClient;
        this.memoryManager = memoryManager;
        this.config = ConfigManager.getInstance();
        this.enabled = config.getBoolean("reflection.enabled", true);
        this.reflectionModel = config.getString("reflection.model",
            config.getString("model.model", "anthropic/claude-3.5-sonnet"));
        this.minMessagesForReflection = config.getInt("reflection.min_messages", 3);
    }

    /**
     * Run reflection after a session completes.
     *
     * @param sessionId the session identifier
     * @param messages  full conversation history (user + assistant + tool)
     * @param completed whether the session ended normally (not interrupted)
     */
    public ReflectionResult reflect(String sessionId, List<ModelMessage> messages, boolean completed) {
        if (!enabled) {
            return ReflectionResult.skipped("disabled");
        }
        if (messages.size() < minMessagesForReflection) {
            return ReflectionResult.skipped("too_short");
        }

        logger.info("Reflecting on session: {} ({} messages)", sessionId, messages.size());

        try {
            // Build transcript summary (last N turns; avoid token blow)
            String transcript = summarizeTranscript(messages, 20);
            String prompt = buildReflectionPrompt(transcript, completed);

            List<ModelMessage> llmInput = List.of(
                new ModelMessage("system",
                    "You are a session-review assistant. Output strict JSON only."),
                new ModelMessage("user", prompt)
            );

            var response = modelClient.chatCompletion(llmInput, List.of(), false);
            String raw = response.getMessage() != null ? response.getMessage().getContent() : "";

            ReflectionResult result = parseReflection(raw);

            // Persist lessons learned
            for (Lesson lesson : result.getLessons()) {
                if (lesson.confidence >= 0.7) {
                    String tagged = "[LESSON] " + lesson.content;
                    memoryManager.addMemory(tagged);
                }
            }
            for (String anti : result.getAntiPatterns()) {
                memoryManager.addMemory("[ANTI-PATTERN] " + anti);
            }

            logger.info("Reflection complete: score={}, lessons={}, anti_patterns={}",
                result.getTaskScore(), result.getLessons().size(), result.getAntiPatterns().size());

            return result;

        } catch (Exception e) {
            logger.warn("Reflection failed for session {}: {}", sessionId, e.getMessage());
            return ReflectionResult.error(e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private String summarizeTranscript(List<ModelMessage> messages, int maxTurns) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, messages.size() - maxTurns);
        for (int i = start; i < messages.size(); i++) {
            ModelMessage m = messages.get(i);
            if (m.getContent() != null && !m.getContent().isBlank()) {
                sb.append(m.getRole().toUpperCase()).append(": ")
                  .append(m.getContent().replace("\n", " ")).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String buildReflectionPrompt(String transcript, boolean completed) {
        return """
            Review the following conversation transcript and produce a structured reflection.

            End state: %s

            Transcript:
            ---
            %s
            ---

            Output STRICT JSON with this exact shape (no markdown, no prose):
            {
              "task_completed": true|false,
              "task_score": 0.0-1.0,
              "what_worked": ["..."],
              "what_went_wrong": ["..."],
              "lessons": [{"content": "...", "confidence": 0.0-1.0}],
              "anti_patterns": ["..."],
              "next_time": ["..."]
            }

            Rules:
            - task_score: 1.0 = fully accomplished what the user asked
            - what_worked: specific choices or tool usage that led to good outcomes
            - what_went_wrong: actual errors, misunderstandings, or inefficient paths
            - lessons: concise, generalisable takeaways worth storing long-term (max 3)
            - anti_patterns: behaviours to avoid next time (max 2)
            - next_time: concrete actions to improve if the same task recurred
            """.formatted(completed ? "completed" : "interrupted/aborted", transcript);
    }

    private ReflectionResult parseReflection(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReflectionResult.error("empty_response");
        }
        // Strip markdown fences
        if (raw.startsWith("```")) {
            int s = raw.indexOf('\n');
            int e = raw.lastIndexOf("```");
            if (s > 0 && e > s) raw = raw.substring(s + 1, e).trim();
            else if (s > 0) raw = raw.substring(s + 1).trim();
        }

        try {
            var node = MAPPER.readTree(raw);
            ReflectionResult r = new ReflectionResult();
            r.taskCompleted = node.path("task_completed").asBoolean(false);
            r.taskScore = node.path("task_score").asDouble(0.5);

            node.path("what_worked").forEach(n -> r.whatWorked.add(n.asText()));
            node.path("what_went_wrong").forEach(n -> r.whatWentWrong.add(n.asText()));
            node.path("lessons").forEach(n -> {
                String c = n.path("content").asText(n.asText());
                double conf = n.path("confidence").asDouble(0.7);
                r.lessons.add(new Lesson(c, conf));
            });
            node.path("anti_patterns").forEach(n -> r.antiPatterns.add(n.asText()));
            node.path("next_time").forEach(n -> r.nextTime.add(n.asText()));
            return r;
        } catch (Exception e) {
            logger.debug("Failed to parse reflection JSON ({}), falling back to heuristic", e.getMessage());
            return heuristicFallback(raw);
        }
    }

    private ReflectionResult heuristicFallback(String raw) {
        ReflectionResult r = new ReflectionResult();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("-") || line.startsWith("*")) {
                line = line.substring(1).trim();
            }
            if (line.length() > 20 && line.length() < 500) {
                if (line.toLowerCase().contains("lesson") || line.toLowerCase().contains("should")) {
                    r.lessons.add(new Lesson(line, 0.6));
                } else if (line.toLowerCase().contains("avoid") || line.toLowerCase().contains("don't")) {
                    r.antiPatterns.add(line);
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------------

    public static class ReflectionResult {
        public boolean taskCompleted;
        public double taskScore = 0.5;
        public List<String> whatWorked = new ArrayList<>();
        public List<String> whatWentWrong = new ArrayList<>();
        public List<Lesson> lessons = new ArrayList<>();
        public List<String> antiPatterns = new ArrayList<>();
        public List<String> nextTime = new ArrayList<>();

        public String status; // "ok" | "skipped" | "error"

        public boolean isTaskCompleted() { return taskCompleted; }
        public double getTaskScore() { return taskScore; }
        public List<String> getWhatWorked() { return whatWorked; }
        public List<String> getWhatWentWrong() { return whatWentWrong; }
        public List<Lesson> getLessons() { return lessons; }
        public List<String> getAntiPatterns() { return antiPatterns; }
        public List<String> getNextTime() { return nextTime; }

        public static ReflectionResult skipped(String reason) {
            ReflectionResult r = new ReflectionResult();
            r.status = "skipped:" + reason;
            return r;
        }

        public static ReflectionResult error(String reason) {
            ReflectionResult r = new ReflectionResult();
            r.status = "error:" + reason;
            return r;
        }
    }

    public static record Lesson(String content, double confidence) {}
}
