package com.nousresearch.hermes.harness;

import java.util.Map;

/**
 * Structured event emitted during agent loop execution.
 *
 * <p>Replaces scattered {@code logger.info()} calls with a typed event
 * that external systems (SSE, hooks, metrics, audit) can subscribe to.</p>
 *
 * @param type       event type (see constants below)
 * @param tenantId   tenant scope
 * @param sessionId  session scope
 * @param agentId    agent that emitted this event
 * @param timestamp  epoch millis
 * @param data       type-specific payload
 */
public record AgentEvent(
    String type,
    String tenantId,
    String sessionId,
    String agentId,
    long timestamp,
    Map<String, Object> data
) {
    // ===== Event type constants =====

    public static final String LOOP_START          = "LOOP_START";
    public static final String PRE_LLM             = "PRE_LLM";
    public static final String LLM_DELTA           = "LLM_DELTA";
    public static final String POST_LLM            = "POST_LLM";
    public static final String PRE_TOOL            = "PRE_TOOL";
    public static final String POST_TOOL           = "POST_TOOL";
    public static final String APPROVAL_NEEDED     = "APPROVAL_NEEDED";
    public static final String CONTEXT_COMPRESSED  = "CONTEXT_COMPRESSED";
    public static final String LOOP_END            = "LOOP_END";
    public static final String ERROR               = "ERROR";

    // ===== Factory methods =====

    public static AgentEvent of(String type, String tenantId, String sessionId,
                                 String agentId, Map<String, Object> data) {
        return new AgentEvent(type, tenantId, sessionId, agentId,
            System.currentTimeMillis(), data != null ? data : Map.of());
    }

    public static AgentEvent loopStart(String tid, String sid, String aid, int budget) {
        return of(LOOP_START, tid, sid, aid, Map.of("budget", budget));
    }

    public static AgentEvent preLlm(String tid, String sid, String aid, int iter) {
        return of(PRE_LLM, tid, sid, aid, Map.of("iteration", iter));
    }

    public static AgentEvent llmDelta(String tid, String sid, String aid, String content) {
        return of(LLM_DELTA, tid, sid, aid, Map.of("content", content));
    }

    public static AgentEvent postLlm(String tid, String sid, String aid,
                                      String finishReason, long totalTokens, String model) {
        return of(POST_LLM, tid, sid, aid, Map.of(
            "finishReason", finishReason,
            "totalTokens", totalTokens,
            "model", model != null ? model : "unknown"
        ));
    }

    public static AgentEvent preTool(String tid, String sid, String aid,
                                      String callId, String tool, Map<String, Object> args) {
        return of(PRE_TOOL, tid, sid, aid, Map.of(
            "callId", callId,
            "tool", tool,
            "args", args
        ));
    }

    public static AgentEvent postTool(String tid, String sid, String aid,
                                       String callId, boolean ok, long durationMs) {
        return of(POST_TOOL, tid, sid, aid, Map.of(
            "callId", callId,
            "ok", ok,
            "durationMs", durationMs
        ));
    }

    public static AgentEvent approvalNeeded(String tid, String sid, String aid,
                                             String callId, String tool, String risk) {
        return of(APPROVAL_NEEDED, tid, sid, aid, Map.of(
            "callId", callId,
            "tool", tool,
            "risk", risk
        ));
    }

    public static AgentEvent contextCompressed(String tid, String sid, String aid, int dropped) {
        return of(CONTEXT_COMPRESSED, tid, sid, aid, Map.of("dropped", dropped));
    }

    public static AgentEvent loopEnd(String tid, String sid, String aid,
                                      int iterations, long totalTokens) {
        return of(LOOP_END, tid, sid, aid, Map.of(
            "iterations", iterations,
            "totalTokens", totalTokens
        ));
    }

    public static AgentEvent error(String tid, String sid, String aid, String message) {
        return of(ERROR, tid, sid, aid, Map.of("message", message));
    }
}
