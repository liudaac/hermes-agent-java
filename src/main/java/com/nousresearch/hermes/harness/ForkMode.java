package com.nousresearch.hermes.harness;

/**
 * Strategy for forking parent context into a sub-agent.
 */
public enum ForkMode {

    /** Deep copy the parent's full conversation history.
     *  Use when the sub-agent needs complete context to do its job. */
    FULL,

    /** Run ContextManager compression on a copy of the parent's history,
     *  then hand the compressed version to the sub-agent.
     *  Use when the conversation is long and only key context matters. */
    COMPRESSED,

    /** No history fork - just pass a text context string (current behavior).
     *  Use when the sub-agent's task is self-contained. */
    CLEAN
}
