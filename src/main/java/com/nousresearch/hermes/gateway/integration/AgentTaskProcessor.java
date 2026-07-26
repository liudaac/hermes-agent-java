package com.nousresearch.hermes.gateway.integration;

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
 *   <li>Calls agent.processMessage(input)</li>
 *   <li>Returns the reply as the task result</li>
 * </ol>
 *
 * <p>Also dispatches webhook events on completion/failure.</p>
 */
public class AgentTaskProcessor implements AsyncTaskQueue.TaskProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskProcessor.class);

    private final TenantManager tenantManager;
    private final WebhookDispatcher webhookDispatcher;

    public AgentTaskProcessor(TenantManager tenantManager,
                              WebhookDispatcher webhookDispatcher) {
        this.tenantManager = tenantManager;
        this.webhookDispatcher = webhookDispatcher;
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

            String reply = agent.processMessage(task.input());

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
}
