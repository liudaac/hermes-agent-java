package com.nousresearch.hermes.harness.compaction;

/**
 * Result of a compaction operation.
 *
 * @param messagesCompacted  number of original messages replaced
 * @param tokensSaved        estimated tokens saved
 * @param summary            the summary text that replaced the compacted range
 * @param success            whether the compaction completed successfully
 */
public record CompactionResult(
    int messagesCompacted,
    int tokensSaved,
    String summary,
    boolean success
) {
    /** Create a "no compaction needed" result. */
    public static CompactionResult skipped() {
        return new CompactionResult(0, 0, null, false);
    }
}
