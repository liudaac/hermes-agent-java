package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.approval.ToolApprovalCoordinator;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatService - answer a user message by delegating to a real
 * {@link TenantAwareAIAgent} (not a bare LLM call).
 *
 * <p>Why this matters: the previous implementation called
 * {@code ModelClient.chatCompletion()} directly, which bypassed the entire
 * agent runtime - no tools, no SubAgent, no team, no reflection, no traces,
 * no tenant sandbox / quota / audit / metrics. By going through
 * {@code processMessage()} we get all of that for free, and dangerous
 * tool calls are routed through the real approval pipeline (see
 * {@link ApprovalBridge} + {@link ToolApprovalCoordinator}).</p>
 *
 * <p>Per-workspace agent pool: we lazily create one
 * {@link TenantAwareAIAgent} per workspaceId so the agent's conversation
 * history (and any paused {@code ToolApprovalCheckpoint}) persists across
 * HTTP requests. This is what allows the approval → resume cycle to
 * actually work end-to-end.</p>
 *
 * <p>MVP scope:</p>
 * <ul>
 *   <li>Happy path: {@code agent.processMessage(message)} returns the
 *       agent's full response (after all tool calls and final LLM turn).</li>
 *   <li>Tool approval path: when the agent throws
 *       {@link TenantAwareAIAgent.ToolApprovalRequiredException}, we
 *       capture the exception via the tool-approval callback, then
 *       call {@link ToolApprovalCoordinator#requestToolApproval} to
 *       create a real {@code BusinessApprovalRecord} (persisted, event
 *       bus, Portal inbox). The chat reply carries the
 *       {@link ChatReply#approval} field so the front-end can show the
 *       approval UI.</li>
 *   <li>Resume: when the user resolves the approval, the front-end calls
 *       {@code POST /api/jarvis/approval/{id}}. The handler routes through
 *       {@link ApprovalBridge#resolve}, which calls
 *       {@link ToolApprovalCoordinator#resumeToolApproval}, which calls
 *       {@code agent.resumeToolApproval(...)}, which returns the agent's
 *       final response. The approval endpoint returns that response as
 *       {@code reply} so the front-end can drop it into the chat.</li>
 * </ul>
 */
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_HISTORY_TURNS = 10;

    private static final String BASE_SYSTEM_PROMPT = """
你是 Hermes 的跨空间对话壳（代号 Jarvis）。你存在于 Portal / Ops / NOC 三个
产品之上，用户的浏览器右下角永远有你。

你的工作：
- 理解用户在问什么（业务前店 / 平台控制台 / 治理中心 / 跨空间）
- 必要时给出明确、可点击的跳转建议
- 简洁回答（中文优先），不要重复用户的问题
- 你拥有真实的工具调用能力。如果用户的请求需要执行动作（查询数据、
  调度工作流、修改配置等），请直接调用相应工具--危险操作会在执行前
  弹出审批请求。
- 如果你的工具调用被批准/驳回，继续完成任务并向用户汇报结果。
- 如果没有合适的工具，回答「这件事需要 XX 工具，我目前没有」。

回答用 Markdown，简短（< 200 字），不要客套。
""";

    private final HermesConfig config;
    private final TenantManager tenantManager;
    private final ToolApprovalCoordinator toolApprovalCoordinator;
    private final BusinessApprovalService businessApprovalService;
    private final Map<String, TenantAwareAIAgent> agentPool = new ConcurrentHashMap<>();

    /** Pending command approvals from NEEDS APPROVAL tool results. */
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    public ChatService(HermesConfig config,
                       TenantManager tenantManager,
                       ToolApprovalCoordinator toolApprovalCoordinator,
                       BusinessApprovalService businessApprovalService) {
        this.config = config;
        this.tenantManager = tenantManager;
        this.toolApprovalCoordinator = toolApprovalCoordinator;
        this.businessApprovalService = businessApprovalService;
    }

    /**
     * Streaming reply: returns a StreamingReply that fires onChunk for each
     * LLM delta, then onComplete with the final ChatReply.
     */
    public StreamingReply replyStream(ChatRequest req) {
        String userMessage = req.message == null ? "" : req.message.trim();
        String workspaceId = (req.context != null && req.context.workspaceId != null
            && !req.context.workspaceId.isBlank())
                ? req.context.workspaceId : "default";

        StreamingReply streaming = new StreamingReply();

        if (userMessage.isEmpty()) {
            streaming.complete(new ChatReply("（消息为空）", spaceName(req), 0.0, List.of(), null));
            return streaming;
        }

        // Handle approved command execution or interaction response
        if (userMessage.startsWith("__approve_command__:") || userMessage.startsWith("__interact__:")) {
            new Thread(() -> {
                ChatReply reply;
                if (userMessage.startsWith("__approve_command__:")) {
                    reply = handleApprovedCommand(userMessage, workspaceId, req);
                } else {
                    reply = handleInteractResponse(userMessage, workspaceId, req);
                }
                streaming.complete(reply);
            }, "jarvis-stream").start();
            return streaming;
        }

        TenantAwareAIAgent agent = getOrCreateAgent(workspaceId);
        if (agent == null) {
            streaming.complete(new ChatReply(
                "（未找到 workspace=" + workspaceId + " 的租户上下文）",
                spaceName(req), 0.0, List.of(), null));
            return streaming;
        }

        applySystemPrompt(agent, req);
        ToolApprovalCapture capture = new ToolApprovalCapture();
        agent.setToolApprovalCallback(ex -> capture.exception = ex);

        new Thread(() -> {
            try {
                // Use streaming processMessage
                StringBuilder resultBuilder = new StringBuilder();
                agent.processMessageStream(userMessage, chunk -> {
                    resultBuilder.append(chunk);
                    streaming.fireChunk(chunk);
                });

                capture.exception = null;
                String replyText = resultBuilder.toString().trim();
                if (replyText.isEmpty()) {
                    replyText = "（agent 未返回内容）";
                }

                List<CrossSpaceLink> links = detectCrossSpaceLinks(replyText,
                    req.context != null ? req.context.space : null);
                streaming.complete(new ChatReply(replyText, spaceName(req), 0.8, links, null));

            } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
                ChatReply reply = handleInteractionRequest(ex, req);
                streaming.complete(reply);
            } catch (Exception e) {
                log.warn("Jarvis stream failed: {}", e.getMessage(), e);
                streaming.error("agent 执行失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        }, "jarvis-stream").start();

        return streaming;
    }

    /**
     * Streaming reply container. The caller sets callbacks and calls start().
     */
    public static class StreamingReply {
        public volatile java.util.function.Consumer<String> onChunk;
        public volatile java.util.function.Consumer<ChatReply> onComplete;
        public volatile java.util.function.Consumer<String> onError;

        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);

        void fireChunk(String chunk) {
            if (onChunk != null) {
                try { onChunk.accept(chunk); } catch (Exception ignored) {}
            }
        }

        void complete(ChatReply reply) {
            if (onComplete != null) {
                try { onComplete.accept(reply); } catch (Exception ignored) {}
            }
        }

        void error(String msg) {
            if (onError != null) {
                try { onError.accept(msg); } catch (Exception ignored) {}
            }
        }

        public void start() {
            // Already started via constructor - this is a no-op for API compatibility
            started.set(true);
        }
    }

    public ChatReply reply(ChatRequest req) {
        String userMessage = req.message == null ? "" : req.message.trim();
        String workspaceId = (req.context != null && req.context.workspaceId != null
            && !req.context.workspaceId.isBlank())
                ? req.context.workspaceId : "default";

        if (userMessage.isEmpty()) {
            return new ChatReply("（消息为空）", spaceName(req), 0.0, List.of(), null);
        }

        // Handle approved command execution (from inline approval button)
        if (userMessage.startsWith("__approve_command__:")) {
            return handleApprovedCommand(userMessage, workspaceId, req);
        }

        // Handle ask_user interaction response
        if (userMessage.startsWith("__interact__:")) {
            return handleInteractResponse(userMessage, workspaceId, req);
        }

        TenantAwareAIAgent agent = getOrCreateAgent(workspaceId);
        if (agent == null) {
            return new ChatReply(
                "（未找到 workspace=" + workspaceId + " 的租户上下文，无法启动 agent）",
                spaceName(req), 0.0, List.of(), null
            );
        }

        // Wire the space context into the agent's system prompt (idempotent)
        applySystemPrompt(agent, req);

        // Register a one-shot tool approval callback: when the agent throws
        // ToolApprovalRequiredException, the callback creates a real
        // BusinessApprovalRecord via the coordinator and stashes the
        // exception so we can return the approval id to the front-end.
        ToolApprovalCapture capture = new ToolApprovalCapture();
        agent.setToolApprovalCallback(ex -> capture.exception = ex);

        try {
            String result = agent.processMessage(userMessage);
            capture.exception = null; // completed without approval gate
            String replyText = result == null ? "（agent 未返回内容）" : result.trim();

            // Detect cross-space navigation hints
            List<CrossSpaceLink> links = detectCrossSpaceLinks(replyText, req.context != null ? req.context.space : null);
            return new ChatReply(
                replyText,
                spaceName(req),
                0.8,
                links,
                null
            );
        } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
            return handleInteractionRequest(ex, req);
        } catch (Exception e) {
            log.warn("Jarvis chat agent call failed: {}", e.getMessage(), e);
            return new ChatReply(
                "（agent 执行失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()) + "）",
                spaceName(req), 0.0, List.of(), null
            );
        }
    }

    /**
     * Resume an agent that was paused on a tool approval. Used by
     * ApprovalBridge when the resolve path goes through the coordinator.
     */
    public String resumeAfterApproval(String approvalId, boolean approved, String reason) {
        // The coordinator already knows which agent to resume (it stored
        // the reference in pendingApprovals). It calls
        // agent.resumeToolApproval internally and returns the agent's
        // final text. We just propagate.
        return toolApprovalCoordinator.resumeToolApproval(approvalId, approved, reason);
    }

    private TenantAwareAIAgent getOrCreateAgent(String workspaceId) {
        return agentPool.computeIfAbsent(workspaceId, wsId -> {
            try {
                TenantContext ctx = tenantManager.resolveForWorkspace(wsId);
                if (ctx == null) {
                    log.warn("Could not resolve TenantContext for workspace {}", wsId);
                    return null;
                }
                TenantAwareAIAgent a = TenantAwareAIAgent.forContext(ctx, "jarvis-" + wsId, config);
                applyJarvisSystemPrompt(a, null);
                return a;
            } catch (Exception e) {
                log.error("Failed to create TenantAwareAIAgent for workspace {}: {}",
                    wsId, e.getMessage(), e);
                return null;
            }
        });
    }

    private void applySystemPrompt(TenantAwareAIAgent agent, ChatRequest req) {
        String prompt = BASE_SYSTEM_PROMPT + spaceContextLine(req);
        if (!prompt.equals(agent.getSystemPrompt())) {
            applyJarvisSystemPrompt(agent, req);
        }
    }

    private void applyJarvisSystemPrompt(TenantAwareAIAgent agent, ChatRequest req) {
        agent.setSystemPrompt(BASE_SYSTEM_PROMPT + spaceContextLine(req));
    }

    private static String spaceName(ChatRequest req) {
        if (req != null && req.context != null && req.context.space != null) {
            return req.context.space;
        }
        return "portal";
    }

    /**
     * Detect cross-space navigation hints in the agent reply text.
     *
     * <p>When the agent says something like "去审批中心看看" or "check the
     * DLQ in NOC", we extract a {@link CrossSpaceLink} so the front-end
     * can auto-navigate after showing the reply.
     *
     * <p>Rules (keyword-based, no LLM call needed):
     * <ul>
     *   <li>审批/approval -> /portal/approvals</li>
     *   <li>DLQ/死信 -> /noc/dlq</li>
     *   <li>SLA -> /noc/sla</li>
     *   <li>运行/run -> /portal/runs</li>
     *   <li>工作流/workflow -> /noc/workflows</li>
     *   <li>数字员工/team -> /portal/teams</li>
     *   <li>模板/template -> /portal/templates</li>
     *   <li>租户/tenant -> /ops/tenants</li>
     *   <li>日志/log -> /ops/logs</li>
     *   <li>配置/config -> /ops/config</li>
     * </ul>
     *
     * @param replyText  the agent's reply
     * @param currentSpace  the space the user is currently in (skip if same)
     * @return at most 1 cross-space link (first match), or empty list
     */
    private static List<CrossSpaceLink> detectCrossSpaceLinks(String replyText, String currentSpace) {
        if (replyText == null || replyText.isBlank()) return List.of();
        String lower = replyText.toLowerCase();

        // (keyword, targetPath, label, targetSpace)
        String[][] rules = {
            {"审批", "/portal/approvals", "待审批", "portal"},
            {"approval", "/portal/approvals", "Approvals", "portal"},
            {"死信", "/noc/dlq", "死信队列", "noc"},
            {"dlq", "/noc/dlq", "DLQ", "noc"},
            {"sla", "/noc/sla", "SLA 监控", "noc"},
            {"运行记录", "/portal/runs", "我的运行", "portal"},
            {"recent run", "/portal/runs", "Runs", "portal"},
            {"工作流", "/noc/workflows", "工作流", "noc"},
            {"workflow", "/noc/workflows", "Workflows", "noc"},
            {"数字员工", "/portal/teams", "数字员工", "portal"},
            {"team", "/portal/teams", "Teams", "portal"},
            {"场景模板", "/portal/templates", "场景模板", "portal"},
            {"template", "/portal/templates", "Templates", "portal"},
            {"租户", "/ops/tenants", "租户管理", "ops"},
            {"tenant", "/ops/tenants", "Tenants", "ops"},
            {"日志", "/ops/logs", "日志", "ops"},
            {"log", "/ops/logs", "Logs", "ops"},
        };

        for (String[] rule : rules) {
            String keyword = rule[0];
            String path = rule[1];
            String label = rule[2];
            String targetSpace = rule[3];
            if (lower.contains(keyword.toLowerCase())) {
                // Skip if the user is already in the target space.
                if (targetSpace.equals(currentSpace)) continue;
                return List.of(new CrossSpaceLink(path, label));
            }
        }
        return List.of();
    }

    private static String spaceContextLine(ChatRequest req) {
        if (req == null || req.context == null) return "";
        StringBuilder sb = new StringBuilder("\n\n当前上下文：\n");
        if (req.context.space != null) sb.append("- 空间: ").append(req.context.space).append("\n");
        if (req.context.workspaceId != null) sb.append("- 工作区: ").append(req.context.workspaceId).append("\n");
        if (req.context.activeResource != null) {
            sb.append("- 活跃资源: ")
              .append(req.context.activeResource.kind)
              .append("/")
              .append(req.context.activeResource.id);
            if (req.context.activeResource.label != null) {
                sb.append(" (").append(req.context.activeResource.label).append(")");
            }
            sb.append("\n");
        }
        sb.append("- 来源: 跨空间对话壳 (Jarvis)\n");
        return sb.toString();
    }

    /**
     * Handle an approved command: execute it directly with approved=true.
     */
    private ChatReply handleApprovedCommand(String message, String workspaceId, ChatRequest req) {
        // Format: __approve_command__:<approvalId>
        String approvalId = message.substring("__approve_command__:".length()).trim();
        PendingApproval pending = pendingApprovals.remove(approvalId);
        if (pending == null) {
            return new ChatReply("（审批已过期或不存在）", spaceName(req), 0.0, List.of(), null);
        }

        // Execute the command directly through the agent with a special message
        TenantAwareAIAgent agent = getOrCreateAgent(workspaceId);
        if (agent == null) {
            return new ChatReply("（无法找到 agent）", spaceName(req), 0.0, List.of(), null);
        }

        // Send a message to the agent telling it to re-execute with approval
        String retryMessage = "用户已确认，请重新执行以下命令（使用 approved=true）：" + pending.command;
        try {
            String result = agent.processMessage(retryMessage);
            String replyText = result == null ? "（执行完成，无输出）" : result.trim();
            return new ChatReply(replyText, spaceName(req), 0.9, List.of(), null);
        } catch (Exception e) {
            log.error("Approved command execution failed: {}", e.getMessage(), e);
            return new ChatReply("（执行失败：" + e.getMessage() + "）", spaceName(req), 0.0, List.of(), null);
        }
    }

    /**
     * Handle an ask_user interaction response: feed the user's choice back to the agent.
     */
    private ChatReply handleInteractResponse(String message, String workspaceId, ChatRequest req) {
        // Format: __interact__:<interactionId>:<response>
        String payload = message.substring("__interact__:".length());
        int colonIdx = payload.indexOf(':');
        if (colonIdx < 0) {
            return new ChatReply("（交互格式错误）", spaceName(req), 0.0, List.of(), null);
        }
        String interactionId = payload.substring(0, colonIdx);
        String response = payload.substring(colonIdx + 1);

        // Clear the pending interaction
        pendingInteractions.remove(interactionId);

        TenantAwareAIAgent agent = getOrCreateAgent(workspaceId);
        if (agent == null) {
            return new ChatReply("（无法找到 agent）", spaceName(req), 0.0, List.of(), null);
        }

        // Feed the response back to the agent
        String agentMessage = "用户选择：" + response;
        try {
            String result = agent.processMessage(agentMessage);
            String replyText = result == null ? "（执行完成）" : result.trim();
            return new ChatReply(replyText, spaceName(req), 0.9, List.of(), null);
        } catch (Exception e) {
            return new ChatReply("（执行失败：" + e.getMessage() + "）", spaceName(req), 0.0, List.of(), null);
        }
    }

    /** Pending command approval stored for inline button approval. */
    private static final class PendingApproval {
        final String approvalId;
        final String command;
        final String risk;

        PendingApproval(String approvalId, String command, String risk) {
            this.approvalId = approvalId;
            this.command = command;
            this.risk = risk;
        }
    }

    /**
     * Handle a ToolApprovalRequiredException: could be a command approval
     * or an ask_user interaction request.
     */
    private ChatReply handleInteractionRequest(
            TenantAwareAIAgent.ToolApprovalRequiredException ex, ChatRequest req) {

        String matchedRule = ex.getMatchedRule();

        // --- ask_user interaction ---
        if ("ask_user".equals(matchedRule)) {
            try {
                JSONObject spec = JSON.parseObject(ex.getToolArguments());
                String type = spec.getString("interaction_type");
                String prompt = spec.getString("prompt");
                String interactionId = "itx_" + System.currentTimeMillis();

                // Store for later resolution
                pendingInteractions.put(interactionId, spec.toJSONString());

                ChatReply.Approval approval = new ChatReply.Approval(
                    interactionId, prompt, "low");
                return new ChatReply(prompt, spaceName(req), 0.9, List.of(), approval);
            } catch (Exception e) {
                return new ChatReply("（交互请求解析失败）", spaceName(req), 0.0, List.of(), null);
            }
        }

        // --- command approval ---
        String command = extractCommandFromArgs(ex.getToolArguments());
        String risk = inferRisk(ex.getToolName());
        if (command != null && command.toLowerCase().contains("rm -rf")) risk = "high";

        String approvalId = "cmd_" + System.currentTimeMillis();
        PendingApproval pending = new PendingApproval(approvalId,
            command != null ? command : ex.getReason(), risk);
        pendingApprovals.put(approvalId, pending);

        ChatReply.Approval approval = new ChatReply.Approval(
            approvalId,
            "确认执行: " + (command != null ? command : ex.getToolName()),
            risk
        );
        String note = "⚠️ 即将执行以下命令，请确认：\n\n```\n" + pending.command
            + "\n```\n\n**风险等级**: " + risk;
        return new ChatReply(note, spaceName(req), 0.9, List.of(), approval);
    }

    /** Pending ask_user interactions. */
    private final Map<String, String> pendingInteractions = new ConcurrentHashMap<>();

    /**
     * Extract command string from tool arguments JSON.
     */
    private static String extractCommandFromArgs(String toolArguments) {
        if (toolArguments == null || toolArguments.isBlank()) return null;
        try {
            JSONObject obj = JSON.parseObject(toolArguments);
            return obj.getString("command");
        } catch (Exception e) {
            return null;
        }
    }

    private static String inferRisk(String toolName) {
        if (toolName == null) return "medium";
        String lower = toolName.toLowerCase();
        if (lower.contains("delete") || lower.contains("payment") || lower.contains("refund")
            || lower.contains("transfer") || lower.contains("drop") || lower.contains("terminate")) {
            return "high";
        }
        if (lower.contains("send") || lower.contains("email") || lower.contains("post")
            || lower.contains("publish") || lower.contains("exec") || lower.contains("write")
            || lower.contains("update") || lower.contains("deploy") || lower.contains("rotate")) {
            return "medium";
        }
        return "low";
    }

    /** Mutable capture slot used by the one-shot tool approval callback. */
    private static final class ToolApprovalCapture {
        volatile TenantAwareAIAgent.ToolApprovalRequiredException exception;
    }

    // ── DTOs (kept package-private, no Jackson/fastjson annotations) ─

    public static final class ChatRequest {
        public String message;
        public ChatContext context;
        public List<HistoryTurn> history;
    }

    public static final class ChatContext {
        public String space;
        public String workspaceId;
        public ActiveResource activeResource;
    }

    public static final class ActiveResource {
        public String kind;
        public String id;
        public String label;
    }

    public static final class HistoryTurn {
        public String role;  // "user" | "jarvis"
        public String text;
    }

    public static final class ChatReply {
        public final String text;
        public final String intent;
        public final double confidence;
        public final List<CrossSpaceLink> crossSpaceLinks;
        public final Approval approval;

        public ChatReply(String text, String intent, double confidence,
                         List<CrossSpaceLink> crossSpaceLinks, Approval approval) {
            this.text = text;
            this.intent = intent;
            this.confidence = confidence;
            this.crossSpaceLinks = crossSpaceLinks;
            this.approval = approval;
        }

        public static final class Approval {
            public final String approvalId;
            public final String title;
            public final String risk; // "low" | "medium" | "high"

            public Approval(String approvalId, String title, String risk) {
                this.approvalId = approvalId;
                this.title = title;
                this.risk = risk;
            }
        }
    }

    public static final class CrossSpaceLink {
        public final String to;
        public final String label;
        public CrossSpaceLink(String to, String label) {
            this.to = to;
            this.label = label;
        }
    }

    // helper for static JSON parse (uses fastjson2 since the project already
    // depends on it)
    static Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) return Map.of();
        return JSON.parseObject(body);
    }

    static JSONObject asJsonObject(String body) {
        if (body == null || body.isBlank()) return new JSONObject();
        return JSON.parseObject(body);
    }
}
