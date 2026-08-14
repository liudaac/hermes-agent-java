package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.harness.loop.*;
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

    /** Max retries for transient LLM errors within a single iteration. */
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

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
     * <p>Error handling follows a 4-category strategy:</p>
     * <ul>
     *   <li><b>TRANSIENT</b> (timeout, 429, 5xx) - retry LLM call with
     *       exponential backoff (max {@value #MAX_TRANSIENT_RETRIES} retries)</li>
     *   <li><b>LLM_RECOVERABLE</b> (bad tool args, parse error) - feed error
     *       back as a tool message so the model can self-correct</li>
     *   <li><b>USER_FIXABLE</b> (permission denied, file not found) -
     *       structured error with recovery suggestion, break</li>
     *   <li><b>FATAL</b> - log, emit ERROR event, break</li>
     * </ul>
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

        // P1-2: Claim any pending next-turn messages
        var turnEntries = ctx.inbox().claimNextTurn();
        for (var entry : turnEntries) {
            history.add(entry.message());
        }

        while (budget.hasRemaining() && !ctx.isInterrupted()) {
            if (!budget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            // P1-1: Pre-step interceptor chain
            PreStepContext preStepCtx = new PreStepContext(
                ctx.getUserTurnCount(), budget.getUsed(),
                new ArrayList<>(history), ctx.sessionId(), ctx.tenantId());
            PreStepDecision preDecision = ctx.preStepChain().intercept(preStepCtx);
            if (preDecision.kind() == PreStepDecision.Kind.REJECT) {
                if (preDecision.reason() != null) {
                    responseBuilder.append("\n⚠️ ").append(preDecision.reason());
                }
                break;
            }
            if (preDecision.kind() == PreStepDecision.Kind.REWRITE && preDecision.messages() != null) {
                history.clear();
                history.addAll(preDecision.messages());
            }

            // P1-2: Claim any pending next-step messages
            var stepEntries = ctx.inbox().claimNextStep();
            for (var entry : stepEntries) {
                history.add(entry.message());
            }

            // P1-2: Claim any pending inject messages (silent, don't affect flow)
            var injectEntries = ctx.inbox().claimInject();
            for (var entry : injectEntries) {
                history.add(entry.message());
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

                enforceContextBudget(ctx, history, emitter);

                // LLM call with transient retry
                ChatCompletionResponse response = callModelWithRetry(ctx, history, onDelta, emitter);
                if (response == null) {
                    // LLM_RECOVERABLE: error fed back as tool message, continue loop
                    continue;
                }
                if (!response.isSuccess() && response.getError() != null) {
                    // Non-retryable LLM error (USER_FIXABLE or FATAL)
                    var cat = LoopErrorClassifier.classify(response.getError());
                    if (emitter != null) {
                        emitter.emit(AgentEvent.ERROR, Map.of(
                            "message", response.getError(),
                            "category", cat.name(),
                            "retryable", false));
                    }
                    responseBuilder.append("\n[Error (").append(cat).append("): ")
                        .append(response.getError()).append("]");
                    break;
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

                    // Emit PRE_TOOL for all calls
                    for (ToolCall tc : assistantMessage.getToolCalls()) {
                        if (emitter != null) {
                            emitter.emit(AgentEvent.PRE_TOOL, Map.of(
                                "callId", tc.getId(),
                                "tool", tc.getFunction().getName(),
                                "args", tc.getFunction().getArguments()));
                        }
                    }

                    // P1-3: Execute tools via scheduler (parallel where possible)
                    List<ToolCall> toolCalls = assistantMessage.getToolCalls();
                    List<ToolCallResult> toolResults = ctx.toolCallScheduler().execute(toolCalls, ctx);

                    for (ToolCallResult tcr : toolResults) {
                        if (emitter != null) {
                            emitter.emit(AgentEvent.POST_TOOL, Map.of(
                                "callId", tcr.callId(),
                                "ok", tcr.success(),
                                "durationMs", tcr.durationMs()));
                        }
                        history.add(ModelMessage.tool(tcr.content(), tcr.callId()));
                    }
                    ctx.incrementItersSinceSkill();
                } else {
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(content);
                    }
                    // P1-2: Check for steer messages before breaking
                    if (ctx.inbox().hasNextStep()) {
                        continue;
                    }
                    break;
                }

                if ("stop".equals(response.getFinishReason())) {
                    // P1-2: If there are next-step messages (steer), continue processing
                    if (ctx.inbox().hasNextStep()) {
                        continue;
                    }
                    break;
                }

            } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
                throw ex;
            } catch (Exception e) {
                var cat = LoopErrorClassifier.classify(e);
                logger.error("Error in loop ({}): {}", cat, e.getMessage(), e);

                if (emitter != null) {
                    emitter.emit(AgentEvent.ERROR, Map.of(
                        "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        "category", cat.name(),
                        "retryable", cat.isRetryable()));
                }

                if (cat == ErrorCategory.USER_FIXABLE) {
                    responseBuilder.append("\n⚠️ [").append(cat).append("] ")
                        .append(e.getMessage())
                        .append("\n💡 This may require user action (check permissions, resources, or configuration).");
                } else {
                    responseBuilder.append("\n[Error (").append(cat).append("): ")
                        .append(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        .append("]");
                }
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

    // ==================== LLM Call with Retry ====================

    /**
     * Call the model, retrying transient errors with exponential backoff.
     *
     * @return the response, or null if an LLM_RECOVERABLE error was fed back
     *         as a tool message (caller should continue the loop)
     */
    private static ChatCompletionResponse callModelWithRetry(
            AgentContext ctx, List<ModelMessage> history,
            java.util.function.Consumer<String> onDelta,
            EventEmitter emitter) {

        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            ChatCompletionResponse response;
            if (onDelta != null) {
                response = ctx.modelClient().chatCompletion(
                    history, ctx.buildToolDefinitions(), true, ctx.modelParams(), onDelta);
            } else {
                response = ctx.modelClient().chatCompletion(
                    history, ctx.buildToolDefinitions(), false, ctx.modelParams());
            }

            // Success
            if (response.isSuccess() || response.getError() == null) {
                return response;
            }

            var cat = LoopErrorClassifier.classify(response.getError());

            // LLM_RECOVERABLE: feed error back as tool message for self-correction
            if (cat == ErrorCategory.LLM_RECOVERABLE) {
                logger.warn("LLM recoverable error (attempt {}): {}", attempt + 1, response.getError());
                // Add assistant placeholder + tool error feedback so model can self-correct
                history.add(ModelMessage.assistant(
                    "I encountered an error: " + response.getError()));
                history.add(ModelMessage.tool(
                    "{\"error\": \"" + escapeJson(response.getError()) + "\", "
                    + "\"hint\": \"Please correct the issue and try again.\"}",
                    "error_feedback_" + attempt));
                if (emitter != null) {
                    emitter.emit(AgentEvent.ERROR, Map.of(
                        "message", response.getError(),
                        "category", "LLM_RECOVERABLE",
                        "retryable", true,
                        "fedBackToModel", true));
                }
                return null; // signal: continue loop
            }

            // TRANSIENT: retry with backoff
            if (cat == ErrorCategory.TRANSIENT && attempt < MAX_TRANSIENT_RETRIES) {
                long delay = Math.min(BASE_BACKOFF_MS * (1L << attempt), MAX_BACKOFF_MS);
                logger.warn("Transient LLM error (attempt {}/{}), retrying in {}ms: {}",
                    attempt + 1, MAX_TRANSIENT_RETRIES + 1, delay, response.getError());
                if (emitter != null) {
                    emitter.emit(AgentEvent.ERROR, Map.of(
                        "message", response.getError(),
                        "category", "TRANSIENT",
                        "retry", attempt + 1,
                        "retryable", true));
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return response; // return error, let caller handle
                }
                continue;
            }

            // P0-3: Context overflow recovery - compact and retry once
            String error = response.getError();
            if (error != null && (error.toLowerCase().contains("context")
                    || error.toLowerCase().contains("token")
                    || error.toLowerCase().contains("too long")
                    || error.contains("maximum"))) {
                logger.warn("Context overflow detected, attempting compaction: {}", error);
                var compactResult = compactionEngine.compactIfNeeded(
                    history,
                    com.nousresearch.hermes.harness.compaction.CompactionTrigger.CONTEXT_OVERFLOW,
                    ctx.modelClient()
                );
                if (compactResult.success() && attempt < MAX_TRANSIENT_RETRIES) {
                    if (emitter != null) {
                        emitter.emit(AgentEvent.CONTEXT_COMPRESSED, Map.of(
                            "strategy", "overflow-compaction",
                            "messagesCompacted", compactResult.messagesCompacted()
                        ));
                    }
                    continue; // retry with compacted history
                }
            }

            // USER_FIXABLE or FATAL (or TRANSIENT exhausted): return error response
            return response;
        }

        // Should not reach here, but just in case
        return ChatCompletionResponse.error("LLM call failed after " + (MAX_TRANSIENT_RETRIES + 1) + " attempts");
    }

    // ==================== Tool Exception Handler ====================

    /**
     * Handle an exception from tool execution.
     *
     * @return tool result string to add to history, or null if the error
     *         is not recoverable within the loop (caller should break)
     */
    private static String handleToolException(ToolCall tc, Exception ex,
                                               List<ModelMessage> history,
                                               EventEmitter emitter) {
        var cat = LoopErrorClassifier.classify(ex);

        if (cat == ErrorCategory.LLM_RECOVERABLE) {
            // Feed error back as tool result so model can self-correct
            logger.warn("Tool '{}' failed with LLM-recoverable error: {}",
                tc.getFunction().getName(), ex.getMessage());
            if (emitter != null) {
                emitter.emit(AgentEvent.ERROR, Map.of(
                    "message", ex.getMessage(),
                    "category", "LLM_RECOVERABLE",
                    "tool", tc.getFunction().getName(),
                    "fedBackToModel", true));
            }
            return "{\"error\": \"" + escapeJson(ex.getMessage()) + "\", "
                + "\"hint\": \"Check the tool call parameters and try again.\"}";

        } else if (cat == ErrorCategory.TRANSIENT) {
            // Transient tool error - feed back and let model decide whether to retry
            logger.warn("Tool '{}' failed with transient error: {}",
                tc.getFunction().getName(), ex.getMessage());
            if (emitter != null) {
                emitter.emit(AgentEvent.ERROR, Map.of(
                    "message", ex.getMessage(),
                    "category", "TRANSIENT",
                    "tool", tc.getFunction().getName(),
                    "retryable", true));
            }
            return "{\"error\": \"transient: " + escapeJson(ex.getMessage()) + "\", "
                + "\"hint\": \"This may be a temporary issue. You may retry the tool call.\"}";

        } else {
            // USER_FIXABLE or FATAL: not recoverable in-loop
            return null;
        }
    }

    // ==================== Helpers ====================

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

    /** Compaction engine for context window management (P0-3). */
    private static final com.nousresearch.hermes.harness.compaction.CompactionEngine compactionEngine
        = new com.nousresearch.hermes.harness.compaction.BasicCompactionEngine();

    private static void enforceContextBudget(AgentContext ctx, List<ModelMessage> history,
                                              EventEmitter emitter) {
        // P0-3: Try compaction engine first (LLM-based summarization)
        try {
            var result = compactionEngine.compactIfNeeded(
                history,
                com.nousresearch.hermes.harness.compaction.CompactionTrigger.PRESSURE,
                ctx.modelClient()
            );
            if (result.success()) {
                logger.info("Compaction: {} messages compacted, ~{} tokens saved",
                    result.messagesCompacted(), result.tokensSaved());
                if (emitter != null) {
                    emitter.emit(AgentEvent.CONTEXT_COMPRESSED, java.util.Map.of(
                        "strategy", "compaction",
                        "messagesCompacted", result.messagesCompacted(),
                        "tokensSaved", result.tokensSaved()
                    ));
                }
                return;
            }
        } catch (Exception e) {
            logger.debug("Compaction engine failed, falling back to ContextManager: {}", e.getMessage());
        }

        // Fallback: legacy ContextManager (shielding -> summary -> truncate)
        var cm = new ContextManager();
        var stats = cm.enforce(history, emitter);
        if (stats.anythingDone()) {
            logger.debug("Context managed (fallback): shielded={}, summarized={}, truncated={}, tokens~{}",
                stats.toolResultsShielded(), stats.messagesSummarized(),
                stats.messagesTruncated(), stats.finalTokenEstimate());
        }
    }
}
