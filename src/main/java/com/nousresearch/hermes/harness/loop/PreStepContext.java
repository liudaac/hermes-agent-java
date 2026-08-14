package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ModelMessage;
import java.util.List;

/**
 * Context passed to PreStepInterceptor before each model step.
 */
public record PreStepContext(
    int turn,
    int step,
    List<ModelMessage> history,
    String sessionId,
    String tenantId
) {}
