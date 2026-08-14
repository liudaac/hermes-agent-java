package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.collaboration.GovernancePolicy;
import com.nousresearch.hermes.collaboration.OrgHealthChecker;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.monitoring.AgentEvalMetrics;
import com.nousresearch.hermes.skills.BackgroundReviewPrompts;
import com.nousresearch.hermes.trajectory.TrajectoryCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Extracted from TenantAwareAIAgent: handles session lifecycle including
 * end-of-session learning/reflection, persistence, debug info, and
 * background review (memory + skill extraction).
 */
public class SessionLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(SessionLifecycle.class);

    private final TenantAwareAIAgent agent;

    // Static prompt strings (moved from TenantAwareAIAgent)
    static final String MEMORY_REVIEW_PROMPT =
        "Review the conversation above and consider saving to memory if appropriate.\n\n" +
        "Focus on:\n" +
        "1. Has the user revealed things about themselves - their persona, desires, " +
        "preferences, or personal details worth remembering?\n" +
        "2. Has the user expressed expectations about how you should behave, their work " +
        "style, or ways they want you to operate?\n\n" +
        "If something stands out, save it using the memory tool. " +
        "If nothing is worth saving, just say 'Nothing to save.' and stop.";

    static final String SKILL_REVIEW_PROMPT =
        "Review the conversation above and consider saving or updating a skill if appropriate.\n\n" +
        "Focus on: was a non-trivial approach used to complete a task that required trial " +
        "and error, or changing course due to experiential findings along the way, or did " +
        "the user expect or desire a different method or outcome?\n\n" +
        "If a relevant skill already exists, update it with what you learned. " +
        "Otherwise, create a new skill if the approach is reusable.\n" +
        "If nothing is worth saving, just say 'Nothing to save.' and stop.";

    static final String COMBINED_REVIEW_PROMPT =
        "Review the conversation above and consider two things:\n\n" +
        "**Memory**: Has the user revealed things about themselves - their persona, " +
        "desires, preferences, or personal details? Has the user expressed expectations " +
        "about how you should behave, their work style, or ways they want you to operate? " +
        "If so, save using the memory tool.\n\n" +
        "**Skills**: Was a non-trivial approach used to complete a task that required trial " +
        "and error, or changing course due to experiential findings along the way, or did " +
        "the user expect or desire a different method or outcome? If a relevant skill " +
        "already exists, update it. Otherwise, create a new one if the approach is reusable.\n\n" +
        "Only act if there's something genuinely worth saving. " +
        "If nothing stands out, just say 'Nothing to save.' and stop.";

    public SessionLifecycle(TenantAwareAIAgent agent) {
        this.agent = agent;
    }

    // ==================== Session End ====================

    public void endSession(boolean completed) {
        String sessionId = agent.getSessionId();
        logger.info("Ending session: {} (completed={})", sessionId, completed);

        // 保存轨迹
        TrajectoryCollector trajectoryCollector = agent.getTrajectoryCollector();
        if (trajectoryCollector != null) {
            trajectoryCollector.endSession(sessionId, completed);
        }

        List<ModelMessage> conversationHistory = agent.getConversationHistory();
        com.nousresearch.hermes.learning.LearningPipeline learningPipeline = agent.getLearningPipeline();
        AgentEvalMetrics evalMetrics = agent.getEvalMetrics();
        ReflectionEngine reflectionEngine = agent.getReflectionEngine();

        // 提取知识
        if (learningPipeline != null && completed) {
            try {
                var result = learningPipeline.onSessionEnd(sessionId, conversationHistory);
                logger.info("Extracted {} insights from session", result.getInsights().size());
                if (evalMetrics != null) evalMetrics.recordKnowledgeExtraction(result.getInsights().size());
            } catch (Exception e) {
                logger.error("Knowledge extraction failed: {}", e.getMessage());
            }
        }

        // 反思 / 自我批评
        if (reflectionEngine != null && completed) {
            try {
                var rr = reflectionEngine.reflect(sessionId, conversationHistory, completed);
                if ("ok".equals(rr.status) && !rr.lessons.isEmpty()) {
                    logger.info("Reflection: score={}, lessons={}, anti_patterns={}",
                        rr.taskScore, rr.lessons.size(), rr.antiPatterns.size());
                if (evalMetrics != null) evalMetrics.recordReflection(rr.taskScore);
                }
                agent.setLastTaskScore(rr.taskScore);
            } catch (Exception e) {
                logger.error("Reflection failed: {}", e.getMessage());
            }
        }

        // 主动学习：识别弱话题并补充知识
        if (learningPipeline != null && completed) {
            try {
                int stored = learningPipeline.runCuriosityScan();
                if (stored > 0) {
                    logger.info("Curiosity engine stored {} new findings", stored);
                if (evalMetrics != null) evalMetrics.recordCuriosityRun(stored);
                }
            } catch (Exception e) {
                logger.warn("Curiosity engine failed: {}", e.getMessage());
            }
        }

        // ======== AI原生组织：治理状态更新 ========
        GovernancePolicy governancePolicy = agent.getGovernancePolicy();
        var agentRole = agent.getAgentRole();
        if (completed) {
            governancePolicy.recordSuccess();
            if (agentRole != null) {
                agentRole.updateMetric("sessions_completed",
                    ((Number) agentRole.getMetrics().getOrDefault("sessions_completed", 0)).intValue() + 1);
            }
        }
        agentRole.updateMetric("last_active", System.currentTimeMillis());
        agentRole.updateMetric("tokens_used_today", governancePolicy.getTokensUsed());

        // AI原生组织：组织健康检查
        OrgHealthChecker orgHealthChecker = agent.getOrgHealthChecker();
        if (orgHealthChecker != null) {
            orgHealthChecker.updateHealth(
                agent.getAgentId(), agent.getLastTaskScore(),
                governancePolicy.getConsecutiveFailures(),
                governancePolicy.getTokensUsed(),
                governancePolicy.getDailyTokenBudget()
            );
        }

        // 持久化会话
        persistSession();

        // 租户上下文持久化
        var tenantContext = agent.getTenantContext();
        if (tenantContext != null) {
            tenantContext.getSessionManager().persistAll();
        }

        if (trajectoryCollector != null) {
            trajectoryCollector.shutdown();
        }

        // Flush cognitive traces
        var cognitiveTraceCollector = agent.getCognitiveTraceCollector();
        if (cognitiveTraceCollector != null) {
            cognitiveTraceCollector.close();
        }

        if (evalMetrics != null) {
            evalMetrics.logSnapshot();
        }
    }

    // ==================== Session Persistence ====================

    public void autoSaveSession() {
        persistSession();
    }

    public void persistSession() {
        String sessionId = agent.getSessionId();
        try {
            var hermesHome = com.nousresearch.hermes.config.Constants.getHermesHome();
            logger.debug("Persisting session {} to {}", sessionId, hermesHome);

            var sessionMgr = new com.nousresearch.hermes.gateway.SessionManager(hermesHome);
            var session = sessionMgr.getSession(sessionId);

            // Clear and rebuild messages to avoid duplicates
            session.messages.clear();
            for (ModelMessage msg : agent.getConversationHistory()) {
                if (msg.getRole() != null && msg.getContent() != null) {
                    session.addMessage(msg.getRole(), msg.getContent());
                }
            }

            // Set source info for dashboard display
            if (session.platform == null) {
                session.platform = "web";
            }
            session.lastActivity = System.currentTimeMillis();

            sessionMgr.saveSession(session);
            logger.info("Session persisted: {} ({} messages)", sessionId, session.messages.size());

        } catch (Exception e) {
            logger.error("Failed to save session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    // ==================== Debug Info ====================

    public Map<String, Object> getSessionDebugInfo() {
        String sessionId = agent.getSessionId();
        String tenantId = agent.getTenantId();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", sessionId);
        info.put("tenantId", tenantId);
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(sessionId);
            if (session != null) {
                var json = session.toJson();
                info.put("usage", Map.of(
                    "promptTokens", json.has("promptTokens") ? json.get("promptTokens").asLong() : 0L,
                    "completionTokens", json.has("completionTokens") ? json.get("completionTokens").asLong() : 0L,
                    "cachedPromptTokens", json.has("cachedPromptTokens") ? json.get("cachedPromptTokens").asLong() : 0L,
                    "reasoningTokens", json.has("reasoningTokens") ? json.get("reasoningTokens").asLong() : 0L,
                    "totalTokens", json.has("totalTokens") ? json.get("totalTokens").asLong() : 0L,
                    "lastModel", json.has("lastModel") ? json.get("lastModel").asText() : null
                ));
                if (json.has("toolCalls")) {
                    var tcs = json.get("toolCalls");
                    List<Map<String, Object>> toolList = new ArrayList<>();
                    for (var tc : tcs) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tool", tc.get("tool").asText());
                        m.put("ok", tc.get("ok").asBoolean());
                        m.put("durationMs", tc.get("durationMs").asLong());
                        m.put("timestamp", tc.get("timestamp").asLong());
                        toolList.add(m);
                    }
                    info.put("toolCalls", toolList);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get session debug info: {}", e.getMessage());
        }
        return info;
    }

    // ==================== Background Review ====================

    public void spawnBackgroundReview(List<ModelMessage> messages,
                                        boolean reviewMemory, boolean reviewSkills) {
        // 选择 prompt
        String prompt;
        if (reviewMemory && reviewSkills) {
            prompt = BackgroundReviewPrompts.COMBINED_REVIEW_PROMPT;
        } else if (reviewMemory) {
            prompt = BackgroundReviewPrompts.MEMORY_REVIEW_PROMPT;
        } else {
            prompt = BackgroundReviewPrompts.SKILL_REVIEW_PROMPT;
        }

        Thread.startVirtualThread(() -> {
            try {
                logger.debug("Starting background review: memory={}, skills={}", reviewMemory, reviewSkills);
                runBackgroundReviewLLM(messages, prompt);
            } catch (Exception e) {
                logger.error("Background review failed", e);
                // Fallback to heuristic review if LLM fork fails
                logger.debug("Falling back to heuristic review");
                if (reviewMemory) reviewAndSaveMemoryHeuristic(messages);
            }
        });
    }

    /**
     * Run the background review using an LLM fork (SubAgent).
     */
    private void runBackgroundReviewLLM(List<ModelMessage> messages, String prompt) {
        try {
            String reviewMessage = prompt + "\n\nYou can only call memory and skill "
                + "management tools. Other tools will be denied at runtime - do not "
                + "attempt them.";

            // Build a conversation digest for context (last 24 messages to bound cost)
            StringBuilder contextBuilder = new StringBuilder();
            int start = Math.max(0, messages.size() - 24);
            for (int i = start; i < messages.size(); i++) {
                ModelMessage msg = messages.get(i);
                String role = msg.getRole();
                String content = msg.getContent();
                if (content == null || content.isBlank()) continue;
                if ("tool".equals(role)) continue; // skip tool results for brevity
                contextBuilder.append(role).append(": ")
                    .append(content, 0, Math.min(content.length(), 500))
                    .append("\n");
            }

            // Use SubAgent for the forked review with memory+skill tool whitelist
            SubAgent reviewAgent = new SubAgent(reviewMessage, contextBuilder.toString(), agent.getConfig());
            reviewAgent.withToolWhitelist(java.util.Set.of(
                "memory", "skill_create", "skill_update", "skill_patch",
                "skill_write_file", "skill_remove_file", "skill_get",
                "skill_search", "skill_list"
            )).withSystemPrompt(
                "You are a background self-improvement reviewer. Review the "
                + "conversation and save valuable learnings to memory or skills. "
                + "Be concise. Only use memory and skill management tools."
            ).withMaxIterations(16);
            SubAgentResult result = reviewAgent.call();

            // Summarize actions for the user - enqueue for next-turn flush
            if (result != null && result.success) {
                boolean hasMemory = result.memoriesToSave != null && !result.memoriesToSave.isEmpty();
                boolean hasInsights = result.insights != null && !result.insights.isEmpty();
                if (hasMemory || hasInsights) {
                    StringBuilder summary = new StringBuilder();
                    if (hasMemory) {
                        summary.append(result.memoriesToSave.size()).append(" memory update(s)");
                    }
                    if (hasInsights) {
                        if (summary.length() > 0) summary.append(", ");
                        summary.append(result.insights.size()).append(" insight(s)");
                    }
                    String summaryStr = summary.toString();
                    logger.info("Self-improvement review: {}", summaryStr);
                    // Enqueue for next-turn flush - the chunkConsumer from the
                    // originating turn has already completed by the time the
                    // background review finishes.
                    agent.getPendingReviewSummaries().add("💾 Self-improvement review: " + summaryStr);
                } else {
                    logger.debug("Background review completed - nothing to save");
                }
            } else {
                logger.debug("Background review completed - nothing to save");
            }

        } catch (Exception e) {
            logger.error("LLM background review failed, falling back to heuristic: {}", e.getMessage(), e);
            reviewAndSaveMemoryHeuristic(messages);
        }
    }

    /**
     * Heuristic fallback for memory review (used when LLM fork is unavailable).
     */
    private void reviewAndSaveMemoryHeuristic(List<ModelMessage> messages) {
        try {
            List<String> userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ModelMessage::getContent)
                .filter(Objects::nonNull)
                .toList();

            if (userMessages.isEmpty()) {
                return;
            }

            String lastUserMessage = userMessages.get(userMessages.size() - 1);
            if (containsValuableInfo(lastUserMessage)) {
                String memory = extractMemorySummary(messages);
                MemoryManager memoryManager = agent.getMemoryManager();
                if (memory != null && !memory.isEmpty()) {
                    memoryManager.addUser(memory);
                    logger.debug("Saved memory from conversation (heuristic): {}",
                        memory.substring(0, Math.min(50, memory.length())));
                }
            }
        } catch (Exception e) {
            logger.error("Heuristic memory review failed", e);
        }
    }

    /**
     * 检查消息是否包含有价值的信息
     */
    private boolean containsValuableInfo(String message) {
        if (message == null || message.length() < 10) {
            return false;
        }
        String lower = message.toLowerCase();
        // 偏好指示器
        return lower.contains("prefer") || lower.contains("like") ||
               lower.contains("always") || lower.contains("usually") ||
               lower.contains("don't") || lower.contains("never") ||
               lower.contains("my name is") || lower.contains("i am") ||
               lower.contains("remember") || lower.contains("important");
    }

    /**
     * 从对话中提取记忆摘要
     */
    private String extractMemorySummary(List<ModelMessage> messages) {
        // 简化实现：提取最后几条消息作为上下文
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 3; i--) {
            ModelMessage msg = messages.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                if (sb.length() > 0) sb.insert(0, "; ");
                sb.insert(0, msg.getContent());
                count++;
            }
        }
        return sb.toString();
    }

    /**
     * 统计工具调用次数
     */
    private int countToolCalls(List<ModelMessage> messages) {
        return (int) messages.stream()
            .filter(m -> m.getToolCalls() != null && !m.getToolCalls().isEmpty())
            .count();
    }

    /**
     * 从对话中提取技能描述
     */
    private String extractSkillDescription(List<ModelMessage> messages) {
        // 简化实现：检查是否有明确的任务完成模式
        for (int i = messages.size() - 1; i >= 0; i--) {
            ModelMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent().toLowerCase();
                if (content.contains("done") || content.contains("completed") ||
                    content.contains("finished") || content.contains("here is")) {
                    return "Workflow completion pattern detected";
                }
            }
        }
        return null;
    }
}
