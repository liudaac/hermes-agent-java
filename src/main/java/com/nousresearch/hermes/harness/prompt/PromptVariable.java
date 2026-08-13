package com.nousresearch.hermes.harness.prompt;

/**
 * One prompt variable, resolved at assembly time and interpolated into
 * {@code {{name}}} references in sections and contexts.
 *
 * <p>Variable names must match {@code [a-z][a-z0-9_]*}.
 * A provider may return {@code null} to indicate "no value for this
 * assembly", but rendering a section that references that value
 * will then fail.</p>
 */
public interface PromptVariable {

    /** The {@code [a-z][a-z0-9_]*} reference name. */
    String name();

    /** Resolved value, or {@code null} when unavailable for this assembly. */
    String resolve(PromptAssembleContext ctx);
}
