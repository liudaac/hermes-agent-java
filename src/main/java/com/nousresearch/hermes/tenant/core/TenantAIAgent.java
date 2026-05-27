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
     * 处理消息 - 委托给 TenantAwareAIAgent
     */
    public String processMessage(String message) {
        if (interrupted) {
            return "Agent has been interrupted";
        }

        try {
            // 委托给 TenantAwareAIAgent 处理
            String response = delegate.processMessage(message);
            return response;
        } catch (Exception e) {
            logger.error("Error processing message in TenantAIAgent: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
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
        logger.debug("Ended session: {} (completed: {})", sessionId, completed);
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
}
