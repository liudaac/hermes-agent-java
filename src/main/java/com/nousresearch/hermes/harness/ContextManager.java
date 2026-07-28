package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-strategy context window manager.
 *
 * <p>Three layered strategies, applied in order of severity:</p>
 *
 * <ol>
 *   <li><b>Observation shielding</b> (lightest) - replace old tool result
 *       content with a short marker, keeping the tool call visible so the
 *       model knows what it did but doesn't see full output anymore.
 *       Inspired by JetBrains Junie.</li>
 *
 *   <li><b>Summary compression</b> (medium) - summarize old conversation
 *       segments into a single system message, preserving key decisions
 *       and outcomes. Inspired by Claude Code's context compression.</li>
 *
 *   <li><b>Hard truncation</b> (heaviest, fallback) - delete middle messages
 *       to stay under budget. Preserves system prompt + first user message +
 *       last N messages.</li>
 * </ol>
 *
 * <p>Strategies activate progressively: shielding triggers at 60% capacity,
 * compression at 80%, truncation at 95%.</p>
 */
public class ContextManager {
    private static final Logger logger = LoggerFactory.getLogger(ContextManager.class);

    /** Approximate tokens = chars / 4. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Default context window (128k tokens for modern models). */
    private final int maxTokens;

    /** Reserved space for system prompt + tools + response headroom. */
    private final int reservedTokens;

    /** Number of recent messages to always preserve. */
    private final int preserveRecent;

    /** Tool result content is truncated to this many chars when shielding. */
    private static final int SHIELDED_RESULT_LENGTH = 200;

    /** Marker prefix for shielded tool results. */
    private static final String SHIELDED_PREFIX = "[shielded] ";

    /** Marker for compressed conversation segments. */
    private static final String COMPRESSION_MARKER = "[context-compressed] ";

    public ContextManager(int maxTokens, int reservedTokens, int preserveRecent) {
        this.maxTokens = maxTokens;
        this.reservedTokens = reservedTokens;
        this.preserveRecent = preserveRecent;
    }

    /** Default: 128k token window, 8k reserved, preserve last 6 messages. */
    public ContextManager() {
        this(128_000, 8_000, 6);
    }

    /**
     * Apply context management strategies to the conversation history.
     * Modifies the list in place.
     *
     * @param history  the conversation history (modified in place)
     * @param emitter  event emitter (null = no events)
     * @return stats about what was done
     */
    public CompressionStats enforce(List<ModelMessage> history, EventEmitter emitter) {
        int availableTokens = maxTokens - reservedTokens;
        int currentTokens = estimateTokens(history);

        if (currentTokens <= availableTokens * 60 / 100) {
            // Under 60% - nothing to do
            return new CompressionStats(0, 0, 0, currentTokens);
        }

        int shielded = 0;
        int summarized = 0;
        int truncated = 0;

        // Phase 1: Observation shielding (trigger at 60%)
        if (currentTokens > availableTokens * 60 / 100) {
            shielded = shieldOldToolResults(history, preserveRecent + 2);
            currentTokens = estimateTokens(history);
            if (shielded > 0) {
                logger.debug("Shielded {} tool results", shielded);
            }
        }

        // Phase 2: Summary compression (trigger at 80%)
        if (currentTokens > availableTokens * 80 / 100) {
            summarized = compressOldSegment(history, preserveRecent);
            currentTokens = estimateTokens(history);
            if (summarized > 0) {
                logger.debug("Compressed {} messages into summary", summarized);
                if (emitter != null) {
                    emitter.emit(AgentEvent.CONTEXT_COMPRESSED, java.util.Map.of(
                        "strategy", "summary",
                        "messagesCompressed", summarized
                    ));
                }
            }
        }

        // Phase 3: Hard truncation (trigger at 95%)
        if (currentTokens > availableTokens * 95 / 100) {
            truncated = hardTruncate(history, preserveRecent);
            currentTokens = estimateTokens(history);
            if (truncated > 0) {
                logger.debug("Hard truncated {} messages", truncated);
                if (emitter != null) {
                    emitter.emit(AgentEvent.CONTEXT_COMPRESSED, java.util.Map.of(
                        "strategy", "truncate",
                        "messagesDropped", truncated
                    ));
                }
            }
        }

        return new CompressionStats(shielded, summarized, truncated, currentTokens);
    }

    // ==================== Phase 1: Observation Shielding ====================

    /**
     * Replace old tool result content with a truncated marker.
     * Keeps tool call visible (in the preceding assistant message)
     * but hides the full tool output.
     *
     * @param history         conversation history
     * @param preserveRecent  don't shield messages within this many of the end
     * @return number of tool results shielded
     */
    private int shieldOldToolResults(List<ModelMessage> history, int preserveRecent) {
        int shielded = 0;
        int cutoff = history.size() - preserveRecent;

        for (int i = 1; i < cutoff; i++) {  // skip system (index 0)
            ModelMessage msg = history.get(i);
            if (!"tool".equals(msg.getRole())) continue;
            if (msg.getContent() == null) continue;
            if (msg.getContent().startsWith(SHIELDED_PREFIX)) continue;  // already shielded

            String original = msg.getContent();
            String summary = original.length() > SHIELDED_RESULT_LENGTH
                ? original.substring(0, SHIELDED_RESULT_LENGTH) + "..."
                : original;

            // Replace content in-place
            history.set(i, ModelMessage.tool(
                SHIELDED_PREFIX + summary + " (" + original.length() + " chars)",
                msg.getToolCallId()
            ));
            shielded++;
        }

        return shielded;
    }

    // ==================== Phase 2: Summary Compression ====================

    /**
     * Compress old conversation segments into a summary message.
     *
     * Strategy: take messages from index 1 (after system) to cutoff - preserveRecent,
     * replace them with a single system message containing a structured summary.
     *
     * Without an LLM call, we do extractive summarization:
     * - Keep assistant messages that contain decisions (longer than 100 chars)
     * - Keep user messages (they're usually short and important)
     * - Drop shielded tool results entirely
     * - Drop assistant messages that are just tool call wrappers
     *
     * @param history         conversation history
     * @param preserveRecent  don't touch last N messages
     * @return number of messages compressed
     */
    private int compressOldSegment(List<ModelMessage> history, int preserveRecent) {
        int cutoff = history.size() - preserveRecent;
        if (cutoff <= 2) return 0;  // nothing to compress

        // Collect messages to compress (index 1 to cutoff-1)
        // But skip any existing compression summaries to avoid recursive nesting
        List<ModelMessage> toCompress = new ArrayList<>();
        for (int i = 1; i < cutoff; i++) {
            ModelMessage msg = history.get(i);
            // Skip existing compression summaries to prevent recursive nesting
            if ("system".equals(msg.getRole()) && msg.getContent() != null
                    && msg.getContent().startsWith(COMPRESSION_MARKER)) {
                continue;
            }
            toCompress.add(msg);
        }

        if (toCompress.isEmpty()) return 0;

        // Build extractive summary
        StringBuilder summary = new StringBuilder();
        summary.append(COMPRESSION_MARKER)
            .append("Previous conversation summary (")
            .append(toCompress.size())
            .append(" messages compressed):\n\n");

        int kept = 0;
        for (ModelMessage msg : toCompress) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isEmpty()) continue;

            // Skip shielded tool results
            if ("tool".equals(role) && content.startsWith(SHIELDED_PREFIX)) continue;

            // Skip very short assistant messages (likely tool call wrappers)
            if ("assistant".equals(role) && content.length() < 50) continue;

            // For tool results, extract just the key info, don't include raw JSON
            if ("tool".equals(role)) {
                // Try to extract stdout/error from tool result, skip raw JSON
                String extracted = extractToolSummary(content);
                if (extracted != null) {
                    summary.append("- Tool result: ").append(extracted).append("\n");
                }
                continue;
            }

            // Keep user and significant assistant messages
            String label = "user".equals(role) ? "User" : "assistant".equals(role) ? "Assistant" : "Tool";
            String truncated = content.length() > 300
                ? content.substring(0, 300) + "..."
                : content;
            summary.append(label).append(": ").append(truncated).append("\n\n");
            kept++;
        }

        if (kept == 0) return 0;

        // Remove ALL messages from index 1 to cutoff-1 (including old compression summaries)
        for (int i = cutoff - 1; i >= 1; i--) {
            history.remove(i);
        }
        // Insert fresh summary as a user message (not system - multiple system
        // messages confuse some models like glm)
        history.add(1, ModelMessage.user(summary.toString()));

        return toCompress.size();
    }

    /**
     * Extract a short human-readable summary from a tool result.
     * Avoids including raw JSON that could be truncated into invalid content.
     */
    private String extractToolSummary(String content) {
        if (content == null || content.isBlank()) return null;
        // Try to find stdout or error field
        int stdoutIdx = content.indexOf("\"stdout\"");
        if (stdoutIdx >= 0) {
            int colonIdx = content.indexOf(':', stdoutIdx);
            if (colonIdx >= 0) {
                int start = colonIdx + 2;
                while (start < content.length() && content.charAt(start) == ' ') start++;
                if (start < content.length() && content.charAt(start) == '"') {
                    int end = content.indexOf('"', start + 1);
                    if (end > start) {
                        String stdout = content.substring(start + 1, end);
                        return stdout.length() > 100 ? stdout.substring(0, 100) + "..." : stdout;
                    }
                }
            }
        }
        int errorIdx = content.indexOf("\"error\"");
        if (errorIdx >= 0) {
            int colonIdx = content.indexOf(':', errorIdx);
            if (colonIdx >= 0) {
                int start = colonIdx + 2;
                while (start < content.length() && content.charAt(start) == ' ') start++;
                if (start < content.length() && content.charAt(start) == '"') {
                    int end = content.indexOf('"', start + 1);
                    if (end > start) {
                        return "error: " + content.substring(start + 1, end);
                    }
                }
            }
        }
        // Fallback: first 80 chars, no JSON
        return content.length() > 80 ? content.substring(0, 80) + "..." : content;
    }

    // ==================== Phase 3: Hard Truncation ====================

    /**
     * Delete middle messages to stay under budget.
     * Preserves: system (index 0), first user message, last N messages.
     */
    private int hardTruncate(List<ModelMessage> history, int preserveRecent) {
        int target = (maxTokens - reservedTokens) * 75 / 100;
        int currentTokens = estimateTokens(history);
        int truncated = 0;

        // Preserve: index 0 (system), index 1 (compressed summary or first user),
        // and last preserveRecent messages
        int start = 2;
        int end = history.size() - preserveRecent;

        while (currentTokens > target && end > start) {
            history.remove(start);
            end--;
            currentTokens = estimateTokens(history);
            truncated++;
        }

        return truncated;
    }

    // ==================== Utilities ====================

    /** Estimate token count from character count (rough: 4 chars/token). */
    public int estimateTokens(List<ModelMessage> history) {
        int chars = 0;
        for (ModelMessage m : history) {
            if (m.getContent() != null) chars += m.getContent().length();
            // Account for role overhead (~4 tokens per message)
            chars += 16;
        }
        return chars / CHARS_PER_TOKEN;
    }

    /** Check if context is getting tight (over 60% capacity). */
    public boolean needsAttention(List<ModelMessage> history) {
        return estimateTokens(history) > (maxTokens - reservedTokens) * 60 / 100;
    }

    /** Stats returned from enforce(). */
    public record CompressionStats(
        int toolResultsShielded,
        int messagesSummarized,
        int messagesTruncated,
        int finalTokenEstimate
    ) {
        public boolean anythingDone() {
            return toolResultsShielded > 0 || messagesSummarized > 0 || messagesTruncated > 0;
        }
    }
}
