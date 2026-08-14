package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ModelMessage;

/**
 * A single entry in the agent inbox.
 */
public record InboxEntry(
    ModelMessage message,
    InboxTarget target,
    long timestamp
) {
    public InboxEntry(ModelMessage message, InboxTarget target) {
        this(message, target, System.currentTimeMillis());
    }
}
