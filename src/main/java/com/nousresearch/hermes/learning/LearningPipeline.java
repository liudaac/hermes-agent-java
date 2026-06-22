package com.nousresearch.hermes.learning;

import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.skills.SkillManager;
import com.nousresearch.hermes.trajectory.TrajectoryCollector;
import com.nousresearch.hermes.trajectory.TrajectoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Unified learning pipeline — single entry point for all knowledge extraction,
 * insight mining, and curiosity-driven learning.
 *
 * <p>All three engines are instantiated internally; callers only talk to this facade.</p>
 */
public class LearningPipeline {

    private static final Logger logger = LoggerFactory.getLogger(LearningPipeline.class);

    private final KnowledgeExtractor knowledgeExtractor;
    private final CuriosityEngine curiosityEngine;
    private final InsightExtractor insightExtractor;

    public LearningPipeline(MemoryManager memoryManager, SkillManager skillManager) {
        this.knowledgeExtractor = new KnowledgeExtractor(memoryManager, skillManager);
        this.curiosityEngine = null; // requires ModelClient + TrajectoryCollector — set via setter
        this.insightExtractor = new InsightExtractor();
    }

    public LearningPipeline(MemoryManager memoryManager, SkillManager skillManager,
                            ModelClient modelClient, TrajectoryCollector trajectoryCollector) {
        this.knowledgeExtractor = new KnowledgeExtractor(memoryManager, skillManager);
        this.curiosityEngine = new CuriosityEngine(modelClient, memoryManager, trajectoryCollector);
        this.insightExtractor = new InsightExtractor();
    }

    // ------------------------------------------------------------------
    // Session-level extraction (LLM-based structured extraction)
    // ------------------------------------------------------------------

    /**
     * Extract insights and memory from a completed session.
     */
    public KnowledgeExtractor.ExtractionResult extractFromSession(String sessionId, List<ModelMessage> messages) {
        return knowledgeExtractor.onSessionEnd(sessionId, messages);
    }

    /**
     * Extract knowledge when a session ends.
     */
    public KnowledgeExtractor.ExtractionResult onSessionEnd(String sessionId, List<ModelMessage> messages) {
        return knowledgeExtractor.onSessionEnd(sessionId, messages);
    }

    // ------------------------------------------------------------------
    // Trajectory-level extraction (pattern-based)
    // ------------------------------------------------------------------

    /**
     * Extract reusable patterns from a single trajectory entry.
     */
    public List<String> extractFromTrajectory(TrajectoryEntry entry) {
        return insightExtractor.extract(entry);
    }

    /**
     * Generate a human-readable summary of a trajectory.
     */
    public String summarizeTrajectory(TrajectoryEntry entry) {
        return insightExtractor.generateSummary(entry);
    }

    // ------------------------------------------------------------------
    // Curiosity-driven learning (proactive knowledge gap filling)
    // ------------------------------------------------------------------

    /**
     * Run a curiosity scan to identify and fill knowledge gaps.
     * @return number of topics researched and stored
     */
    public int runCuriosityScan() {
        if (curiosityEngine == null) {
            logger.warn("CuriosityEngine not configured (missing ModelClient/TrajectoryCollector)");
            return 0;
        }
        return curiosityEngine.run();
    }
}
