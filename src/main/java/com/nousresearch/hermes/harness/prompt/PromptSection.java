package com.nousresearch.hermes.harness.prompt;

/**
 * One contributed section of the system prompt.
 *
 * <p>Sections are concatenated in ascending {@link #order()} order.
 * Convention:</p>
 * <ul>
 *   <li>{@code -100} — harness identity</li>
 *   <li>{@code 0} — deployment persona</li>
 *   <li>{@code 100-199} — tool guidance</li>
 *   <li>{@code 200+} — custom modules (evolution, team, etc.)</li>
 * </ul>
 *
 * <p>A scoped section with the same {@link #name()} shadows a global one.
 * The {@link #complete()} flag, when true, makes this section the sole
 * system prompt after assembly.</p>
 */
public interface PromptSection {

    /** Unique name — a duplicate registration throws. */
    String name();

    /** Sort order (ascending). See convention above. */
    int order();

    /** Static text or a provider evaluated at each assembly. */
    String render(PromptAssembleContext ctx);

    /** When true, this section becomes the complete system prompt. */
    default boolean complete() { return false; }
}
