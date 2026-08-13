package com.nousresearch.hermes.harness.compaction;

import com.nousresearch.hermes.model.ModelMessage;

import java.util.List;

/**
 * Compaction engine: reduces context window usage by summarizing old messages.
 *
 * <p>Two trigger modes:</p>
 * <ul>
 *   <li>{@link CompactionTrigger#PRESSURE} - proactive, checked before each model call</li>
 *   <li>{@link CompactionTrigger#CONTEXT_OVERFLOW} - reactive, after LLM reports context overflow</li>
 * </ul>
 *
 * <p>Implementation should:</p>
 * <ol>
 *   <li>Estimate current token usage</li>
 *   <li>Select a compactable range (keep recent messages, maintain tool pairing)</li>
 *   <li>Generate a summary (LLM-based preferred, extractive fallback)</li>
 *   <li>Replace the range with the summary message</li>
 * </ol>
 */
public interface CompactionEngine {

    /**
     * Check if compaction is needed and perform it if so.
     *
     * @param history    the conversation history (modified in place)
     * @param trigger    why compaction is being considered
     * @param modelClient  optional model client for LLM-based summarization
     * @return compaction result, or {@link CompactionResult#skipped()} if not needed
     */
    CompactionResult compactIfNeeded(
        List<ModelMessage> history,
        CompactionTrigger trigger,
        com.nousresearch.hermes.model.ModelClient modelClient
    );

    /**
     * Explicitly compact, even below pressure thresholds.
     */
    CompactionResult compact(
        List<ModelMessage> history,
        com.nousresearch.hermes.model.ModelClient modelClient
    );
}
