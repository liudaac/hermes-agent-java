package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ModelMessage;
import java.util.List;

/**
 * Decision returned by a PreStepInterceptor.
 */
public record PreStepDecision(
    Kind kind,
    List<ModelMessage> messages,  // REWRITE: replacement messages (null for ENTER/REJECT)
    String reason                  // REJECT: reason (null for ENTER/REWRITE)
) {
    public enum Kind { ENTER, REJECT, REWRITE }

    public static PreStepDecision enter() {
        return new PreStepDecision(Kind.ENTER, null, null);
    }

    public static PreStepDecision reject(String reason) {
        return new PreStepDecision(Kind.REJECT, null, reason);
    }

    public static PreStepDecision rewrite(List<ModelMessage> messages) {
        return new PreStepDecision(Kind.REWRITE, messages, null);
    }
}
