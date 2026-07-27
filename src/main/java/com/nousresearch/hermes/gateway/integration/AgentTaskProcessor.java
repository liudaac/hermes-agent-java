package com.nousresearch.hermes.gateway.integration;

import com.nousresearch.hermes.agent.ModelChain;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Also dispatches webhook events on completion/failure.</p>
 */
public class AgentTaskProcessor implements AsyncTaskQueue.TaskProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskProcessor.class);

    private final TenantManager tenantManager;
    private final WebhookDispatcher webhookDispatcher;
    private final HermesConfig globalConfig;

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
        }
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
