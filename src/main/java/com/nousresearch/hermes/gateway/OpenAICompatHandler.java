package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.quota.QuotaExceededException;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * S1-3: OpenAI 兼容 API handler。
 *
 * <p>提供标准的 OpenAI API 格式，让客户可以直接用 openai SDK 接入。</p>
 *
 * <p>Endpoints：</p>
 * <ul>
 *   <li>{@code POST /v1/chat/completions} — 聊天补全</li>
 *   <li>{@code GET /v1/models} — 列出所有可用模型（别名）</li>
 * </ul>
 *
 * <p>安全规则：</p>
 * <ul>
 *   <li>api_key 绝不写日志（Redactor 处理）</li>
 *   <li>每 route 的 api_key/base_url 走 provider 侧凭证，绝不当调用方鉴权</li>
 * </ul>
 */
public class OpenAICompatHandler {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatHandler.class);

    private final HermesConfig config;
    private final TenantManager tenantManager;

    public OpenAICompatHandler(HermesConfig config, TenantManager tenantManager) {
        this.config = config;
        this.tenantManager = tenantManager;
    }

    /**
     * POST /v1/chat/completions
     *
     * <p>OpenAI 兼容格式：</p>
     * <pre>{@code
     * {
     *   "model": "gpt-4",           // 可以是别名
     *   "messages": [
     *     {"role": "user", "content": "Hello"}
     *   ],
     *   "stream": false,            // 暂不支持 stream
     *   "temperature": 0.7          // 可选
     * }
     * }</pre>
     */
    public void handleChatCompletions(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String modelAlias = body.getString("model");
            JSONArray messages = body.getJSONArray("messages");
            boolean stream = body.getBooleanValue("stream", false);

            if (modelAlias == null || modelAlias.isBlank()) {
                ctx.status(400).json(errorResponse("'model' field is required"));
                return;
            }
            if (messages == null || messages.isEmpty()) {
                ctx.status(400).json(errorResponse("'messages' field is required and cannot be empty"));
                return;
            }

            // S1-3: model_routes 别名解析
            // 优先级：session /model > model_routes > global
            // 这里没有 session 概念（OpenAI 兼容 API 是无状态的），所以只用 model_routes > global
            ModelRoute route = config.resolveModelRoute(modelAlias, null);
            String actualModel = route != null ? route.getModel() : modelAlias;

            // 安全日志：只记录别名和实际模型，绝不记录 api_key
            logger.info("OpenAI compat request: alias={}, model={}", modelAlias, actualModel);

            // 提取最后一条 user message
            String lastUserMessage = extractLastUserMessage(messages);
            if (lastUserMessage == null) {
                ctx.status(400).json(errorResponse("No user message found"));
                return;
            }

            // 解析租户（从 header 或 body，默认 default）
            String tenantId = ctx.header("X-Tenant-Id");
            if (tenantId == null) tenantId = body.getString("tenant_id");
            if (tenantId == null) tenantId = "default";

            TenantContext tenant = tenantManager.getOrCreateTenant(
                tenantId, createDefaultProvisioningRequest());

            if (!tenant.isActive()) {
                ctx.status(403).json(errorResponse("Tenant is not active"));
                return;
            }

            // 配额检查
            try {
                tenant.getQuotaManager().checkDailyRequestQuota();
            } catch (QuotaExceededException e) {
                ctx.status(429).json(errorResponse("Quota exceeded: " + e.getMessage()));
                return;
            }

            // 创建/获取 agent
            String sessionId = UUID.randomUUID().toString();
            var agent = tenant.getOrCreateAgent(sessionId, config);

            // 应用 route 的 provider/baseUrl 如果有
            if (route != null && route.getBaseUrl() != null) {
                agent.setModelParams(Map.of("base_url", route.getBaseUrl()));
            }

            // 处理消息
            long startTime = System.currentTimeMillis();
            String response = agent.processMessage(lastUserMessage);
            long duration = System.currentTimeMillis() - startTime;
            tenant.updateActivity();

            // 构建 OpenAI 兼容响应
            String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

            JSONObject result = new JSONObject();
            result.put("id", completionId);
            result.put("object", "chat.completion");
            result.put("created", System.currentTimeMillis() / 1000);
            result.put("model", actualModel);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", response != null ? response : "");
            choice.put("message", message);
            choice.put("finish_reason", "stop");
            choices.add(choice);
            result.put("choices", choices);

            // usage（粗略估算）
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", estimateTokens(lastUserMessage));
            usage.put("completion_tokens", estimateTokens(response));
            usage.put("total_tokens",
                estimateTokens(lastUserMessage) + estimateTokens(response));
            result.put("usage", usage);

            ctx.status(200).json(result);

        } catch (Exception e) {
            logger.error("OpenAI compat error: {}", redactLog(e.getMessage()), e);
            ctx.status(500).json(errorResponse("Internal error"));
        }
    }

    /**
     * GET /v1/models
     *
     * <p>列出所有可用模型别名 + global default。</p>
     */
    public void handleListModels(Context ctx) {
        JSONObject result = new JSONObject();
        result.put("object", "list");

        JSONArray data = new JSONArray();

        // model_routes 别名
        for (ModelRoute route : config.getModelRoutes()) {
            JSONObject model = new JSONObject();
            model.put("id", route.getAlias());
            model.put("object", "model");
            model.put("created", System.currentTimeMillis() / 1000);
            model.put("owned_by", route.getProvider() != null ? route.getProvider() : "hermes");
            data.add(model);
        }

        // global default 也列出来
        JSONObject defaultModel = new JSONObject();
        defaultModel.put("id", config.getCurrentModel());
        defaultModel.put("object", "model");
        defaultModel.put("created", System.currentTimeMillis() / 1000);
        defaultModel.put("owned_by", config.getProvider());
        data.add(defaultModel);

        result.put("data", data);
        ctx.status(200).json(result);
    }

    // ============ 辅助方法 ============

    private String extractLastUserMessage(JSONArray messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JSONObject msg = messages.getJSONObject(i);
            if ("user".equals(msg.getString("role"))) {
                return msg.getString("content");
            }
        }
        return null;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 粗略估算：1 token ≈ 4 字符（英文）/ 2 字符（中文）
        return Math.max(1, text.length() / 4);
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", Map.of("message", message, "type", "invalid_request_error"));
        return error;
    }

    /**
     * S1-3: Redactor — 移除日志中的 api_key 等敏感信息。
     */
    static String redactLog(String s) {
        if (s == null) return null;
        return s
            .replaceAll("(?i)(api[_-]?key)\\s*[=:]\\s*\\S+", "$1=***REDACTED***")
            .replaceAll("(?i)(authorization)\\s*[=:]\\s*\\S+(?:\\s+\\S+)*", "$1=***REDACTED***")
            .replaceAll("(?i)(auth[_-]?token)\\s*[=:]\\s*\\S+", "$1=***REDACTED***")
            .replaceAll("(?i)sk-[a-zA-Z0-9_-]{20,}", "sk-***REDACTED***")
            .replaceAll("(?i)(bearer)\\s+[a-zA-Z0-9._-]+", "$1 ***REDACTED***");
    }

    private TenantProvisioningRequest createDefaultProvisioningRequest() {
        return new TenantProvisioningRequest("default", "openai-compat");
    }
}
