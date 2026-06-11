package com.nousresearch.hermes.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds the "smart memory context card" that is injected into the system prompt.
 *
 * <p>Instead of dumping the entire MEMORY.md / USER.md every turn (which both
 * blows tokens and pollutes prefix-cache as memories grow), this card only
 * injects the top-K entries that look relevant to the current user message.
 * The remainder are hinted via a one-liner so the agent can fetch them via
 * the {@code memory} tool when needed.
 *
 * <p>The trade-off is: <em>occasionally</em> we'll miss a relevant memory the
 * lexical retriever didn't surface. To bound that risk we always include a
 * small "always-on" excerpt of high-priority entries (USER.md profile) at the
 * top, since those are tiny but high-signal.
 */
public class ContextCardBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ContextCardBuilder.class);

    private final MemoryRetriever retriever;
    private final MemoryManager memoryManager;
    private final int topK;
    private final boolean alwaysIncludeProfile;

    public ContextCardBuilder(MemoryManager memoryManager) {
        this(memoryManager, 6, true);
    }

    public ContextCardBuilder(MemoryManager memoryManager, int topK, boolean alwaysIncludeProfile) {
        this.memoryManager = memoryManager;
        this.retriever = new MemoryRetriever(memoryManager);
        this.topK = topK;
        this.alwaysIncludeProfile = alwaysIncludeProfile;
    }

    /**
     * Produce the memory section for the system prompt.
     *
     * @param queryHint the current user message (or other relevance signal).
     *                  If null/blank, falls back to the full snapshot to be safe.
     * @return formatted markdown block, possibly empty
     */
    public String build(String queryHint) {
        // No query → behave like the old MemoryManager.getSystemPromptSnapshot()
        if (queryHint == null || queryHint.isBlank()) {
            return memoryManager.getSystemPromptSnapshot();
        }

        int total = retriever.totalEntries();
        if (total == 0) return "";

        // Tiny corpus → dump current live entries; smart selection wastes tokens.
        // Do not use the frozen system prompt snapshot here: mid-session memory writes
        // intentionally do not mutate that snapshot, but the smart context card should
        // reflect live memory state for the current turn.
        if (total <= topK + 2) {
            return buildLiveSnapshot();
        }

        List<MemoryRetriever.RetrievedEntry> top = retriever.retrieve(queryHint, topK);

        StringBuilder sb = new StringBuilder();

        // 1. Always-on user profile (small, high-signal)
        if (alwaysIncludeProfile) {
            List<String> profileEntries = memoryManager.getByCategory("user", 5);
            if (!profileEntries.isEmpty()) {
                sb.append("## User Profile\n\n");
                for (String e : profileEntries) {
                    sb.append("- ").append(e.replace("\n", "\n  ")).append("\n");
                }
                sb.append("\n");
            }
        }

        // 2. Top-K retrieved memory entries
        if (!top.isEmpty()) {
            sb.append("## Relevant Memory (top ").append(top.size()).append(")\n\n");
            for (MemoryRetriever.RetrievedEntry r : top) {
                if (alwaysIncludeProfile && "user".equals(r.category)) continue; // avoid dup
                sb.append("- ").append(r.content.replace("\n", "\n  ")).append("\n");
            }
            sb.append("\n");
        }

        // 3. Hint about remaining entries so the agent knows it can fetch more
        int shown = top.size() + (alwaysIncludeProfile
                ? Math.min(memoryManager.getByCategory("user", 5).size(), 5) : 0);
        int remaining = Math.max(0, total - shown);
        if (remaining > 0) {
            sb.append("_+").append(remaining)
              .append(" more memories available — call the `memory` tool with action='search' to retrieve._\n");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Built context card: total={}, shown={}, remaining={}", total, shown, remaining);
        }

        return sb.toString();
    }

    private String buildLiveSnapshot() {
        StringBuilder sb = new StringBuilder();
        List<String> memEntries = memoryManager.getByCategory("memory", Integer.MAX_VALUE);
        if (!memEntries.isEmpty()) {
            sb.append("## Memory\n\n");
            for (String e : memEntries) {
                sb.append("- ").append(e.replace("\n", "\n  ")).append("\n");
            }
        }
        List<String> userEntries = memoryManager.getByCategory("user", Integer.MAX_VALUE);
        if (!userEntries.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("## User Profile\n\n");
            for (String e : userEntries) {
                sb.append("- ").append(e.replace("\n", "\n  ")).append("\n");
            }
        }
        return sb.toString();
    }

    public MemoryRetriever getRetriever() {
        return retriever;
    }
}
