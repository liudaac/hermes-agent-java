package com.nousresearch.hermes.monitoring;

import java.time.Instant;

/**
 * Immutable snapshot of agent evaluation metrics at a point in time.
 */
public record EvalSnapshot(
    Instant timestamp,

    // Reflection
    int reflectionCount,
    double avgReflectionScore,
    double highScoreRate,
    double lowScoreRate,

    // Tool reliability
    int toolCallsTotal,
    double toolFirstTrySuccessRate,
    long avgToolLatencyMs,

    // Memory quality
    int memoryQueries,
    double memoryHitRate,
    long avgMemoryCardChars,

    // Learning yield
    int curiosityRuns,
    double avgCuriosityFindings,
    int knowledgeExtractionRuns,
    double avgKnowledgeItems,

    // Confidence calibration
    int calibrationsTotal,
    double cautionRate,
    double verifyRate,
    int failuresAfterWarning
) {}
