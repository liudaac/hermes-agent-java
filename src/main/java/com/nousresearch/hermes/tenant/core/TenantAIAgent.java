package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 租户 AI Agent - 包装 TenantAwareAIAgent 提供租户隔离的 Agent 功能
 */
public class TenantAIAgent {
    private static final Logger logger = LoggerFactory.getLogger(TenantAIAgent.class);

    private final TenantContext context;
    private final String sessionId;
    private final TenantAwareAIAgent delegate;
    private volatile boolean interrupted = false;

    public TenantAIAgent(TenantContext context, String sessionId, HermesConfig config) {
        this.context = context;
        this.sessionId = sessionId;
        // Bind the delegate to the already-resolved tenant context and session.
        // Do not call forTenant(...), which would create/load a separate TenantManager/TenantContext graph.
        this.delegate = TenantAwareAIAgent.forContext(context, sessionId, config);
        // Register this session for decay tracking
        context.registerSessionForDecay(sessionId);
        logger.debug("Created TenantAIAgent for tenant: {}, session: {}", context.getTenantId(), sessionId);
    }
    
    /**
     * 创建 Agent（使用默认配置）
     */
    public TenantAIAgent(TenantContext context, String sessionId) {
        this(context, sessionId, null);
    }

    /**
     * 中断 Agent 执行
     */
    public void interrupt() {
        this.interrupted = true;
        logger.debug("Interrupted TenantAIAgent for session: {}", sessionId);
    }

    /**
     * 等待 Agent 终止
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        // 等待一段时间
        try {
            Thread.sleep(unit.toMillis(timeout) / 10); // 简化的等待逻辑
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 处理消息 - 委托给 TenantAwareAIAgent，同时接入中心化 MemoryStore
     */
    public String processMessage(String message) {
        if (interrupted) {
            return "Agent has been interrupted";
        }

        try {
            // ── MemoryStore: 写入用户消息到短期记忆 ──
            var memoryStore = context.getCentralMemoryStore();
            if (memoryStore != null) {
                memoryStore.appendSessionMessage(
                    context.getTenantId(), sessionId, "user", message);
            }

            // ── MemoryStore: 检索长期记忆，注入上下文 ──
            if (memoryStore != null) {
                var longTermResults = memoryStore.searchMemories(
                    context.getTenantId(), null, message, 5);
                if (!longTermResults.isEmpty()) {
                    StringBuilder ctx = new StringBuilder();
                    ctx.append("\n\n[Relevant long-term memory]\n");
                    for (var entry : longTermResults) {
                        ctx.append("- ").append(entry.getContent()).append("\n");
                    }
                    message = message + ctx.toString();
                }
            }

            // 委托给 TenantAwareAIAgent 处理
            String response = delegate.processMessage(message);

            // ── MemoryStore: 写入 assistant 回复到短期记忆 ──
            if (memoryStore != null && response != null && !response.isBlank()) {
                memoryStore.appendSessionMessage(
                    context.getTenantId(), sessionId, "assistant", response);
            }

            return response;
        } catch (Exception e) {
            logger.error("Error processing message in TenantAIAgent: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 流式处理消息 - 委托给 TenantAwareAIAgent，同时接入中心化 MemoryStore
     */
    public void processMessageStream(String message, java.util.function.Consumer<String> chunkConsumer) {
        if (interrupted) {
            chunkConsumer.accept("Agent has been interrupted");
            return;
        }

        try {
            // ── MemoryStore: 写入用户消息到短期记忆 ──
            var memoryStore = context.getCentralMemoryStore();
            if (memoryStore != null) {
                memoryStore.appendSessionMessage(
                    context.getTenantId(), sessionId, "user", message);
            }

            // ── MemoryStore: 检索长期记忆 ──
            if (memoryStore != null) {
                var longTermResults = memoryStore.searchMemories(
                    context.getTenantId(), null, message, 5);
                if (!longTermResults.isEmpty()) {
                    StringBuilder ctx = new StringBuilder();
                    ctx.append("\n\n[Relevant long-term memory]\n");
                    for (var entry : longTermResults) {
                        ctx.append("- ").append(entry.getContent()).append("\n");
                    }
                    message = message + ctx.toString();
                }
            }

            // 包装 chunkConsumer 以捕获完整响应
            StringBuilder responseBuilder = new StringBuilder();
            java.util.function.Consumer<String> wrappingConsumer = chunk -> {
                responseBuilder.append(chunk);
                chunkConsumer.accept(chunk);
            };

            delegate.processMessageStream(message, wrappingConsumer);

            // ── MemoryStore: 写入 assistant 回复 ──
            if (memoryStore != null && responseBuilder.length() > 0) {
                memoryStore.appendSessionMessage(
                    context.getTenantId(), sessionId, "assistant", responseBuilder.toString());
            }
        } catch (Exception e) {
            logger.error("Error in stream processing in TenantAIAgent: {}", e.getMessage(), e);
            chunkConsumer.accept("Error: " + e.getMessage());
        }
    }

    /**
     * 设置/覆盖系统提示词
     */
    public void setSystemPrompt(String prompt) {
        delegate.setSystemPrompt(prompt);
    }

    public String getSystemPrompt() {
        return delegate.getSystemPrompt();
    }

    /**
     * 设置/覆盖模型参数（temperature, max_tokens 等）
     */
    public void setModelParams(java.util.Map<String, Object> params) {
        delegate.setModelParams(params);
    }

    public java.util.Map<String, Object> getModelParams() {
        return delegate.getModelParams();
    }

    /**
     * 获取会话调试信息（usage + tool calls）
     */
    public java.util.Map<String, Object> getSessionDebugInfo() {
        return delegate.getSessionDebugInfo();
    }

    /**
     * 结束会话
     */
    public void endSession(boolean completed) {
        delegate.endSession(completed);

        // AgentExperience: auto-learn from this session
        if (completed) {
            autoLearnExperience();
        }

        // Unregister from decay scheduler
        context.unregisterSessionForDecay(sessionId);

        logger.debug("Ended session: {} (completed: {})", sessionId, completed);
    }

    /**
     * Auto-learn experience from this session's conversation.
     *
     * <p>Extracts a brief experience note from the session's messages and
     * records it to the MemoryStore's agent experience log. This enables
     * the agent to accumulate learned patterns across sessions.</p>
     */
    private void autoLearnExperience() {
        var memoryStore = context.getCentralMemoryStore();
        if (memoryStore == null) return;

        try {
            // Get session stats to check if there's anything to learn
            var stats = memoryStore.getSessionStats(context.getTenantId(), sessionId);
            if (stats.fullCount() + stats.warmCount() + stats.coolCount() == 0) return;

            // Recall recent messages to build an experience summary
            var recalls = memoryStore.recallSession(
                context.getTenantId(), sessionId, "", 5,
                com.nousresearch.hermes.memory.store.DecayPolicy.standard());

            if (recalls.isEmpty()) return;

            // Build a concise experience note from the conversation
            StringBuilder exp = new StringBuilder();
            for (var r : recalls) {
                String content = r.content();
                // Truncate each message to keep the experience concise
                int maxLen = Math.min(content.length(), 150);
                exp.append("[").append(r.role()).append("] ")
                   .append(content, 0, maxLen)
                   .append(content.length() > 150 ? "..." : "")
                   .append(" ");
            }

            String experience = exp.toString().trim();
            if (!experience.isEmpty()) {
                memoryStore.addAgentExperience(
                    context.getTenantId(),
                    sessionId,  // use sessionId as agentId proxy
                    "session_summary",
                    experience
                );
                logger.debug("Auto-learned experience for tenant={}/session={}",
                    context.getTenantId(), sessionId);
            }
        } catch (Exception e) {
            logger.warn("Auto-learn experience failed for {}/{}: {}",
                context.getTenantId(), sessionId, e.getMessage());
        }
    }

    /**
     * 获取会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取租户上下文
     */
    public TenantContext getContext() {
        return context;
    }

    /**
     * Resume tool approval - delegates to TenantAwareAIAgent
     */
    public String resumeToolApproval(String toolCallId, boolean approved, String reason) {
        return delegate.resumeToolApproval(toolCallId, approved, reason);
    }

    /**
     * Set event emitter - delegates to TenantAwareAIAgent
     */
    public void setEventEmitter(com.nousresearch.hermes.harness.EventEmitter emitter) {
        delegate.setEventEmitter(emitter);
    }

    /**
     * Get the underlying TenantAwareAIAgent (for AgentLoop direct access).
     */
    public TenantAwareAIAgent getDelegate() {
        return delegate;
    }
}
