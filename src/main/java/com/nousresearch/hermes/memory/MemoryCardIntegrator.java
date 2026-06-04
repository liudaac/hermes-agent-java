package com.nousresearch.hermes.memory;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Plugs {@link ContextCardBuilder} into an agent's conversation loop.
 *
 * <p>Call {@link #beforeTurn(List, String)} at the start of each user turn
 * (after the user message has been added but before {@code chatCompletion}).
 * It replaces the first {@code system}-role message with a fresh context
 * card built from the current user message as a query hint.
 *
 * <p>This avoids injecting the full MEMORY.md / USER.md every turn, saving
 * hundreds to thousands of tokens on each call while preserving the agent's
 * ability to reference previously stored information.
 */
public class MemoryCardIntegrator {

    private static final Logger logger = LoggerFactory.getLogger(MemoryCardIntegrator.class);

    private final ContextCardBuilder cardBuilder;

    public MemoryCardIntegrator(MemoryManager memoryManager) {
        this.cardBuilder = new ContextCardBuilder(memoryManager);
    }

    public MemoryCardIntegrator(MemoryManager memoryManager, int topK, boolean alwaysIncludeProfile) {
        this.cardBuilder = new ContextCardBuilder(memoryManager, topK, alwaysIncludeProfile);
    }

    /**
     * Replace the first system message in {@code conversationHistory} with a
     * memory context card scoped to {@code userMessage}.
     *
     * @param conversationHistory mutable list of conversation messages
     * @param userMessage         the current user input (used as query hint)
     * @return the size of the injected memory card in characters (0 if none)
     */
    public int beforeTurn(List<ModelMessage> conversationHistory, String userMessage) {
        if (conversationHistory == null || conversationHistory.isEmpty()) return 0;

        String memoryCard = cardBuilder.build(userMessage);
        if (memoryCard.isBlank()) return 0;

        // Inject as a distinct system message BEFORE the user message.
        // Find the last system message index, insert after it, or use index 0.
        int insertAt = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            if ("system".equals(conversationHistory.get(i).getRole())) {
                insertAt = i + 1; // always insert AFTER the last system msg
            }
        }

        // Remove any stale memory card injected on a previous turn
        removeStaleMemoryCard(conversationHistory);

        // Insert fresh card
        conversationHistory.add(insertAt, ModelMessage.system(memoryCard));
        return memoryCard.length();
    }

    /**
     * Strip the memory card that was injected last turn, identified by a
     * marker prefix we put on the content.
     */
    private void removeStaleMemoryCard(List<ModelMessage> history) {
        history.removeIf(msg ->
                "system".equals(msg.getRole())
                        && msg.getContent() != null
                        && msg.getContent().contains("Relevant Memory")
        );
    }

    public ContextCardBuilder getCardBuilder() { return cardBuilder; }
}
