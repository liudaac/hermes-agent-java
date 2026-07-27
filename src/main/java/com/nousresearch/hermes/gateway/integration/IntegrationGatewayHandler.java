package com.nousresearch.hermes.gateway.integration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.quota.TenantQuotaManager;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * D2: Integration Gateway API handler.
 *
 * <p>Provides REST endpoints for business systems to interact with Hermes agents.
 * All endpoints require API Key authentication (Bearer ak_xxx).</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /api/v1/agents/{agentId}/messages - send message to agent</li>
 *   <li>GET  /api/v1/agents - list available agents</li>
 *   <li>GET  /api/v1/agents/{agentId}/sessions - list sessions</li>
 *   <li>POST /api/v1/sessions/{sessionId}/messages - append to session</li>
 *   <li>GET  /api/v1/sessions/{sessionId}/history - get session history</li>
 *   <li>POST /api/v1/tasks - submit async task</li>
 *   <li>GET  /api/v1/tasks/{taskId} - get task status</li>
 *   <li>POST /api/v1/tasks/{taskId}/cancel - cancel task</li>
 *   <li>GET  /api/v1/tenants/{tenantId}/usage - query usage</li>
 *   <li>GET  /api/v1/tenants/{tenantId}/billing - query billing</li>
 *   <li>POST /api/v1/webhooks - register webhook</li>
 *   <li>GET  /api/v1/health - health check</li>
 * </ul>
 */
public class IntegrationGatewayHandler {

    private static final Logger logger = LoggerFactory.getLogger(IntegrationGatewayHandler.class);

    private final TenantManager tenantManager;
    private final AsyncTaskQueue taskQueue;
    private final WebhookDispatcher webhookDispatcher;
    private com.nousresearch.hermes.config.HermesConfig globalConfig;
    private AgentTaskProcessor taskProcessor;

    public IntegrationGatewayHandler(TenantManager tenantManager,
                                     AsyncTaskQueue taskQueue,
                                     WebhookDispatcher webhookDispatcher) {
        this.tenantManager = tenantManager;
        this.taskQueue = taskQueue;
        this.webhookDispatcher = webhookDispatcher;
    }

    public void setGlobalConfig(com.nousresearch.hermes.config.HermesConfig config) {
        this.globalConfig = config;
    }

    /**
     * Wire the AgentTaskProcessor for chain interruption support.
     */
    public void setTaskProcessor(AgentTaskProcessor processor) {
        this.taskProcessor = processor;
    }

    // ============ Messages ============

    public void sendMessage(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        String agentId = ctx.pathParam("agentId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String message = body.getString("message");
        String workspaceId = body.getString("workspaceId");

        if (message == null || message.isBlank()) {
            ctx.status(400).json(Map.of("error", "message is required"));
            return;
        }
        if (!system.canWrite()) {
            ctx.status(403).json(Map.of("error", "Insufficient scope (write required)"));
            return;
        }

        try {
            String tenantId = system.tenantId();
            if (workspaceId == null) workspaceId = system.workspaceId() != null
                ? system.workspaceId() : tenantId;

            TenantContext tenant = tenantManager.getOrCreateTenant(tenantId,
                new TenantProvisioningRequest(tenantId, "system"));
            var agent = tenant.getOrCreateAgent(agentId, null);

            long start = System.currentTimeMillis();

            // Chain mode: [chain] prefix or tenant config chain_mode
            String reply;
            boolean useChain = shouldUseChain(message, tenant.getConfig());

            if (useChain) {
                logger.info("Sync message using chain mode for agent={}", agentId);
                com.nousresearch.hermes.agent.ModelChain chain =
                    com.nousresearch.hermes.agent.ModelChain.builder().buildDefault()
                    .withContext(tenantId, agentId, agentId);
                var tools = agent.getDelegate().buildToolDefinitions();
                var chainResult = chain.execute(tenant.getConfig(), globalConfig, message, tools);
                reply = chainResult.output();

                JSONObject result = new JSONObject();
                result.put("agentId", agentId);
                result.put("reply", reply);
                result.put("durationMs", System.currentTimeMillis() - start);
                result.put("workspaceId", workspaceId);
                result.put("chainMode", true);
                result.put("traceId", chainResult.traceId());
                if (chainResult.plan() != null) {
                    result.put("plan", com.alibaba.fastjson2.JSON.parseObject(
                        com.alibaba.fastjson2.JSON.toJSONString(chainResult.toApi())));
                }
                tenant.updateActivity();
                ctx.status(200).json(result);
                return;
            } else {
                reply = agent.processMessage(message);
            }

            long duration = System.currentTimeMillis() - start;
            tenant.updateActivity();

            JSONObject result = new JSONObject();
            result.put("agentId", agentId);
            result.put("reply", reply);
            result.put("durationMs", duration);
            result.put("workspaceId", workspaceId);
            result.put("chainMode", useChain);
            ctx.status(200).json(result);

            // D4: dispatch event
            webhookDispatcher.dispatch(tenantId, "message.completed",
                JSON.toJSONString(Map.of("agentId", agentId, "durationMs", duration)));

        } catch (Exception e) {
            logger.error("SendMessage failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Determine whether to use ModelChain for this message.
     * Same logic as AgentTaskProcessor.shouldUseChain().
     */
    private boolean shouldUseChain(String message, com.nousresearch.hermes.tenant.core.TenantConfig config) {
        if (message != null && message.strip().startsWith("[chain]")) {
            return true;
        }
        if (config != null) {
            Object chainMode = config.get("chain_mode");
            if (Boolean.TRUE.equals(chainMode) || "true".equals(String.valueOf(chainMode))) {
                return true;
            }
        }
        return false;
    }

    // ============ Agents ============

    public void listAgents(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        String tenantId = system.tenantId();
        TenantContext tenant = tenantManager.getOrLoadTenant(tenantId);

        JSONArray agents = new JSONArray();
        if (tenant != null) {
            for (var entry : tenant.listAgentRoles().entrySet()) {
                JSONObject a = new JSONObject();
                a.put("agentId", entry.getKey());
                a.put("role", entry.getValue().getRoleName());
                a.put("status", "available");
                agents.add(a);
            }
        }
        ctx.json(agents);
    }

    public void listSessions(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        String agentId = ctx.pathParam("agentId");
        // Sessions are managed by TenantAIAgent internally
        JSONArray sessions = new JSONArray();
        ctx.json(sessions);
    }

    // ============ Tasks ============

    public void submitTask(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        if (!system.canWrite()) {
            ctx.status(403).json(Map.of("error", "Insufficient scope (write required)"));
            return;
        }

        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String agentId = body.getString("agentId");
        String input = body.getString("input");
        int priority = body.getIntValue("priority", 0);
        int timeout = body.getIntValue("timeoutSeconds", 300);

        if (agentId == null || input == null) {
            ctx.status(400).json(Map.of("error", "agentId and input are required"));
            return;
        }

        String workspaceId = body.getString("workspaceId");
        if (workspaceId == null) workspaceId = system.workspaceId() != null
            ? system.workspaceId() : system.tenantId();

        AsyncTask task = taskQueue.submit(
            system.tenantId(), system.systemId(), workspaceId,
            agentId, input, priority, timeout);

        ctx.status(201).json(task.toApi());

        // D4: dispatch event
        webhookDispatcher.dispatch(system.tenantId(), "task.submitted",
            JSON.toJSONString(Map.of("taskId", task.taskId(), "agentId", agentId)));
    }

    public void getTask(Context ctx) {
        String taskId = ctx.pathParam("taskId");
        AsyncTask task = taskQueue.get(taskId);
        if (task == null) {
            ctx.status(404).json(Map.of("error", "Task not found"));
            return;
        }
        ctx.json(task.toApi());
    }

    public void cancelTask(Context ctx) {
        String taskId = ctx.pathParam("taskId");
        boolean cancelled = taskQueue.cancel(taskId);
        if (!cancelled) {
            ctx.status(409).json(Map.of("error", "Task cannot be cancelled (may already be terminal)"));
            return;
        }

        // Also interrupt running chain if active
        if (taskProcessor != null) {
            taskProcessor.interruptChain(taskId);
        }

        ctx.json(Map.of("taskId", taskId, "status", "CANCELLED"));

        webhookDispatcher.dispatch(taskQueue.get(taskId).tenantId(), "task.cancelled",
            JSON.toJSONString(Map.of("taskId", taskId)));
    }

    /**
     * POST /api/v1/tasks/{taskId}/interrupt - interrupt a running chain.
     *
     * <p>Unlike cancel (which marks the task as CANCELLED in DB),
     * interrupt specifically targets the running ModelChain, causing it
     * to stop between phases/steps. The task status remains RUNNING
     * until the worker thread catches the interruption and updates it.</p>
     */
    public void interruptTask(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        String taskId = ctx.pathParam("taskId");

        if (taskProcessor == null) {
            ctx.status(503).json(Map.of("error", "Task processor not available"));
            return;
        }

        boolean found = taskProcessor.interruptChain(taskId);
        if (found) {
            ctx.json(Map.of(
                "taskId", taskId,
                "status", "INTERRUPTING",
                "message", "Chain will stop at the next checkpoint"
            ));
            webhookDispatcher.dispatch(system.tenantId(), "task.interrupting",
                JSON.toJSONString(Map.of("taskId", taskId)));
        } else {
            ctx.status(404).json(Map.of(
                "error", "No active chain found for task " + taskId,
                "hint", "The task may not be in chain mode or may have already completed"
            ));
        }
    }

    /**
     * GET /api/v1/tasks/{taskId}/status - check if chain is running.
     */
    public void getTaskChainStatus(Context ctx) {
        String taskId = ctx.pathParam("taskId");
        if (taskProcessor == null) {
            ctx.status(503).json(Map.of("error", "Task processor not available"));
            return;
        }
        boolean running = taskProcessor.isChainRunning(taskId);
        ctx.json(Map.of(
            "taskId", taskId,
            "chainRunning", running
        ));
    }

    // = Usage & Billing ============

    public void getUsage(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        String tenantId = ctx.pathParam("tenantId");
        if (!system.tenantId().equals(tenantId) && !system.hasScope("admin")) {
            ctx.status(403).json(Map.of("error", "Access denied for tenant " + tenantId));
            return;
        }
        TenantContext tenant = tenantManager.getOrLoadTenant(tenantId);
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found"));
            return;
        }
        var quota = tenant.getQuotaManager();
        var usage = quota.getUsage();
        JSONObject result = new JSONObject();
        result.put("tenantId", tenantId);
        result.put("dailyRequestsUsed", usage.getDailyRequests());
        result.put("dailyTokensUsed", usage.getDailyTokens());
        var q = quota.getQuota();
        result.put("dailyRequestLimit", q.getMaxDailyRequests());
        result.put("dailyTokenLimit", q.getMaxDailyTokens());
        result.put("activeAgents", tenant.listAgentRoles().size());
        ctx.json(result);
    }

    public void getBilling(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        String tenantId = ctx.pathParam("tenantId");
        if (!system.tenantId().equals(tenantId) && !system.hasScope("admin")) {
            ctx.status(403).json(Map.of("error", "Access denied"));
            return;
        }
        // Billing data is in billing_record table
        // For now return summary from quota manager
        JSONObject billing = new JSONObject();
        billing.put("tenantId", tenantId);
        billing.put("message", "Use /api/admin/tenants/" + tenantId + "/billing for detailed query");
        ctx.json(billing);
    }

    // ============ Webhooks ============

    public void registerWebhook(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        if (!system.canWrite()) {
            ctx.status(403).json(Map.of("error", "Insufficient scope"));
            return;
        }
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String url = body.getString("url");
        JSONArray eventsArr = body.getJSONArray("events");
        String secret = body.getString("secret");

        if (url == null || eventsArr == null || secret == null) {
            ctx.status(400).json(Map.of("error", "url, events, and secret are required"));
            return;
        }

        List<String> events = eventsArr.toJavaList(String.class);
        webhookDispatcher.subscribe(system.tenantId(), system.systemId(), url, events, secret);
        ctx.status(201).json(Map.of("status", "subscribed", "url", url, "events", events));
    }

    public void listWebhooks(Context ctx) {
        BusinessSystem system = ctx.attribute("businessSystem");
        var subs = webhookDispatcher.listSubscriptions(system.tenantId());
        ctx.json(subs);
    }

    // ============ Health ============

    public void healthCheck(Context ctx) {
        JSONObject health = new JSONObject();
        health.put("status", "ok");
        health.put("timestamp", System.currentTimeMillis());
        health.put("version", "1.0");
        ctx.json(health);
    }
}
