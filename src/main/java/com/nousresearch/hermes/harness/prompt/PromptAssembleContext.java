package com.nousresearch.hermes.harness.prompt;

/**
 * Per-assembly context passed to {@link PromptSection#render},
 * {@link PromptContext#render}, and {@link PromptVariable#resolve}.
 *
 * <p>Carries the agent identity and an optional abort signal so
 * providers can short-circuit expensive operations.</p>
 */
public record PromptAssembleContext(
    String tenantId,
    String sessionId,
    String agentId,
    String agentRole
) {
    /** Create a minimal context from agent identity fields. */
    public static PromptAssembleContext of(String tenantId, String sessionId, String agentId) {
        return new PromptAssembleContext(tenantId, sessionId, agentId, null);
    }
}
