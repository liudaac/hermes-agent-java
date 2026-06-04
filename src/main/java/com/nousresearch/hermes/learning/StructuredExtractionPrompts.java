package com.nousresearch.hermes.learning;

/**
 * Prompt templates that ask the LLM to produce {@link ExtractedKnowledge} JSON.
 *
 * <p>Kept in its own file so the templates are easy to iterate on without
 * touching {@link KnowledgeExtractor} logic.
 */
final class StructuredExtractionPrompts {

    private StructuredExtractionPrompts() {}

    /** System prompt: defines the JSON contract. */
    static final String SYSTEM = """
        You are a knowledge-extraction assistant for a long-running AI agent.
        Given a transcript, you produce a STRICT JSON object describing what is
        worth remembering. You MUST output ONLY JSON (no prose, no fences), with
        this exact schema:

        {
          "facts":          [{"content": "...", "confidence": 0.0-1.0, "tags": ["..."]}],
          "user_profile":   [{"content": "...", "confidence": 0.0-1.0, "tags": ["..."]}],
          "skill_hints":    [{"name": "snake_case_id", "description": "...",
                              "content": "markdown body or empty",
                              "tags": ["..."], "confidence": 0.0-1.0}],
          "anti_patterns":  [{"content": "...", "confidence": 0.0-1.0, "tags": ["..."]}]
        }

        Rules:
        - facts          = stable, project / decision / config information.
        - user_profile   = preferences, identity, calling style, timezone.
        - skill_hints    = reusable workflows (only when >=3 tool calls succeeded).
        - anti_patterns  = mistakes or failure modes to avoid next time.
        - Use confidence honestly: 0.9+ only for facts the user explicitly stated.
        - Omit any list entirely if you have nothing for it (empty array is fine).
        - Do NOT invent details. If unsure, lower the confidence.
        - Keep each "content" under 240 characters; one fact per item.
        """;

    /** Build the user prompt that wraps the transcript. */
    static String userPrompt(String formattedTranscript, int maxItemsPerBucket) {
        return """
            Conversation transcript:
            ---
            %s
            ---

            Extract knowledge as JSON per the schema. Cap each list at %d items.
            Return JSON only.
            """.formatted(formattedTranscript, maxItemsPerBucket);
    }

    /** Pre-compression prompt: focus on facts that would be lost on summarisation. */
    static String preCompressUserPrompt(String formattedTranscript) {
        return """
            The following slice of conversation is about to be discarded by
            context compression. Extract ONLY the facts and user_profile items
            that would otherwise be lost. Use the same JSON schema; you may
            leave skill_hints and anti_patterns empty.

            Conversation slice:
            ---
            %s
            ---

            Return JSON only.
            """.formatted(formattedTranscript);
    }
}
