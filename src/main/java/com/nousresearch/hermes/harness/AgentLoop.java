package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.plugin.hook.HookType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * The agent loop: PRE -> LOOP -> POST.
 *
 * <p>Three static entry points that together replace the old
 * {@code TenantAwareAIAgent.doProcessMessage()} body.</p>
 *
 * <p>All agent access goes through {@link AgentContext} - AgentLoop
 * never touches {@link TenantAwareAIAgent} directly. This makes the
 * loop testable, mockable, and reusable for SubAgent.</p>
 */
public class AgentLoop {
    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private AgentLoop() {} // static utility

    // ==================== PRE-LOOP ====================

    public static boolean preLoop(AgentContext ctx, String message) {
        ctx.userTurnCountIncrement();

        var hookEngine = ctx.hookEngine();
        if (hookEngine != null && ctx.getUserTurnCount() == 1) {
            Map<String, Object> sessionCtx = new HashMap<>();
            sessionCtx.put("session_id", ctx.sessionId());
            sessionCtx.put("tenant_id", ctx.tenantId());
            sessionCtx.put("message", message);
            hookEngine.invoke(HookType.ON_SESSION_START, sessionCtx);
        }

        if (ctx.history().isEmpty()) {
            ctx.history().add(ModelMessage.system(ctx.buildSystemPrompt()));
        }

        boolean shouldReviewMemory = false;
        if (ctx.memoryNudgeInterval() > 0) {
            ctx.incrementTurnsSinceMemory();
            if (ctx.getTurnsSinceMemory() >= ctx.memoryNudgeInterval()) {
                shouldReviewMemory = true;
                ctx.resetTurnsSinceMemory();
            }
        }

        ctx.history().add(ModelMessage.user(message));

        if (ctx.cognitiveTraceCollector() != null) {
            ctx.cognitiveTraceCollector().observe(ctx.getUserTurnCount(), message);
        }

        if (ctx.smartMemoryCardEnabled() && ctx.memoryCardIntegrator() != null) {
            int cardSize = ctx.memoryCardIntegrator().beforeTurn(ctx.history(), message);
            if (ctx.evalMetrics() != null) {
                ctx.evalMetrics().recordMemoryQuery(cardSize > 0 ? 1 : 0, cardSize);
            }
        }
        ctx.autoSaveSession();

        return shouldReviewMemory;
    }

    // ==================== LOOP ====================

    /**
     * Non-streaming loop.
     */
    public static String run(AgentContext ctx, EventEmitter emitter) {
        return run(ctx, emitter, null);
    }

    /**
     * Core think->act->observe loop. Optionally streams LLM deltas
     * through {@code onDelta}.
     *
     * @param ctx      agent context
     * @param emitter  event emitter (null = no structured events)
     * @param onDelta  streaming callback (null = non-streaming)
     * @return response text
     */
    public static String run(AgentContext ctx, EventEmitter emitter,
                              java.util.function.Consumer<String> onDelta) {
        var history = ctx.history();
        var budget = ctx.budget();

        StringBuilder responseBuilder = new StringBuilder();
        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_START, Map.of("budget", budget.getRemaining() + budget.getUsed()));
        }

        while (budget.hasRemaining() && !ctx.isInterrupted()) {
            if (!budget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            if (ctx.governancePolicy() != null && ctx.governancePolicy().isPaused()) {
                responseBuilder.append("\n⚠️ Agent paused: ").append(ctx.governancePolicy().getPauseReason());
                break;
            }

            try {
                if (emitter != null) {
                    emitter.emit(AgentEvent.PRE_LLM, Map.of("iteration", budget.getUsed()));
                }

                // Hook: PRE_LLM_CALL
                if (ctx.hookEngine() != null) {
                    Map<String, Object> preCtx = new HashMap<>();
                    preCtx.put("messages", new ArrayList<>(history));
                    preCtx.put("session_id", ctx.sessionId());
                    preCtx.put("tenant_id", ctx.tenantId());
                    ctx.hookEngine().invoke(HookType.PRE_LLM_CALL, preCtx);
                }

                enforceContextBudget(history, emitter);

                // LLM call (streaming or non-streaming)
                ChatCompletionResponse response;
                if (onDelta != null) {
                    response = ctx.modelClient().chatCompletion(
                        history, ctx.buildToolDefinitions(), true, ctx.modelParams(), onDelta);
                } else {
                    response = ctx.modelClient().chatCompletion(
                        history, ctx.buildToolDefinitions(), false, ctx.modelParams());
                }

                // Hook: POST_LLM_CALL
                if (ctx.hookEngine() != null) {
                    Map<String, Object> postCtx = new HashMap<>();
                    postCtx.put("message", response.getMessage());
                    postCtx.put("finish_reason", response.getFinishReason());
                    postCtx.put("session_id", ctx.sessionId());
                    postCtx.put("tenant_id", ctx.tenantId());
                    ctx.hookEngine().invoke(HookType.POST_LLM_CALL, postCtx);
                }

                if (emitter != null) {
                    emitter.emit(AgentEvent.POST_LLM, Map.of(
                        "finishReason", response.getFinishReason() != null ? response.getFinishReason() : "stop",
                        "hasToolCalls", response.hasToolCalls()));
                }

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    responseBuilder.append("\n[No response from model]");
                    break;
                }

                ctx.recordModelUsage(response);
                history.add(assistantMessage);
                ctx.autoSaveSession();

                if (response.hasToolCalls()) {
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(assistantMessage.getContent());
                    }

                    for (ToolCall tc : assistantMessage.getToolCalls()) {
                        if (emitter != null) {
                            emitter.emit(AgentEvent.PRE_TOOL, Map.of(
                                "callId", tc.getId(),
                                "tool", tc.getFunction().getName(),
                                "args", tc.getFunction().getArguments()));
                        }

                        long toolStart = System.currentTimeMillis();
                        String result = ctx.executeToolCall(tc);
                        long duration = System.currentTimeMillis() - toolStart;

                        if (emitter != null) {
                            emitter.emit(AgentEvent.POST_TOOL, Map.of(
                                "callId", tc.getId(),
                                "ok", !result.contains("\"error\""),
                                "durationMs", duration));
                        }

                        history.add(ModelMessage.tool(result, tc.getId()));
                    }
                    ctx.incrementItersSinceSkill();
                } else {
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(content);
                    }
                    break;
                }

                if ("stop".equals(response.getFinishReason())) break;

            } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
                throw ex;
            } catch (Exception e) {
                logger.error("Error in loop: {}", e.getMessage(), e);
                if (emitter != null) {
                    emitter.emit(AgentEvent.ERROR, Map.of("message", e.getMessage()));
                }
                responseBuilder.append("\n[Error: ").append(e.getMessage()).append("]");
                break;
            }
        }

        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_END, Map.of(
                "iterations", budget.getUsed(),
                "messages", history.size()));
        }
        return responseBuilder.toString();
    }

    // ==================== POST-LOOP ====================

    public static String postLoop(AgentContext ctx, String loopResponse,
                                   boolean shouldReviewMemory) {
        ctx.persistSession();

        boolean shouldReviewSkills = false;
        if (ctx.skillNudgeInterval() > 0 &&
            ctx.getItersSinceSkill() >= ctx.skillNudgeInterval()) {
            shouldReviewSkills = true;
            ctx.resetItersSinceSkill();
        }

        String finalResponse = loopResponse;

        // Confidence calibration
        if (ctx.confidenceCalibrator() != null && !finalResponse.isEmpty()) {
            int toolsUsed = ctx.countToolsUsedThisTurn();
            boolean hasSearch = ctx.history().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("Search results"));
            var calibrated = ctx.confidenceCalibrator().calibrate(finalResponse, toolsUsed, hasSearch);
            if (ctx.evalMetrics() != null) {
                ctx.evalMetrics().recordCalibration(calibrated.action());
            }
            if (calibrated.action() != com.nousresearch.hermes.agent.ConfidenceCalibrator.Action.DIRECT) {
                finalResponse = calibrated.adjustedText();
            }
        }

        // Background review
        if (!finalResponse.isEmpty() && !ctx.isInterrupted() &&
            (shouldReviewMemory || shouldReviewSkills)) {
            ctx.spawnBackgroundReview(
                new ArrayList<>(ctx.history()),
                shouldReviewMemory, shouldReviewSkills);
        }

        // Plugin hook: transform_llm_output
        var hookEngine = ctx.hookEngine();
        if (hookEngine != null && !finalResponse.isEmpty()) {
            Map<String, Object> outCtx = new HashMap<>();
            outCtx.put("text", finalResponse);
            outCtx.put("session_id", ctx.sessionId());
            outCtx.put("tenant_id", ctx.tenantId());
            List<Object> transforms = hookEngine.invoke(HookType.TRANSFORM_LLM_OUTPUT, outCtx);
            for (Object t : transforms) {
                if (t instanceof String s && !s.isEmpty()) {
                    finalResponse = s;
                }
            }
        }

        return finalResponse;
    }

    // ==================== Helpers ====================

    private static void enforceContextBudget(List<ModelMessage> history, EventEmitter emitter) {
        int totalChars = 0;
        for (ModelMessage m : history) {
            if (m.getContent() != null) totalChars += m.getContent().length();
        }
        int limit = 400_000;
        if (totalChars <= limit) return;

        int target = (int) (limit * 0.75);
        int dropped = 0;
        int i = 1;
        while (totalChars > target && history.size() > 6 && i < history.size() - 4) {
            ModelMessage m = history.remove(i);
            totalChars -= m.getContent() == null ? 0 : m.getContent().length();
            dropped++;
        }
        if (dropped > 0 && emitter != null) {
            emitter.emit(AgentEvent.CONTEXT_COMPRESSED, Map.of("dropped", dropped));
        }
    }
}
