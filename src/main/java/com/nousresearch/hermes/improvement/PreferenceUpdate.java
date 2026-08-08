package com.nousresearch.hermes.improvement;

/**
 * A user preference update extracted from improvement signals.
 *
 * <p>Preferences are written to MemoryStore as MemoryEntry(type=PREFERENCE)
 * and naturally injected into future conversations via searchMemories().</p>
 *
 * @param userId     user
 * @param key        preference key: response_style, execution_order, tool_preference,
 *                   communication_frequency, other
 * @param oldValue   previous value (nullable)
 * @param newValue   new value
 * @param confidence 0.0-1.0
 * @param evidence   supporting evidence string
 */
public record PreferenceUpdate(
        String userId,
        String key,
        String oldValue,
        String newValue,
        double confidence,
        String evidence
) {}
