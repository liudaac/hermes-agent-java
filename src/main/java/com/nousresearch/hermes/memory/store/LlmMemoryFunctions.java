package com.nousresearch.hermes.memory.store;

import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM-backed implementations of {@link MemoryStore.SummaryFunction} and
 * {@link MemoryStore.FactExtractor}.
 *
 * <p>Uses a {@link ModelClient} to generate summaries during WARM&rarr;COOL
 * decay transitions and extract key facts during COOL&rarr;EVICT transitions.</p>
 *
 * <h2>Summary prompt</h2>
 * <p>Given a batch of session messages, produces a concise summary preserving
 * key decisions, user preferences, and action items.</p>
 *
 * <h2>Fact extraction prompt</h2>
 * <p>Given a summary, extracts standalone factual statements suitable for
 * long-term memory storage.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var llmFuncs = new LlmMemoryFunctions(modelClient);
 * var scheduler = new DecayScheduler(memoryStore,
 *     llmFuncs.summaryFunction(),
 *     llmFuncs.factExtractor());
 * }</pre>
 */
public class LlmMemoryFunctions {

    private static final Logger logger = LoggerFactory.getLogger(LlmMemoryFunctions.class);

    private final ModelClient modelClient;
    private final String summaryModel;
    private final String factModel;

    // ── Prompts ──────────────────────────────────────────────

    private static final String SUMMARY_SYSTEM = """
        You are a conversation summariser for an AI agent's memory system.
        Summarise the following conversation messages into a concise summary.
        Preserve:
        - Key decisions and their rationale
        - User preferences and constraints
        - Action items and their status
        - Important technical details (APIs, configs, file paths)
        - Unresolved questions or open issues

        Keep the summary under 500 words. Use bullet points for clarity.
        Do not include greetings, filler, or redundant context.
        """;

    private static final String FACT_SYSTEM = """
        You are a fact extractor for an AI agent's long-term memory.
        Extract key standalone facts from the given summary that would be
        useful for future conversations. Each fact should be:
        - Self-contained (understandable without additional context)
        - Atomic (one piece of information per fact)
        - Durable (still relevant days later)

        Return each fact on a separate line, prefixed with "- ".
        Maximum %d facts. No headers or explanations.

        Examples:
        - User prefers dark mode UI themes
        - Project uses PostgreSQL 16 with pgvector extension
        - API rate limit is 100 requests per minute
        """;

    // ── Constructor ──────────────────────────────────────────

    /**
     * Create with a ModelClient, using the client's default model for both
     * summarisation and fact extraction.
     */
    public LlmMemoryFunctions(ModelClient modelClient) {
        this(modelClient, null, null);
    }

    /**
     * Create with explicit model aliases for summarisation and fact extraction.
     *
     * @param modelClient  the LLM client to use
     * @param summaryModel model alias for summarisation (null = client default)
     * @param factModel    model alias for fact extraction (null = client default)
     */
    public LlmMemoryFunctions(ModelClient modelClient, String summaryModel, String factModel) {
        this.modelClient = modelClient;
        this.summaryModel = summaryModel;
        this.factModel = factModel;
    }

    // ── SummaryFunction ──────────────────────────────────────

    /**
     * Create a SummaryFunction that uses the LLM to summarise messages.
     */
    public MemoryStore.SummaryFunction summaryFunction() {
        return this::summariseWithLlm;
    }

    /**
     * Create a FactExtractor that uses the LLM to extract facts.
     */
    public MemoryStore.FactExtractor factExtractor() {
        return this::extractFactsWithLlm;
    }

    // ── LLM calls ────────────────────────────────────────────

    private String summariseWithLlm(List<MemoryStore.SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        try {
            // Build user message from conversation
            StringBuilder sb = new StringBuilder();
            sb.append("Conversation to summarise:\n\n");
            for (var msg : messages) {
                sb.append("[").append(msg.role()).append("] ")
                  .append(msg.content())
                  .append("\n");
            }

            List<ModelMessage> llmMessages = new ArrayList<>();
            llmMessages.add(ModelMessage.system(SUMMARY_SYSTEM));
            llmMessages.add(ModelMessage.user(sb.toString()));

            var response = modelClient.chatCompletion(
                llmMessages, List.of(), false,
                Map.of("temperature", 0.3, "max_tokens", 800)
            );

            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                logger.debug("LLM summary generated: {} chars (from {} messages)",
                    response.getContent().length(), messages.size());
                return response.getContent().trim();
            }

            // Fallback to concat if LLM fails
            logger.warn("LLM summary returned empty, falling back to concat");
            return concatSummary(messages);

        } catch (Exception e) {
            logger.warn("LLM summarisation failed, falling back to concat: {}", e.getMessage());
            return concatSummary(messages);
        }
    }

    private List<String> extractFactsWithLlm(String summary, int maxFacts) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        if (maxFacts <= 0) {
            return List.of();
        }

        try {
            String systemPrompt = String.format(FACT_SYSTEM, maxFacts);
            String userPrompt = "Summary to extract facts from:\n\n" + summary;

            List<ModelMessage> llmMessages = new ArrayList<>();
            llmMessages.add(ModelMessage.system(systemPrompt));
            llmMessages.add(ModelMessage.user(userPrompt));

            var response = modelClient.chatCompletion(
                llmMessages, List.of(), false,
                Map.of("temperature", 0.2, "max_tokens", 500)
            );

            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                List<String> facts = parseFacts(response.getContent().trim(), maxFacts);
                logger.debug("LLM extracted {} facts from summary ({} chars)",
                    facts.size(), summary.length());
                return facts;
            }

            // Fallback: return summary as single fact
            logger.warn("LLM fact extraction returned empty, using summary as single fact");
            return maxFacts > 0 ? List.of(summary) : List.of();

        } catch (Exception e) {
            logger.warn("LLM fact extraction failed, using summary as fallback: {}", e.getMessage());
            return maxFacts > 0 ? List.of(summary) : List.of();
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Parse LLM response into a list of facts.
     * Handles formats like "- fact" or "1. fact" or plain lines.
     */
    private static List<String> parseFacts(String text, int maxFacts) {
        List<String> facts = new ArrayList<>();
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Remove common prefixes: "- ", "* ", "1. ", "1) "
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).trim();
            } else if (trimmed.startsWith("* ")) {
                trimmed = trimmed.substring(2).trim();
            } else if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                trimmed = trimmed.replaceFirst("^\\d+[.)]\\s+", "").trim();
            }
            if (!trimmed.isEmpty() && facts.size() < maxFacts) {
                facts.add(trimmed);
            }
        }
        return facts;
    }

    /**
     * Fallback: simple concatenation summary (same as DecayScheduler default).
     */
    private static String concatSummary(List<MemoryStore.SessionMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (var msg : messages) {
            sb.append("[").append(msg.role()).append("] ")
              .append(msg.content(), 0, Math.min(200, msg.content().length()))
              .append("\n");
        }
        return sb.toString();
    }
}
