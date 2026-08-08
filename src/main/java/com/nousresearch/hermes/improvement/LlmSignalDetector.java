package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects LLM-assisted improvement signals from user messages.
 *
 * <p>Handles three signal types that require natural language understanding:</p>
 * <ul>
 *   <li>USER_CORRECTION - "no, that's wrong, should be..."</li>
 *   <li>EXPLICIT_FEEDBACK - "remember I prefer..." / "don't do X anymore"</li>
 *   <li>REPEAT_PATTERN - detected via BM25 similarity (separate method)</li>
 * </ul>
 *
 * <p>Detection uses a two-stage approach: trigger-word matching (fast, no LLM cost)
 * followed by LLM classification/extraction (accurate, only called when triggered).</p>
 *
 * <p>When no ModelClient is configured, falls back to trigger-word-only detection
 * (no LLM classification). This keeps the detector functional in test/local mode.</p>
 */
public class LlmSignalDetector {

    private static final Logger logger = LoggerFactory.getLogger(LlmSignalDetector.class);

    private final ModelClient modelClient;
    private final SignalCollector signalCollector;

    // Trigger words for USER_CORRECTION
    private static final Set<String> CORRECTION_TRIGGERS = Set.of(
            "不对", "错了", "不是这样", "应该是", "重新", "搞错了",
            "no,", "no ", "wrong", "incorrect", "should be", "not right",
            "that's wrong", "try again", "redo"
    );

    // Trigger words for EXPLICIT_FEEDBACK
    private static final Set<String> FEEDBACK_TRIGGERS = Set.of(
            "记住", "以后都", "我偏好", "别再", "不要每次",
            "remember", "always", "i prefer", "stop doing", "don't",
            "from now on", "please note"
    );

    // ── LLM Prompts ──────────────────────────────────────────

    private static final String CORRECTION_CLASSIFY_SYSTEM = """
        You are a signal classifier for an AI self-improvement system.
        Determine if the user's message is genuinely correcting the AI's previous response.

        Reply with EXACTLY one word:
        - CORRECTION: if the user is correcting a mistake or misunderstanding
        - NOT_CORRECTION: if the user is asking a new question, adding info, or changing topic

        Examples:
        "No, the function should return a List not an Array" -> CORRECTION
        "Actually you got the API name wrong" -> CORRECTION
        "Can you also check the database?" -> NOT_CORRECTION
        "What about using Postgres instead?" -> NOT_CORRECTION
        """;

    private static final String FEEDBACK_EXTRACT_SYSTEM = """
        You are a preference extractor for an AI self-improvement system.
        Extract the user's stated preference from their message.

        Return the preference as a single line in the format:
        key: value

        Where key is one of: response_style, execution_order, tool_preference, communication_frequency, other

        Examples:
        "Remember I prefer concise answers" -> response_style: concise
        "Always run tests before deploying" -> execution_order: test_before_deploy
        "Stop using curl, use the API instead" -> tool_preference: api_over_curl
        "Don't ask me to confirm every step" -> communication_frequency: low
        "Remember my project uses Java 21" -> other: project_uses_java_21

        If no clear preference, reply: NONE
        """;

    public LlmSignalDetector(ModelClient modelClient, SignalCollector signalCollector) {
        this.modelClient = modelClient;
        this.signalCollector = signalCollector;
    }

    /**
     * Creates a detector with no LLM (trigger-word-only mode for testing).
     */
    public LlmSignalDetector(SignalCollector signalCollector) {
        this(null, signalCollector);
    }

    // ── USER_CORRECTION detection ───────────────────────────

    /**
     * Check if a user message is a correction and emit a signal if so.
     *
     * @return true if a USER_CORRECTION signal was emitted
     */
    public boolean detectCorrection(String tenantId, String userId,
                                     String sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;

        // Stage 1: trigger-word matching (fast path)
        if (!containsTrigger(userMessage, CORRECTION_TRIGGERS)) {
            return false;
        }

        // Stage 2: LLM classification (if available)
        if (modelClient != null) {
            try {
                String classification = classifyWithLlm(userMessage);
                if (!"CORRECTION".equals(classification)) {
                    logger.debug("Trigger matched but LLM classified as NOT_CORRECTION: {}",
                                 truncate(userMessage, 80));
                    return false;
                }
            } catch (Exception e) {
                logger.warn("LLM classification failed, accepting trigger match: {}", e.getMessage());
                // Fall through - accept trigger match as signal
            }
        }

        signalCollector.emitSignal(tenantId, userId, SignalType.USER_CORRECTION,
                sessionId, "User correction detected: " + truncate(userMessage, 200), 1.0);
        logger.info("USER_CORRECTION signal emitted for user {} in session {}", userId, sessionId);
        return true;
    }

    // ── EXPLICIT_FEEDBACK detection ─────────────────────────

    /**
     * Check if a user message contains explicit preference feedback.
     *
     * @return the extracted preference string (e.g. "response_style: concise"), or null if none
     */
    public String detectExplicitFeedback(String tenantId, String userId,
                                          String sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;

        // Stage 1: trigger-word matching
        if (!containsTrigger(userMessage, FEEDBACK_TRIGGERS)) {
            return null;
        }

        // Stage 2: LLM extraction (if available)
        String preference = null;
        if (modelClient != null) {
            try {
                preference = extractPreferenceWithLlm(userMessage);
                if ("NONE".equals(preference) || preference == null) {
                    logger.debug("Trigger matched but LLM extracted no preference: {}",
                                 truncate(userMessage, 80));
                    return null;
                }
            } catch (Exception e) {
                logger.warn("LLM preference extraction failed, using raw message: {}", e.getMessage());
                preference = "other: " + truncate(userMessage, 100);
            }
        } else {
            // No LLM: use raw message as preference
            preference = "other: " + truncate(userMessage, 100);
        }

        signalCollector.emitSignal(tenantId, userId, SignalType.EXPLICIT_FEEDBACK,
                sessionId, "User feedback: " + preference, 1.0);
        logger.info("EXPLICIT_FEEDBACK signal emitted for user {}: {}", userId, preference);
        return preference;
    }

    // ── REPEAT_PATTERN detection ────────────────────────────

    /**
     * Check if a new session's first message is similar to recent sessions.
     * Uses simple keyword overlap (Jaccard similarity) for BM25-like detection.
     *
     * <p>This is a synchronous quick-check. Full BM25 similarity can be added
     * in the future using MemoryStore's existing BM25 implementation.</p>
     *
     * @param recentFirstMessages first messages of recent sessions (last 7 days)
     * @return true if similarity > 0.7 with any recent message
     */
    public boolean detectRepeatPattern(String tenantId, String userId,
                                        String sessionId, String currentMessage,
                                        List<String> recentFirstMessages) {
        if (currentMessage == null || currentMessage.isBlank()) return false;
        if (recentFirstMessages == null || recentFirstMessages.isEmpty()) return false;

        Set<String> currentTokens = tokenize(currentMessage);
        if (currentTokens.isEmpty()) return false;

        for (String recentMsg : recentFirstMessages) {
            if (recentMsg == null || recentMsg.isBlank()) continue;
            Set<String> recentTokens = tokenize(recentMsg);
            if (recentTokens.isEmpty()) continue;

            double similarity = jaccardSimilarity(currentTokens, recentTokens);
            if (similarity > 0.7) {
                signalCollector.emitSignal(tenantId, userId, SignalType.REPEAT_PATTERN,
                        sessionId,
                        "Repeat pattern detected (similarity=" + String.format("%.2f", similarity) +
                        "): " + truncate(currentMessage, 100),
                        0.5);
                logger.info("REPEAT_PATTERN signal emitted for user {} (similarity={})",
                            userId, String.format("%.2f", similarity));
                return true;
            }
        }
        return false;
    }

    // ── Internal helpers ────────────────────────────────────

    private boolean containsTrigger(String message, Set<String> triggers) {
        String lower = message.toLowerCase();
        for (String trigger : triggers) {
            if (lower.contains(trigger.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String classifyWithLlm(String userMessage) {
        List<ModelMessage> messages = List.of(
                ModelMessage.system(CORRECTION_CLASSIFY_SYSTEM),
                ModelMessage.user("User message: " + truncate(userMessage, 500))
        );
        ChatCompletionResponse response = modelClient.chatCompletion(
                messages, List.of(), false,
                Map.of("temperature", 0.1, "max_tokens", 10)
        );
        String content = response != null ? response.getContent() : null;
        return content != null ? content.trim().toUpperCase() : "NOT_CORRECTION";
    }

    private String extractPreferenceWithLlm(String userMessage) {
        List<ModelMessage> messages = List.of(
                ModelMessage.system(FEEDBACK_EXTRACT_SYSTEM),
                ModelMessage.user("User message: " + truncate(userMessage, 500))
        );
        ChatCompletionResponse response = modelClient.chatCompletion(
                messages, List.of(), false,
                Map.of("temperature", 0.2, "max_tokens", 100)
        );
        String content = response != null ? response.getContent() : null;
        return content != null ? content.trim() : "NONE";
    }

    private Set<String> tokenize(String text) {
        // Simple tokenization: lowercase, split on non-alphanumeric (works for both CN and EN)
        String lower = text.toLowerCase();
        String[] parts = lower.split("[^a-z0-9\\u4e00-\\u9fff]+");
        // Filter out very short tokens
        Set<String> tokens = new java.util.HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) tokens.add(p);
        }
        return tokens;
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        Set<String> intersection = new java.util.HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
