package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Session-level scratchpad — the agent's working memory for the current conversation.
 *
 * <p>Implements a four-layer memory architecture:</p>
 * <ul>
 *   <li><b>L1 Working Memory</b>: last N raw messages (kept in conversationHistory)</li>
 *   <li><b>L2 Rolling Summary</b>: LLM-compressed narrative of older turns</li>
 *   <li><b>L3 Long-term Memory</b>: MEMORY.md / USER.md (retrieved via MemoryRetriever)</li>
 *   <li><b>L4 Procedural Memory</b>: skills (injected by SkillManager)</li>
 * </ul>
 *
 * <p>The scratchpad is updated whenever the working memory exceeds a token
 * threshold: the oldest chunk is sent to the LLM for summarisation, and the
 * result replaces those messages in the rolling summary.</p>
 */
public class SessionScratchpad {

    private static final Logger logger = LoggerFactory.getLogger(SessionScratchpad.class);

    // L1: how many recent raw messages to keep uncompressed
    private static final int DEFAULT_WORKING_MEMORY_TURNS = 8;
    // L2: max turns before we trigger a rolling-summary batch
    private static final int DEFAULT_SUMMARY_BATCH_SIZE = 6;

    private final int workingMemoryTurns;
    private final int summaryBatchSize;

    // L2 state
    private final List<String> rollingSummaries = new ArrayList<>();
    private int totalTurnsSummarized = 0;

    // L2 key facts extracted from the session so far
    private final List<String> keyFacts = new ArrayList<>();

    public SessionScratchpad() {
        this(DEFAULT_WORKING_MEMORY_TURNS, DEFAULT_SUMMARY_BATCH_SIZE);
    }

    public SessionScratchpad(int workingMemoryTurns, int summaryBatchSize) {
        this.workingMemoryTurns = workingMemoryTurns;
        this.summaryBatchSize = summaryBatchSize;
    }

    /**
     * After each assistant response, check if we should compress old messages.
     *
     * @param history     the full conversation history (mutable)
     * @param summarizer  callback that takes a chunk of messages and returns a summary string
     * @return true if compression happened
     */
    public boolean maybeCompress(List<ModelMessage> history,
                                 java.util.function.Function<List<ModelMessage>, String> summarizer) {
        int nonSystem = 0;
        for (ModelMessage m : history) {
            if (!"system".equals(m.getRole())) nonSystem++;
        }

        // Keep last (workingMemoryTurns) non-system messages; summarize the rest
        int toSummarize = nonSystem - workingMemoryTurns;
        if (toSummarize <= 0 || toSummarize < summaryBatchSize) {
            return false;
        }

        // Extract oldest batch for summarisation
        List<ModelMessage> batch = new ArrayList<>();
        int collected = 0;
        for (ModelMessage m : new ArrayList<>(history)) {
            if ("system".equals(m.getRole())) continue;
            if (collected < toSummarize) {
                batch.add(m);
                collected++;
            }
        }

        if (batch.isEmpty()) return false;

        try {
            String summary = summarizer.apply(batch);
            if (summary != null && !summary.isBlank()) {
                rollingSummaries.add(summary);
                totalTurnsSummarized += batch.size();
                // Remove the summarized messages from history, keeping system + recent
                history.removeAll(batch);
                logger.debug("Rolling summary added ({} messages -> {} chars)", batch.size(), summary.length());
                return true;
            }
        } catch (Exception e) {
            logger.warn("Rolling summarisation failed: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Add a key fact discovered during the session.
     */
    public void addKeyFact(String fact) {
        if (fact != null && !fact.isBlank()) {
            keyFacts.add(fact);
        }
    }

    /**
     * Build the L2 scratchpad text for injection into the system prompt.
     */
    public String buildScratchpadText() {
        StringBuilder sb = new StringBuilder();
        if (!rollingSummaries.isEmpty()) {
            sb.append("## Session Summary So Far\n\n");
            for (String s : rollingSummaries) {
                sb.append("- ").append(s.replace("\n", " ")).append("\n");
            }
            sb.append("\n");
        }
        if (!keyFacts.isEmpty()) {
            sb.append("## Key Facts from This Session\n\n");
            for (String f : keyFacts) {
                sb.append("- ").append(f.replace("\n", " ")).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public boolean isEmpty() {
        return rollingSummaries.isEmpty() && keyFacts.isEmpty();
    }

    public int getTotalTurnsSummarized() { return totalTurnsSummarized; }
    public List<String> getRollingSummaries() { return new ArrayList<>(rollingSummaries); }
    public List<String> getKeyFacts() { return new ArrayList<>(keyFacts); }
}
