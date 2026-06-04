package com.nousresearch.hermes.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Agent-level evaluation metrics.
 *
 * <p>Collects lightweight, structured metrics from across the agent's
 * subsystems so that improvements (or regressions) in intelligence can be
 * observed objectively. No external dependencies — data is logged as JSON
 * and can be scraped by any log aggregator or pushed to a dashboard.</p>
 *
 * <p>Tracked dimensions:</p>
 * <ul>
 *   <li><b>Task success</b> — reflection score distribution</li>
 *   <li><b>Tool reliability</b> — first-try success rate, latency</li>
 *   <li><b>Memory quality</b> — retrieval hit rate, card size efficiency</li>
 *   <li><b>Learning yield</b> — curiosity findings per run, knowledge extraction rate</li>
 *   <li><b>Confidence calibration</b> — how often we warn vs actual failure</li>
 * </ul>
 */
public class AgentEvalMetrics {

    private static final Logger logger = LoggerFactory.getLogger(AgentEvalMetrics.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Task Success (from ReflectionEngine) ---
    private final AtomicInteger reflectionCount = new AtomicInteger();
    private final DoubleAdder reflectionScoreSum = new DoubleAdder();
    private final AtomicInteger highScoreCount = new AtomicInteger();   // >= 0.8
    private final AtomicInteger lowScoreCount = new AtomicInteger();    // < 0.5

    // --- Tool Reliability (from ToolPerformanceTracker) ---
    private final AtomicInteger toolCallsTotal = new AtomicInteger();
    private final AtomicInteger toolCallsSuccess = new AtomicInteger();
    private final AtomicLong toolLatencySumMs = new AtomicLong();

    // --- Memory Quality (from MemoryCardIntegrator / MemoryRetriever) ---
    private final AtomicInteger memoryQueries = new AtomicInteger();
    private final AtomicInteger memoryHits = new AtomicInteger();       // card had >0 entries
    private final AtomicLong memoryCardCharsSum = new AtomicLong();

    // --- Learning Yield (from CuriosityEngine / KnowledgeExtractor) ---
    private final AtomicInteger curiosityRuns = new AtomicInteger();
    private final AtomicInteger curiosityFindings = new AtomicInteger();
    private final AtomicInteger knowledgeExtractionRuns = new AtomicInteger();
    private final AtomicInteger knowledgeItemsExtracted = new AtomicInteger();

    // --- Confidence Calibration (from ConfidenceCalibrator) ---
    private final AtomicInteger calibrationsTotal = new AtomicInteger();
    private final AtomicInteger calibrationsCaution = new AtomicInteger();
    private final AtomicInteger calibrationsVerify = new AtomicInteger();
    private final AtomicInteger actualFailuresAfterWarning = new AtomicInteger(); // user said "wrong"

    // ------------------------------------------------------------------

    public void recordReflection(double taskScore) {
        reflectionCount.incrementAndGet();
        reflectionScoreSum.add(taskScore);
        if (taskScore >= 0.8) highScoreCount.incrementAndGet();
        else if (taskScore < 0.5) lowScoreCount.incrementAndGet();
    }

    public void recordToolCall(boolean success, long latencyMs) {
        toolCallsTotal.incrementAndGet();
        if (success) toolCallsSuccess.incrementAndGet();
        toolLatencySumMs.addAndGet(latencyMs);
    }

    public void recordMemoryQuery(int entriesReturned, int cardChars) {
        memoryQueries.incrementAndGet();
        if (entriesReturned > 0) memoryHits.incrementAndGet();
        memoryCardCharsSum.addAndGet(cardChars);
    }

    public void recordCuriosityRun(int findingsStored) {
        curiosityRuns.incrementAndGet();
        curiosityFindings.addAndGet(findingsStored);
    }

    public void recordKnowledgeExtraction(int itemsExtracted) {
        knowledgeExtractionRuns.incrementAndGet();
        knowledgeItemsExtracted.addAndGet(itemsExtracted);
    }

    public void recordCalibration(com.nousresearch.hermes.agent.ConfidenceCalibrator.Action action) {
        calibrationsTotal.incrementAndGet();
        switch (action) {
            case CAUTION -> calibrationsCaution.incrementAndGet();
            case VERIFY -> calibrationsVerify.incrementAndGet();
        }
    }

    public void recordFailureAfterWarning() {
        actualFailuresAfterWarning.incrementAndGet();
    }

    // ------------------------------------------------------------------

    /**
     * Build a snapshot of current metrics.
     */
    public EvalSnapshot snapshot() {
        int refCount = reflectionCount.get();
        int toolTotal = toolCallsTotal.get();
        int memQ = memoryQueries.get();
        int curRuns = curiosityRuns.get();
        int knowRuns = knowledgeExtractionRuns.get();
        int calTotal = calibrationsTotal.get();

        return new EvalSnapshot(
            Instant.now(),
            refCount,
            refCount > 0 ? reflectionScoreSum.sum() / refCount : 0.0,
            refCount > 0 ? highScoreCount.get() / (double) refCount : 0.0,
            refCount > 0 ? lowScoreCount.get() / (double) refCount : 0.0,
            toolTotal,
            toolTotal > 0 ? toolCallsSuccess.get() / (double) toolTotal : 0.0,
            toolTotal > 0 ? toolLatencySumMs.get() / toolTotal : 0,
            memQ,
            memQ > 0 ? memoryHits.get() / (double) memQ : 0.0,
            memQ > 0 ? memoryCardCharsSum.get() / memQ : 0,
            curRuns,
            curRuns > 0 ? curiosityFindings.get() / (double) curRuns : 0.0,
            knowRuns,
            knowRuns > 0 ? knowledgeItemsExtracted.get() / (double) knowRuns : 0.0,
            calTotal,
            calTotal > 0 ? calibrationsCaution.get() / (double) calTotal : 0.0,
            calTotal > 0 ? calibrationsVerify.get() / (double) calTotal : 0.0,
            actualFailuresAfterWarning.get()
        );
    }

    /**
     * Log a JSON snapshot. Call periodically (e.g. heartbeat, end of session).
     */
    public void logSnapshot() {
        EvalSnapshot s = snapshot();
        try {
            ObjectNode json = MAPPER.createObjectNode();
            json.put("timestamp", s.timestamp().toString());
            json.put("type", "agent_eval");
            json.put("reflection.count", s.reflectionCount());
            json.put("reflection.avgScore", fmt(s.avgReflectionScore()));
            json.put("reflection.highScoreRate", fmt(s.highScoreRate()));
            json.put("reflection.lowScoreRate", fmt(s.lowScoreRate()));
            json.put("tool.calls", s.toolCallsTotal());
            json.put("tool.firstTrySuccess", fmt(s.toolFirstTrySuccessRate()));
            json.put("tool.avgLatencyMs", s.avgToolLatencyMs());
            json.put("memory.queries", s.memoryQueries());
            json.put("memory.hitRate", fmt(s.memoryHitRate()));
            json.put("memory.avgCardChars", s.avgMemoryCardChars());
            json.put("curiosity.runs", s.curiosityRuns());
            json.put("curiosity.avgFindings", fmt(s.avgCuriosityFindings()));
            json.put("knowledge.runs", s.knowledgeExtractionRuns());
            json.put("knowledge.avgItems", fmt(s.avgKnowledgeItems()));
            json.put("confidence.calibrations", s.calibrationsTotal());
            json.put("confidence.cautionRate", fmt(s.cautionRate()));
            json.put("confidence.verifyRate", fmt(s.verifyRate()));
            json.put("confidence.failuresAfterWarning", s.failuresAfterWarning());
            logger.info("AGENT_EVAL {}", MAPPER.writeValueAsString(json));
        } catch (Exception e) {
            logger.debug("Failed to log eval snapshot: {}", e.getMessage());
        }
    }

    private static double fmt(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
