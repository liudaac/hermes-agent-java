package com.nousresearch.hermes.gateway.integration;

import com.nousresearch.hermes.agent.ModelChain;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E1: Bridges AsyncTaskQueue to TenantAIAgent.
 *
 * <p>When a worker thread picks up a task, this processor:</p>
 * <ol>
 *   <li>Resolves the tenant context</li>
 *   <li>Gets or creates the agent</li>
 *   <li>If chain mode is enabled, routes through ModelChain (planner->executor->reviewer)</li>
 *   <li>Otherwise calls agent.processMessage(input) directly</li>
 *   <li>Returns the reply as the task result</li>
 * </ol>
 *
 * <p>Chain mode is triggered when tenant config has {@code chain_mode: true}
 * or when the task input starts with {@code [chain]}.</p>
 *
 * <p>Supports interruption: call {@link #interruptChain(String)} with the task ID
 * to stop a running chain between phases/steps.</p>
 *
 * <p>Also dispatches webhook events on completion/failure.</p>
 */
public class AgentTaskProcessor implements AsyncTaskQueue.TaskProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskProcessor.class);

    private final TenantManager tenantManager;
    private final WebhookDispatcher webhookDispatcher;
    private final HermesConfig globalConfig;

    /** Active chains keyed by taskId, for interruption support */
    private final Map<String, ModelChain> activeChains = new ConcurrentHashMap<>();

    public AgentTaskProcessor(TenantManager tenantManager,
                              WebhookDispatcher webhookDispatcher) {
        this(tenantManager, webhookDispatcher, null);
    }

    public AgentTaskProcessor(TenantManager tenantManager,
                              WebhookDispatcher webhookDispatcher,
                              HermesConfig globalConfig) {
        this.tenantManager = tenantManager;
        this.webhookDispatcher = webhookDispatcher;
        this.globalConfig = globalConfig;
    }

    @Override
    public String process(AsyncTask task) throws Exception {
        logger.info("Processing task {} for tenant={}/agent={}",
            task.taskId(), task.tenantId(), task.agentId());

        try {
            TenantContext tenant = tenantManager.getOrCreateTenant(
                task.tenantId(),
                new TenantProvisioningRequest(task.tenantId(), "system"));

            var agent = tenant.getOrCreateAgent(task.agentId());
            tenant.updateActivity();

            String reply;
            TenantConfig tenantConfig = tenant.getConfig();

            if (shouldUseChain(task, tenantConfig)) {
                // Chain mode: planner -> executor -> reviewer
                logger.info("Task {} using chain mode (planner->executor->reviewer)", task.taskId());
                ModelChain chain = ModelChain.builder().buildDefault()
                    .withContext(task.tenantId(), task.sessionId(), task.agentId());
                activeChains.put(task.taskId(), chain);

                var tools = agent.getDelegate().buildToolDefinitions();
                ModelChain.ChainResult chainResult = chain.execute(tenantConfig, globalConfig, task.input(), tools);
                reply = chainResult.output();

                // Dispatch chain-specific webhook with plan + traceId
                if (webhookDispatcher != null && chainResult.plan() != null) {
                    try {
                        webhookDispatcher.dispatch(task.tenantId(), "chain.plan",
                            com.alibaba.fastjson2.JSON.toJSONString(chainResult.toApi()));
                    } catch (Exception e) {
                        logger.debug("Chain plan webhook failed: {}", e.getMessage());
                    }
                }
            } else {
                // Direct mode: single model call
                reply = agent.processMessage(task.input());
            }

            // E2: Dispatch task.completed webhook
            if (webhookDispatcher != null) {
                try {
                    webhookDispatcher.dispatch(task.tenantId(), "task.completed",
                        com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of(
                            "taskId", task.taskId(),
                            "agentId", task.agentId(),
                            "status", "COMPLETED",
                            "result", reply != null ? reply.substring(0, Math.min(reply.length(), 500)) : ""
                        )));
                } catch (Exception e) {
                    logger.debug("Webhook dispatch failed for task {}: {}", task.taskId(), e.getMessage());
                }
            }

            return reply;

        } catch (ModelChain.ChainInterruptedException e) {
            logger.info("Task {} chain was interrupted", task.taskId());
            // Dispatch interruption webhook
            if (webhookDispatcher != null) {
                try {
                    webhookDispatcher.dispatch(task.tenantId(), "task.interrupted",
                        com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of(
                            "taskId", task.taskId(),
                            "agentId", task.agentId(),
                            "status", "INTERRUPTED",
                            "message", e.getMessage()
                        )));
                } catch (Exception ignored) {}
            }
            throw e;
        } catch (Exception e) {
            // E2: Dispatch task.failed webhook
            if (webhookDispatcher != null) {
                try {
                    webhookDispatcher.dispatch(task.tenantId(), "task.failed",
                        com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of(
                            "taskId", task.taskId(),
                            "agentId", task.agentId(),
                            "status", "FAILED",
                            "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                        )));
                } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            activeChains.remove(task.taskId());
        }
    }

    /**
     * Interrupt a running chain by task ID.
     * The current phase/step will complete, but no further ones will start.
     *
     * @param taskId the task ID to interrupt
     * @return true if a chain was found and interrupted, false if not found
     */
    public boolean interruptChain(String taskId) {
        ModelChain chain = activeChains.get(taskId);
        if (chain != null && !chain.isInterrupted()) {
            chain.interrupt();
            return true;
        }
        return false;
    }

    /**
     * Check if a chain is currently running for the given task ID.
     */
    public boolean isChainRunning(String taskId) {
        ModelChain chain = activeChains.get(taskId);
        return chain != null && !chain.isInterrupted();
    }

    /**
     * Determine whether to use ModelChain for this task.
     *
     * <p>Triggers:</p>
     * <ul>
     *   <li>Tenant config has {@code chain_mode: true}</li>
     *   <li>Task input starts with {@code [chain]} prefix</li>
     * </ul>
     */
    private boolean shouldUseChain(AsyncTask task, TenantConfig config) {
        // [chain] prefix in input forces chain mode
        if (task.input() != null && task.input().strip().startsWith("[chain]")) {
            return true;
        }

        // Tenant config flag
        if (config != null) {
            Object chainMode = config.get("chain_mode");
            if (Boolean.TRUE.equals(chainMode) || "true".equals(String.valueOf(chainMode))) {
                return true;
            }
        }

        return false;
    }
}
