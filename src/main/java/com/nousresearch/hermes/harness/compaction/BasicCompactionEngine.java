package com.nousresearch.hermes.harness.compaction;

import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Default compaction engine with LLM-based summarization.
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Estimate token usage (chars / 4)</li>
 *   <li>If above threshold, select compactable range (preserve recent + tool pairing)</li>
 *   <li>Try LLM summarization; fall back to extractive if LLM unavailable</li>
 *   <li>Replace the range with a summary message in-place</li>
 * </ol>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code thresholdRatio} - fraction of context window that triggers pressure (default 0.7)</li>
 *   <li>{@code retainRatio} - fraction of messages to keep (default 0.3)</li>
 *   <li>{@code retainMin} - minimum recent messages to always keep (default 6)</li>
 *   <li>{@code contextWindow} - max tokens (default 100k)</li>
 *   <li>{@code reservedTokens} - reserved for system prompt + tools (default 8k)</li>
 * </ul>
 */
public class BasicCompactionEngine implements CompactionEngine {
    private static final Logger logger = LoggerFactory.getLogger(BasicCompactionEngine.class);

    private static final int CHARS_PER_TOKEN = 4;

    private final double thresholdRatio;
    private final double retainRatio;
    private final int retainMin;
    private final int contextWindow;
    private final int reservedTokens;

    public BasicCompactionEngine() {
        this(0.7, 0.3, 6, 100_000, 8_000);
    }

    public BasicCompactionEngine(
        double thresholdRatio, double retainRatio, int retainMin,
        int contextWindow, int reservedTokens
    ) {
        this.thresholdRatio = thresholdRatio;
        this.retainRatio = retainRatio;
        this.retainMin = retainMin;
        this.contextWindow = contextWindow;
        this.reservedTokens = reservedTokens;
    }

    @Override
    public CompactionResult compactIfNeeded(
        List<ModelMessage> history, CompactionTrigger trigger, ModelClient modelClient
    ) {
        int available = contextWindow - reservedTokens;
        int current = estimateTokens(history);

        boolean shouldCompact = switch (trigger) {
            case PRESSURE -> current > available * thresholdRatio;
            case CONTEXT_OVERFLOW -> true; // always compact on overflow
        };

        if (!shouldCompact) return CompactionResult.skipped();

        return compact(history, modelClient);
    }

    @Override
    public CompactionResult compact(List<ModelMessage> history, ModelClient modelClient) {
        if (history.size() <= retainMin) return CompactionResult.skipped();

        int available = contextWindow - reservedTokens;
        int current = estimateTokens(history);
        int target = (int) (available * retainRatio);

        // Determine how many recent messages to keep
        int retainCount = Math.max(retainMin, history.size() / 4);
        // Don't compact more than 70% of messages
        int maxCompactable = history.size() - retainCount;
        if (maxCompactable <= 0) return CompactionResult.skipped();

        // Find the compactable range (after system prompt, before recent messages)
        // Skip system messages at the start
        int startIdx = 0;
        while (startIdx < history.size() && "system".equals(history.get(startIdx).getRole())) {
            startIdx++;
        }
        int endIdx = history.size() - retainCount;

        if (endIdx <= startIdx) return CompactionResult.skipped();

        // Build the range to compact
        List<ModelMessage> toCompact = new ArrayList<>(history.subList(startIdx, endIdx));

        // Adjust to maintain tool pairing: don't end on an orphaned tool call
        endIdx = adjustForToolPairing(history, startIdx, endIdx);
        toCompact = new ArrayList<>(history.subList(startIdx, endIdx));

        if (toCompact.isEmpty()) return CompactionResult.skipped();

        int tokensBefore = estimateTokens(toCompact);

        // Generate summary
        String summary = summarize(toCompact, modelClient);
        if (summary == null || summary.isBlank()) {
            logger.debug("Compaction produced empty summary, skipping");
            return CompactionResult.skipped();
        }

        // Replace the range with summary message
        synchronized (history) {
            // Remove compacted messages
            for (int i = endIdx - 1; i >= startIdx; i--) {
                history.remove(i);
            }
            // Insert summary as a user message at the same position
            history.add(startIdx, ModelMessage.user(
                "[context-compressed] Previous conversation summary (" + toCompact.size()
                + " messages):\n\n" + summary
            ));
        }

        int tokensAfter = estimateTokens(Collections.singletonList(
            history.get(startIdx)));
        int tokensSaved = tokensBefore - tokensAfter;

        logger.info("Compaction: {} messages -> 1 summary, ~{} tokens saved (trigger={})",
            toCompact.size(), tokensSaved, "manual");

        return new CompactionResult(toCompact.size(), tokensSaved, summary, true);
    }

    /**
     * Generate a summary using LLM if available, else extractive fallback.
     */
    private String summarize(List<ModelMessage> messages, ModelClient modelClient) {
        // Try LLM summarization first
        if (modelClient != null) {
            try {
                String llmSummary = summarizeWithLlm(messages, modelClient);
                if (llmSummary != null && !llmSummary.isBlank()) {
                    return llmSummary;
                }
            } catch (Exception e) {
                logger.warn("LLM summarization failed, falling back to extractive: {}", e.getMessage());
            }
        }

        // Extractive fallback
        return extractiveSummary(messages);
    }

    /**
     * LLM-based summarization.
     */
    private String summarizeWithLlm(List<ModelMessage> messages, ModelClient modelClient) {
        StringBuilder conversationText = new StringBuilder();
        for (ModelMessage msg : messages) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isEmpty()) continue;

            // Skip shielded tool results
            if ("tool".equals(role) && content.startsWith("[shielded]")) continue;

            // Truncate very long messages
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }

            conversationText.append(role).append(": ").append(content).append("\n\n");
        }

        if (conversationText.isEmpty()) return null;

        String prompt = """
            Summarize the following conversation, preserving:
            1. Key decisions and their rationale
            2. Important facts learned (file paths, configs, error messages)
            3. Task progress and what was accomplished
            4. Any unresolved issues or next steps

            Be concise but complete. Use bullet points.

            Conversation to summarize:
            """ + conversationText;

        List<ModelMessage> summaryRequest = List.of(
            ModelMessage.system("You are a conversation summarizer. Be concise and factual."),
            ModelMessage.user(prompt)
        );

        ChatCompletionResponse response = modelClient.chatCompletion(
            summaryRequest, List.of(), false, Map.of("max_tokens", "800"));
        if (response.isSuccess() && response.getMessage() != null) {
            return response.getMessage().getContent();
        }
        return null;
    }

    /**
     * Extractive summary fallback (no LLM call).
     */
    private String extractiveSummary(List<ModelMessage> messages) {
        StringBuilder summary = new StringBuilder();
        int kept = 0;
        for (ModelMessage msg : messages) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isEmpty()) continue;

            // Skip shielded tool results and short assistant wrappers
            if ("tool".equals(role) && content.startsWith("[shielded]")) continue;
            if ("assistant".equals(role) && content.length() < 50) continue;

            // Truncate
            String truncated = content.length() > 300
                ? content.substring(0, 300) + "..." : content;

            String label = "user".equals(role) ? "User"
                : "assistant".equals(role) ? "Assistant" : "Tool";
            summary.append(label).append(": ").append(truncated).append("\n\n");
            kept++;
            if (kept >= 15) break; // limit summary size
        }
        return summary.toString();
    }

    /**
     * Adjust end index to avoid splitting a tool call/result pair.
     */
    private int adjustForToolPairing(List<ModelMessage> history, int start, int end) {
        // If the message just before end is an assistant message with tool_calls,
        // move end back to include the tool results
        if (end > 0 && end < history.size()) {
            ModelMessage boundary = history.get(end - 1);
            if ("assistant".equals(boundary.getRole())
                && boundary.getToolCalls() != null
                && !boundary.getToolCalls().isEmpty()) {
                // Find where the tool results end
                while (end < history.size() && "tool".equals(history.get(end).getRole())) {
                    end++;
                }
            }
        }
        // If end lands on a tool message, move it forward to include all consecutive tools
        while (end < history.size() && "tool".equals(history.get(end).getRole())) {
            end++;
        }
        return end;
    }

    /**
     * Estimate token count from message list.
     */
    private int estimateTokens(List<ModelMessage> messages) {
        int chars = 0;
        for (ModelMessage m : messages) {
            if (m.getContent() != null) chars += m.getContent().length();
            chars += 16; // role overhead
        }
        return chars / CHARS_PER_TOKEN;
    }
}
