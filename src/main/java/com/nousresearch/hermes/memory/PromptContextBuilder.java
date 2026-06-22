package com.nousresearch.hermes.memory;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds and injects the smart memory context card into an agent's conversation.
 *
 * <p>Instead of dumping the entire MEMORY.md / USER.md every turn (which both
 * blows tokens and pollutes prefix-cache as memories grow), this builder only
 * injects the top-K entries that look relevant to the current user message.
 * The remainder are hinted via a one-liner so the agent can fetch them via
 * the {@code memory} tool when needed.</p>
 *
 * <p>Usage: call {@link #beforeTurn(List, String)} at the start of each user turn
 * (after the user message has been added but before {@code chatCompletion}).</p>
 */
public class PromptContextBuilder {

    private static final Logger logger = LoggerFactory.getLogger(PromptContextBuilder.class);

    private final MemoryManager memoryManager;
    private final MemoryRetriever retriever;
    private final int topK;
    private final boolean alwaysIncludeProfile;

    public PromptContextBuilder(MemoryManager memoryManager) {
        this(memoryManager, 6, true);
    }

    public PromptContextBuilder(MemoryManager memoryManager, int topK, boolean alwaysIncludeProfile) {
        this.memoryManager = memoryManager;
        this.retriever = new MemoryRetriever(memoryManager);
        this.topK = topK;
        this.alwaysIncludeProfile = alwaysIncludeProfile;
    }

    /**
     * Replace stale memory cards and inject a fresh context card scoped to the user message.
     *
     * @param conversationHistory mutable list of conversation messages
     * @param userMessage         the current user input (used as query hint)
     * @return the size of the injected context card in characters (0 if none)
     */
    public int beforeTurn(List<ModelMessage> conversationHistory, String userMessage) {
        if (conversationHistory == null || conversationHistory.isEmpty()) return 0;

        String contextCard = build(userMessage);
        if (contextCard.isBlank()) return 0;

        int insertAt = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            if ("system".equals(conversationHistory.get(i).getRole())) {
                insertAt = i + 1;
            }
        }

        removeStaleCards(conversationHistory);
        conversationHistory.add(insertAt, ModelMessage.system(contextCard));
        return contextCard.length();
    }

    /**
     * Produce the memory section for the system prompt.
     *
     * @param queryHint the current user message (or other relevance signal).
     *                  If null/blank, falls back to the full snapshot to be safe.
     * @return formatted markdown block, possibly empty
     */
    public String build(String queryHint) {
        if (queryHint == null || queryHint.isBlank()) {
            return memoryManager.getSystemPromptSnapshot();
        }

        int total = retriever.totalEntries();
        if (total == 0) return "";

        if (total <= topK + 2) {
            return buildLiveSnapshot();
        }

        List<MemoryRetriever.RetrievedEntry> top = retriever.retrieve(queryHint, topK);
        StringBuilder sb = new StringBuilder();

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

        if (!top.isEmpty()) {
            sb.append("## Relevant Memory (top ").append(top.size()).append(")\n\n");
            for (MemoryRetriever.RetrievedEntry r : top) {
                if (alwaysIncludeProfile && "user".equals(r.category)) continue;
                sb.append("- ").append(r.content.replace("\n", "\n  ")).append("\n");
            }
            sb.append("\n");
        }

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

    private void removeStaleCards(List<ModelMessage> history) {
        history.removeIf(msg ->
                "system".equals(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("Relevant Memory")
        );
    }

    public MemoryRetriever getRetriever() {
        return retriever;
    }
}
