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
 * {@code TenantAwareAIAgent.doProcessMessage()} body:</p>
 *
 * <ol>
 *   <li>{@link #preLoop} - session setup, memory nudge, cognitive trace</li>
 *   <li>{@link #run} - core think→act→observe cycle with structured events</li>
 *   <li>{@link #postLoop} - persist, confidence, background review, transform</li>
 * </ol>
 *
 * <p>Operates directly on {@link TenantAwareAIAgent}'s fields
 * (conversationHistory, iterationBudget) — no duplicate state.</p>
 */
public class AgentLoop {
    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private AgentLoop() {} // static utility

    // ==================== PRE-LOOP ====================

    /**
     * Pre-loop phase: session hook, system prompt, memory nudge,
     * cognitive trace, memory card, auto-save.
     *
     * @return true if memory review should run after this turn
     */
    public static boolean preLoop(TenantAwareAIAgent agent, String message) {
        agent.userTurnCountIncrement();

        var hookEngine = agent.getHookEngine();
        if (hookEngine != null && agent.getUserTurnCount() == 1) {
            Map<String, Object> sessionCtx = new HashMap<>();
            sessionCtx.put("session_id", agent.getSessionId());
            sessionCtx.put("tenant_id", agent.getTenantId());
            sessionCtx.put("message", message);
            hookEngine.invoke(HookType.ON_SESSION_START, sessionCtx);
        }

        if (agent.getConversationHistory().isEmpty()) {
            agent.getConversationHistory().add(ModelMessage.system(agent.buildSystemPromptForHarness()));
        }

        boolean shouldReviewMemory = false;
        if (agent.getMemoryNudgeInterval() > 0) {
            agent.incrementTurnsSinceMemory();
            if (agent.getTurnsSinceMemory() >= agent.getMemoryNudgeInterval()) {
                shouldReviewMemory = true;
                agent.resetTurnsSinceMemory();
            }
        }

        agent.getConversationHistory().add(ModelMessage.user(message));

        if (agent.getCognitiveTraceCollector() != null) {
            agent.getCognitiveTraceCollector().observe(agent.getUserTurnCount(), message);
        }

        if (agent.isSmartMemoryCardEnabled() && agent.getMemoryCardIntegrator() != null) {
            int cardSize = agent.getMemoryCardIntegrator().beforeTurn(
                agent.getConversationHistory(), message);
            if (agent.getEvalMetrics() != null) {
                agent.getEvalMetrics().recordMemoryQuery(cardSize > 0 ? 1 : 0, cardSize);
            }
        }
        agent.autoSaveSessionForHarness();

        return shouldReviewMemory;
    }

    // ==================== LOOP ====================

    /**
     * Core think→act→observe loop. Operates directly on the agent's
     * own conversationHistory and iterationBudget.
     *
     * @param agent   the owning agent
     * @param emitter event emitter (null = no structured events)
     * @return response text (empty if paused for approval)
     */
    public static String run(TenantAwareAIAgent agent, EventEmitter emitter) {
        var history = agent.getConversationHistory();
        var budget = agent.getIterationBudget();

        StringBuilder responseBuilder = new StringBuilder();
        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_START, Map.of("budget", budget.getRemaining() + budget.getUsed()));
        }

        while (budget.hasRemaining() && !agent.isInterrupted()) {
            if (!budget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            if (agent.getGovernancePolicy() != null && agent.getGovernancePolicy().isPaused()) {
                responseBuilder.append("\n⚠️ Agent paused: ").append(agent.getGovernancePolicy().getPauseReason());
                break;
            }

            try {
                if (emitter != null) {
                    emitter.emit(AgentEvent.PRE_LLM, Map.of("iteration", budget.getUsed()));
                }

                // Hook: PRE_LLM_CALL
                if (agent.getHookEngine() != null) {
                    Map<String, Object> preCtx = new HashMap<>();
                    preCtx.put("messages", new ArrayList<>(history));
                    preCtx.put("session_id", agent.getSessionId());
                    preCtx.put("tenant_id", agent.getTenantId());
                    agent.getHookEngine().invoke(HookType.PRE_LLM_CALL, preCtx);
                }

                enforceContextBudget(history, emitter);

                var response = agent.getModelClient().chatCompletion(
                    history, agent.buildToolDefinitionsForHarness(), false, agent.getModelParams());

                // Hook: POST_LLM_CALL
                if (agent.getHookEngine() != null) {
                    Map<String, Object> postCtx = new HashMap<>();
                    postCtx.put("message", response.getMessage());
                    postCtx.put("finish_reason", response.getFinishReason());
                    postCtx.put("session_id", agent.getSessionId());
                    postCtx.put("tenant_id", agent.getTenantId());
                    agent.getHookEngine().invoke(HookType.POST_LLM_CALL, postCtx);
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

                agent.recordModelUsageForHarness(response);
                history.add(assistantMessage);
                agent.autoSaveSessionForHarness();

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
                        String result = agent.executeToolCall(tc);  // throws ToolApprovalRequiredException
                        long duration = System.currentTimeMillis() - toolStart;

                        if (emitter != null) {
                            emitter.emit(AgentEvent.POST_TOOL, Map.of(
                                "callId", tc.getId(),
                                "ok", !result.contains("\"error\""),
                                "durationMs", duration));
                        }

                        history.add(ModelMessage.tool(result, tc.getId()));
                    }
                    agent.incrementItersSinceSkillForHarness();
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

    /**
     * Post-loop phase: persist, confidence calibration, background review,
     * transform_llm_output hook.
     */
    public static String postLoop(TenantAwareAIAgent agent, String loopResponse,
                                   boolean shouldReviewMemory) {
        agent.persistSessionForHarness();

        boolean shouldReviewSkills = false;
        if (agent.getSkillNudgeInterval() > 0 &&
            agent.getItersSinceSkill() >= agent.getSkillNudgeInterval()) {
            shouldReviewSkills = true;
            agent.resetItersSinceSkill();
        }

        String finalResponse = loopResponse;

        // Confidence calibration
        if (agent.getConfidenceCalibrator() != null && !finalResponse.isEmpty()) {
            int toolsUsed = agent.countToolsUsedThisTurnForHarness();
            boolean hasSearch = agent.getConversationHistory().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("Search results"));
            var calibrated = agent.getConfidenceCalibrator().calibrate(finalResponse, toolsUsed, hasSearch);
            if (agent.getEvalMetrics() != null) {
                agent.getEvalMetrics().recordCalibration(calibrated.action());
            }
            if (calibrated.action() != com.nousresearch.hermes.agent.ConfidenceCalibrator.Action.DIRECT) {
                finalResponse = calibrated.adjustedText();
            }
        }

        // Background review
        if (!finalResponse.isEmpty() && !agent.isInterrupted() &&
            (shouldReviewMemory || shouldReviewSkills)) {
            agent.spawnBackgroundReviewForHarness(
                new ArrayList<>(agent.getConversationHistory()),
                shouldReviewMemory, shouldReviewSkills);
        }

        // Plugin hook: transform_llm_output
        var hookEngine = agent.getHookEngine();
        if (hookEngine != null && !finalResponse.isEmpty()) {
            Map<String, Object> outCtx = new HashMap<>();
            outCtx.put("text", finalResponse);
            outCtx.put("session_id", agent.getSessionId());
            outCtx.put("tenant_id", agent.getTenantId());
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
