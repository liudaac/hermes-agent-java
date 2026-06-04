package com.nousresearch.hermes.learning;

/**
 * Configurable thresholds and behaviour for {@link KnowledgeExtractor}'s
 * structured-extraction pipeline.
 *
 * <p>Values are loaded from {@code learning.*} config keys; see
 * {@link KnowledgeExtractor} constructor for the keys read.
 */
public class ExtractionPolicy {

    /** Minimum confidence required to write a fact into MEMORY.md. */
    private final double memoryConfidenceThreshold;

    /** Minimum confidence required to update USER.md. */
    private final double userProfileConfidenceThreshold;

    /** Minimum confidence required before a skill hint is even surfaced. */
    private final double skillConfidenceThreshold;

    /** Hard cap on how many items per bucket we will accept from a single session. */
    private final int maxItemsPerBucket;

    /** Whether to enable LLM extraction at all (falls back to heuristic when false). */
    private final boolean llmEnabled;

    public ExtractionPolicy(double memoryConfidenceThreshold,
                            double userProfileConfidenceThreshold,
                            double skillConfidenceThreshold,
                            int maxItemsPerBucket,
                            boolean llmEnabled) {
        this.memoryConfidenceThreshold = memoryConfidenceThreshold;
        this.userProfileConfidenceThreshold = userProfileConfidenceThreshold;
        this.skillConfidenceThreshold = skillConfidenceThreshold;
        this.maxItemsPerBucket = Math.max(1, maxItemsPerBucket);
        this.llmEnabled = llmEnabled;
    }

    public static ExtractionPolicy defaults() {
        return new ExtractionPolicy(0.70, 0.75, 0.80, 5, true);
    }

    public double getMemoryConfidenceThreshold() { return memoryConfidenceThreshold; }
    public double getUserProfileConfidenceThreshold() { return userProfileConfidenceThreshold; }
    public double getSkillConfidenceThreshold() { return skillConfidenceThreshold; }
    public int getMaxItemsPerBucket() { return maxItemsPerBucket; }
    public boolean isLlmEnabled() { return llmEnabled; }
}
