package com.nousresearch.hermes.harness.prompt;

/**
 * Dynamic model context materialized as a durable user-role snapshot.
 *
 * <p>Unlike {@link PromptSection} (which is part of the system prompt),
 * contexts are rendered as separate user messages injected before the
 * current user message. They carry runtime facts (memory snapshots,
 * environment state, etc.) that change between steps.</p>
 */
public interface PromptContext {

    /** Unique name - a duplicate registration throws. */
    String name();

    /** Sort order (ascending). */
    int order();

    /** Static text or a provider evaluated at each assembly. Empty text contributes nothing. */
    String render(PromptAssembleContext ctx);
}
