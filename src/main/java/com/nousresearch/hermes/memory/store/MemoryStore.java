package com.nousresearch.hermes.memory.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Centralised memory store for multi-tenant Agent memory.
 *
 * <p>Two domains:</p>
 * <ul>
 *   <li><b>Short-term</b> &mdash; session-scoped conversation messages with
 *       time-windowed decay (FULL &rarr; WARM &rarr; COOL &rarr; EVICT).</li>
 *   <li><b>Long-term</b> &mdash; user/agent-scoped facts, preferences and
 *       decisions with vector + BM25 + recency fusion retrieval.</li>
 * </ul>
 *
 * <p>Every method takes {@code tenantId} as the first parameter.
 * Multi-tenant isolation is enforced at the store level.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link LocalMemoryStore} &mdash; in-memory, zero dependencies (dev / single-node).</li>
 *   <li>{@code RedisMemoryStore} &mdash; Redis Sorted Sets + TTL (Sprint B).</li>
 *   <li>{@code PostgresMemoryStore} &mdash; pgvector + tsvector + BM25 (Sprint C).</li>
 * </ul>
 */
public interface MemoryStore {

    // ══════════════════════════════════════════════════════════════════
    //  Short-term: Session Memory (with decay)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Append a message to the session's short-term memory.
     *
     * @param tenantId  tenant identifier
     * @param sessionId session identifier
     * @param role      message role ("user", "assistant", "tool", "system")
     * @param content   message content
     */
    void appendSessionMessage(String tenantId, String sessionId,
                              String role, String content);

    /**
     * Recall session memory, already weighted by the decay policy.
     *
     * <p>Results include:</p>
     * <ul>
     *   <li>FULL &amp; WARM: original messages (WARM at reduced weight)</li>
     *   <li>COOL: LLM-generated summaries (not original messages)</li>
     *   <li>EVICT: excluded (facts already promoted to long-term)</li>
     * </ul>
     *
     * @param tenantId  tenant identifier
     * @param sessionId session identifier
     * @param query     relevance query (current user message); empty = no relevance scoring
     * @param limit     max results
     * @param policy    decay policy defining stage windows and weights
     * @return ranked list of memories with decay-applied scores
     */
    List<MemoryRecall> recallSession(String tenantId, String sessionId,
                                     String query, int limit,
                                     DecayPolicy policy);

    /**
     * Run a decay cycle for one session:
     * <ul>
     *   <li>WARM &rarr; COOL: batch-summarise messages older than {@code policy.warmWindow}</li>
     *   <li>COOL &rarr; EVICT: extract key facts and write them to long-term memory,
     *       then remove the summary from short-term.</li>
     * </ul>
     *
     * @return summary of what happened during this cycle
     */
    DecayResult runDecayCycle(String tenantId, String sessionId,
                              DecayPolicy policy,
                              SummaryFunction summariser,
                              FactExtractor factExtractor);

    /**
     * Clear all short-term memory for a session.
     */
    void clearSession(String tenantId, String sessionId);

    /**
     * Get statistics about the current state of a session's memory.
     */
    SessionMemoryStats getSessionStats(String tenantId, String sessionId);

    // ══════════════════════════════════════════════════════════════════
    //  Long-term: Agent / User Memory (persistent)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Store a long-term memory entry.
     *
     * @return the generated memory ID
     */
    String addMemory(MemoryEntry entry);

    /**
     * Search long-term memories using fusion retrieval (semantic + BM25 + recency).
     *
     * @param tenantId tenant identifier
     * @param userId   user identifier (nullable for agent-scoped memories)
     * @param query    search query
     * @param limit    max results
     * @return ranked memory entries
     */
    List<MemoryEntry> searchMemories(String tenantId, String userId,
                                     String query, int limit);

    /**
     * Update an existing memory entry.
     */
    void updateMemory(String memoryId, MemoryEntry entry);

    /**
     * Invalidate a memory (mark as no longer current, not delete).
     * Sets {@code validUntil = now}.
     */
    void invalidateMemory(String memoryId);

    /**
     * Permanently delete a memory.
     */
    void deleteMemory(String memoryId);

    // ══════════════════════════════════════════════════════════════════
    //  Agent Experience (learned patterns)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Record an experience learned by an agent.
     */
    void addAgentExperience(String tenantId, String agentId,
                            String category, String content);

    /**
     * Retrieve agent experiences by category.
     */
    List<String> getAgentExperiences(String tenantId, String agentId,
                                     String category, int limit);

    // ══════════════════════════════════════════════════════════════════
    //  Functional interfaces for decay-time LLM calls
    // ══════════════════════════════════════════════════════════════════

    /**
     * Function that summarises a batch of messages into a compact summary.
     * Called during WARM&rarr;COOL transition.
     */
    @FunctionalInterface
    interface SummaryFunction {
        String summarise(List<SessionMessage> messages);
    }

    /**
     * Function that extracts key facts from a summary.
     * Called during COOL&rarr;EVICT transition.
     */
    @FunctionalInterface
    interface FactExtractor {
        List<String> extract(String summary, int maxFacts);
    }

    /**
     * A single session message with timestamp.
     */
    record SessionMessage(String role, String content, Instant timestamp) {}

    /**
     * A recalled memory item with decay metadata.
     */
    record MemoryRecall(
            String content,
            String role,
            RecallStage stage,
            double score,
            boolean summary,
            Instant originalTime
    ) {}

    /**
     * Result of one decay cycle run.
     */
    record DecayResult(
            int compressedToCool,
            int evictedFromCool,
            int factsExtracted,
            List<String> extractedFacts,
            Duration duration
    ) {}

    /**
     * Session memory statistics.
     */
    record SessionMemoryStats(
            int fullCount,
            int warmCount,
            int coolCount,
            int evictedCount,
            Instant lastDecayRun,
            Instant earliestMessage,
            Instant latestMessage
    ) {}
}
