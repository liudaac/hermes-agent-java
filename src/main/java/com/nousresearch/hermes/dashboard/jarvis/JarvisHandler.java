package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.event.BusinessEventBus;
import com.nousresearch.hermes.dashboard.jarvis.ChatService.ChatReply;
import com.nousresearch.hermes.dashboard.jarvis.ChatService.ChatRequest;
import com.nousresearch.hermes.dashboard.jarvis.IntentRouter.RoutingResult;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JarvisHandler — FastAPI-equivalent of the four Jarvis endpoints, now
 * wired into the live agent runtime.
 *
 * <p>Routes (registered in DashboardServer):</p>
 * <ul>
 *   <li>{@code POST /api/jarvis/chat} → {@link #chat(Context)}</li>
 *   <li>{@code POST /api/jarvis/intent} → {@link #classifyIntent(Context)}</li>
 *   <li>{@code GET  /api/jarvis/stream} → {@link #streamSuggestions(SseClient)} (SSE)</li>
 *   <li>{@code POST /api/jarvis/approval/{approvalId}} → {@link #resolveApproval(Context)}</li>
 * </ul>
 *
 * <p>Each endpoint touches the real runtime:</p>
 * <ul>
 *   <li><b>chat</b> goes through {@link ChatService} which delegates to
 *       {@code TenantAwareAIAgent.processMessage()}. Tool-approval gates
 *       are surfaced via the {@link ChatReply.Approval} field.</li>
 *   <li><b>resolveApproval</b> goes through {@link ApprovalBridge} which
 *       dispatches to {@code ToolApprovalCoordinator.resumeToolApproval}
 *       (for tool-level approvals — the agent resumes and returns its
 *       final text) or {@code BusinessApprovalService.approve/reject}
 *       (for Jarvis-initiated approvals).</li>
 *   <li><b>stream</b> subscribes the new SSE client to
 *       {@link BusinessEventBus} and {@link BusinessApprovalService}'s
 *       global event stream, so workflow / SLA / DLQ / takeover / run
 *       status / approval lifecycle events appear as proactive
 *       suggestions in real time.</li>
 * </ul>
 */
public class JarvisHandler {
    private static final Logger log = LoggerFactory.getLogger(JarvisHandler.class);

    private final ChatService chatService;
    private final IntentRouter intentRouter;
    private final ApprovalBridge approvalBridge;
    private final BusinessEventBus businessEventBus;
    private final BusinessApprovalService businessApprovalService;

    /**
     * Per-client SSE state. We need to remember each connected client's
     * workspace scope so we can filter cross-tenant events (see
     * {@link #broadcastFiltered}).
     *
     * <ul>
     *   <li>{@code workspaceId} — the workspace this client is scoped to.
     *       {@code null} means "no events at all" (defense in depth: a
     *       client that doesn't declare its scope is a misconfiguration).</li>
     *   <li>{@code allAccess} — admin mode: receives events from every
     *       workspace, including events with no workspaceId. Opt-in only
     *       via {@code ?all=true} on the SSE URL.</li>
     *   <li>{@code seesGlobalEvents} — whether to deliver events that
     *       don't carry a workspaceId (e.g. system-wide stats).
     *       Only {@code allAccess=true} clients get these.</li>
     * </ul>
     */
    public record StreamContext(
        String id,
        SseClient client,
        String workspaceId,
        boolean allAccess
    ) {
        public boolean canSee(String eventWorkspaceId) {
            if (allAccess) return true;
            if (eventWorkspaceId == null || eventWorkspaceId.isBlank()) {
                // Workspace-scoped events without a workspaceId are
                // considered system-wide; only allAccess clients see them.
                return false;
            }
            return eventWorkspaceId.equals(workspaceId);
        }
    }

    /** Active SSE streams, keyed by client id. */
    private final Map<String, StreamContext> streams = new ConcurrentHashMap<>();
    private final AtomicLong suggestionSeq = new AtomicLong(0);

    /** Single global subscriber that we register with the event bus. */
    private Consumer<BusinessEventBus.BusinessEvent> businessEventSubscriber;
    /** Single global subscriber that we register with the approval service. */
    private Consumer<BusinessApprovalService.ApprovalEvent> approvalEventSubscriber;

    public JarvisHandler(ChatService chatService,
                         IntentRouter intentRouter,
                         ApprovalBridge approvalBridge,
                         BusinessEventBus businessEventBus,
                         BusinessApprovalService businessApprovalService) {
        this.chatService = chatService;
        this.intentRouter = intentRouter;
        this.approvalBridge = approvalBridge;
        this.businessEventBus = businessEventBus;
        this.businessApprovalService = businessApprovalService;
        wireEventSubscriptions();
    }

    private void wireEventSubscriptions() {
        if (businessEventBus != null) {
            this.businessEventSubscriber = this::onBusinessEvent;
            businessEventBus.subscribe(businessEventSubscriber);
            log.info("JarvisHandler subscribed to BusinessEventBus");
        }
        if (businessApprovalService != null) {
            this.approvalEventSubscriber = this::onApprovalEvent;
            businessApprovalService.subscribeGlobal(approvalEventSubscriber);
            log.info("JarvisHandler subscribed to BusinessApprovalService global events");
        }
    }

    // ── POST /api/jarvis/chat ──────────────────────────────────────

    public void chat(Context ctx) {
        ChatRequest req = parseChatRequest(ctx.body());
        ChatReply reply = chatService.reply(req);

        JSONObject body = new JSONObject();
        body.put("reply", reply.text);
        body.put("intent", reply.intent);
        body.put("confidence", reply.confidence);
        if (!reply.crossSpaceLinks.isEmpty()) {
            body.put("crossSpaceLink", reply.crossSpaceLinks.get(0));
        }
        if (reply.approval != null) {
            JSONObject ap = new JSONObject();
            ap.put("approvalId", reply.approval.approvalId);
            ap.put("title", reply.approval.title);
            ap.put("risk", reply.approval.risk);
            body.put("approval", ap);
        }
        ctx.json(body);
    }

    // ── POST /api/jarvis/intent ────────────────────────────────────

    public void classifyIntent(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing body"));
            return;
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(body);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid json"));
            return;
        }
        String input = obj.getString("input");
        if (input == null || input.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing 'input'"));
            return;
        }
        String workspaceId = obj.getString("workspaceId");

        // route(input, workspaceId) classifies + dispatches to the
        // appropriate product's query service. For "cross" or unclassifiable
        // input, routed is null and only the classification is returned.
        IntentRouter.RoutingResult result = intentRouter.route(input, workspaceId);

        JSONObject out = new JSONObject();
        out.put("intent", result.intent.getIntent());
        out.put("confidence", result.intent.getConfidence());
        out.put("source", result.intent.getSource());
        if (result.intent.getReasoning() != null) {
            out.put("reasoning", result.intent.getReasoning());
        }
        if (result.routed != null) {
            JSONObject routed = new JSONObject();
            routed.put("action", result.routed.action());
            routed.put("summary", result.routed.summary());
            routed.put("linkTo", result.routed.linkTo());
            JSONObject data = new JSONObject();
            if (result.routed.data() != null) {
                for (var e : result.routed.data().entrySet()) {
                    data.put(e.getKey(), e.getValue());
                }
            }
            routed.put("data", data);
            out.put("routed", routed);
        }
        ctx.json(out);
    }

    // ── GET /api/jarvis/stream (SSE) ──────────────────────────────
    //
    // The stream is workspace-scoped: a client only receives events
    // whose workspaceId matches the workspace they declared on connect.
    // Pass ?workspaceId=<id> in the URL for the standard case, or
    // ?all=true for an admin/super-user view that crosses workspaces.
    // Clients that declare neither receive nothing — defense in depth.

    public void streamSuggestions(SseClient client, String workspaceId, boolean allAccess) {
        String id = "jarvis-sse-" + System.nanoTime();
        String safeWorkspace = (workspaceId == null || workspaceId.isBlank()) ? null : workspaceId;
        StreamContext ctx = new StreamContext(id, client, safeWorkspace, allAccess);
        streams.put(id, ctx);

        client.keepAlive();
        JSONObject hello = new JSONObject();
        hello.put("msg", "jarvis stream connected");
        hello.put("workspaceId", safeWorkspace);
        hello.put("allAccess", allAccess);
        if (safeWorkspace == null && !allAccess) {
            hello.put("warning", "no workspaceId declared and allAccess=false; no events will be delivered");
        }
        client.sendEvent("hello", hello.toJSONString());

        client.onClose(() -> {
            streams.remove(id);
            log.debug("Jarvis SSE client disconnected: {}", id);
        });

        log.info("Jarvis SSE client connected: id={} workspace={} allAccess={}",
            id, safeWorkspace, allAccess);
    }

    /**
     * Push a suggestion to every connected client whose workspace
     * scope includes {@code eventWorkspaceId}. This is the workspace-
     * safe broadcast: the only path event subscribers should use.
     *
     * @param eventWorkspaceId workspace the originating event belongs
     *                         to, or {@code null} for system-wide events
     *                         (only delivered to {@code allAccess=true}
     *                         clients)
     */
    public void broadcastFiltered(String eventWorkspaceId, String title, String body,
                                   String severity, String linkTo) {
        if (streams.isEmpty()) return;
        JSONObject evt = new JSONObject();
        evt.put("id", "s-" + suggestionSeq.incrementAndGet());
        evt.put("title", title);
        evt.put("body", body);
        evt.put("severity", severity);
        if (linkTo != null) evt.put("linkTo", linkTo);
        evt.put("workspaceId", eventWorkspaceId);
        evt.put("createdAt", java.time.Instant.now().toString());
        String data = evt.toJSONString();
        int delivered = 0;
        for (StreamContext ctx : streams.values()) {
            if (!ctx.canSee(eventWorkspaceId)) continue;
            try {
                ctx.client.sendEvent("suggestion", data);
                delivered++;
            } catch (Exception ignored) {
                // SSE is best-effort; dead clients are removed on close
            }
        }
        if (delivered > 0 && log.isDebugEnabled()) {
            log.debug("Jarvis broadcast '{}' (ws={}, severity={}) → {}/{} clients",
                title, eventWorkspaceId, severity, delivered, streams.size());
        }
    }

    public int activeStreamCount() {
        return streams.size();
    }

    // ── POST /api/jarvis/approval/{approvalId} ────────────────────

    public void resolveApproval(Context ctx) {
        String approvalId = ctx.pathParam("approvalId");
        if (approvalId == null || approvalId.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing approvalId"));
            return;
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid json"));
            return;
        }
        String decision = obj.getString("decision");
        if (decision == null) {
            ctx.status(400).json(Map.of("error", "missing 'decision'"));
            return;
        }

        boolean approved = "approve".equalsIgnoreCase(decision)
            || "approved".equalsIgnoreCase(decision);
        ApprovalBridge.ResolutionResult result = approvalBridge.resolve(approvalId, decision);

        JSONObject out = new JSONObject();
        out.put("ok", result.ok());
        out.put("approved", approved);
        out.put("approvalId", approvalId);
        out.put("decision", result.decision().name());
        if (result.reply() != null && !result.reply().isBlank()) {
            out.put("reply", result.reply());
        }
        ctx.json(out);
    }

    // ── Event bus → suggestion translation ────────────────────────

    private void onBusinessEvent(BusinessEventBus.BusinessEvent event) {
        if (event == null) return;
        String type = event.type();
        if (type == null) return;

        String ws = event.workspaceId();

        // Skip noisy / low-value events by default. v1 keeps it conservative.
        switch (type) {
            case "WORKFLOW_CHECKPOINT" -> {
                String step = stringField(event, "stepName");
                String decision = stringField(event, "decision");
                String link = "/portal/runs/" + safeEntity(event);
                broadcastFiltered(ws,
                    "工作流等待确认",
                    "步骤 " + (step == null ? "?" : step)
                        + " 等待决策: " + (decision == null ? "?" : decision),
                    "info",
                    link
                );
            }
            case "SLA_WARN" -> {
                String sla = stringField(event, "slaName");
                long elapsed = longField(event, "elapsedMs");
                long threshold = longField(event, "thresholdMs");
                broadcastFiltered(ws,
                    "SLA 即将超时",
                    "SLA '" + (sla == null ? "?" : sla) + "' 已用 "
                        + formatMs(elapsed) + " / 阈值 " + formatMs(threshold),
                    "warning",
                    "/noc/sla"
                );
            }
            case "SLA_BREACH" -> {
                String sla = stringField(event, "slaName");
                String action = stringField(event, "action");
                broadcastFiltered(ws,
                    "SLA 已超时",
                    "SLA '" + (sla == null ? "?" : sla) + "' 触发动作: "
                        + (action == null ? "（未指定）" : action),
                    "critical",
                    "/noc/sla"
                );
            }
            case "DLQ_ENQUEUE" -> {
                String runId = stringField(event, "runId");
                String reason = stringField(event, "reason");
                broadcastFiltered(ws,
                    "任务进入死信队列",
                    "Run " + (runId == null ? "?" : runId) + ": "
                        + (reason == null ? "（无原因）" : reason),
                    "warning",
                    "/noc/dlq"
                );
            }
            case "TAKEOVER_REQUESTED" -> {
                String runId = stringField(event, "runId");
                String operatorId = stringField(event, "operatorId");
                broadcastFiltered(ws,
                    "需要人工接管",
                    "Run " + (runId == null ? "?" : runId) + " 被 "
                        + (operatorId == null ? "?" : operatorId) + " 申请接管",
                    "critical",
                    "/noc/takeovers"
                );
            }
            case "RUN_STATUS" -> {
                // Only push the "completed" / "failed" terminal statuses to
                // avoid spamming the stream with progress events.
                String status = stringField(event, "status");
                if (status == null) return;
                String lower = status.toLowerCase();
                if (!lower.contains("completed") && !lower.contains("failed")
                    && !lower.contains("succeeded") && !lower.contains("error")) {
                    return;
                }
                String message = stringField(event, "message");
                broadcastFiltered(ws,
                    "Run " + status,
                    message == null ? ("Run " + safeEntity(event) + " " + status) : message,
                    lower.contains("fail") || lower.contains("error") ? "critical" : "info",
                    "/portal/runs/" + safeEntity(event)
                );
            }
            default -> {
                // Unhandled event types are silently ignored. Add a case
                // above when you want them surfaced in the stream.
            }
        }
    }

    private void onApprovalEvent(BusinessApprovalService.ApprovalEvent event) {
        if (event == null) return;
        String type = event.type() == null ? "" : event.type().name();
        String title;
        String severity = "info";
        String link = "/portal/approvals";
        switch (type) {
            case "CREATED" -> {
                title = "新审批待处理";
                severity = "warning";
            }
            case "APPROVED" -> {
                title = "审批已通过";
            }
            case "REJECTED" -> {
                title = "审批被驳回";
                severity = "warning";
            }
            case "INFO_REQUESTED" -> {
                title = "审批请求补充信息";
                severity = "info";
            }
            case "EXECUTION_RESUMED" -> {
                title = "审批后流程已恢复";
            }
            default -> {
                return;
            }
        }
        String ws = event.workspaceId();
        String body = ws == null ? event.approvalId() : (ws + " / " + event.approvalId());
        if (event.actor() != null && !event.actor().isBlank()) {
            body += " — " + event.actor();
        }
        broadcastFiltered(ws, title, body, severity, link);
    }

    private static String stringField(BusinessEventBus.BusinessEvent event, String key) {
        if (event.data() == null) return null;
        Object v = event.data().get(key);
        return v == null ? null : v.toString();
    }

    private static long longField(BusinessEventBus.BusinessEvent event, String key) {
        if (event.data() == null) return 0;
        Object v = event.data().get(key);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }

    private static String safeEntity(BusinessEventBus.BusinessEvent event) {
        return event.entityId() == null ? "" : event.entityId();
    }

    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return (ms / 1000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m";
        return (ms / 3_600_000) + "h" + ((ms % 3_600_000) / 60_000) + "m";
    }

    // ── body parsing helpers ───────────────────────────────────────

    private ChatRequest parseChatRequest(String body) {
        ChatRequest req = new ChatRequest();
        if (body == null || body.isBlank()) return req;
        try {
            JSONObject obj = JSON.parseObject(body);
            req.message = obj.getString("message");

            JSONObject ctxObj = obj.getJSONObject("context");
            if (ctxObj != null) {
                ChatService.ChatContext c = new ChatService.ChatContext();
                c.space = ctxObj.getString("space");
                c.workspaceId = ctxObj.getString("workspaceId");
                JSONObject ar = ctxObj.getJSONObject("activeResource");
                if (ar != null) {
                    ChatService.ActiveResource a = new ChatService.ActiveResource();
                    a.kind = ar.getString("kind");
                    a.id = ar.getString("id");
                    a.label = ar.getString("label");
                    c.activeResource = a;
                }
                req.context = c;
            }
            // (history intentionally skipped — the agent owns its own
            // conversation history via TenantAwareAIAgent's per-workspace
            // instance pool in ChatService)
        } catch (Exception e) {
            log.warn("Failed to parse /api/jarvis/chat body: {}", e.getMessage());
        }
        return req;
    }
}
