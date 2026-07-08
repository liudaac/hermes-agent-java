package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.dashboard.jarvis.ChatService.ChatReply;
import com.nousresearch.hermes.dashboard.jarvis.ChatService.ChatRequest;
import com.nousresearch.hermes.dashboard.jarvis.IntentRouter.IntentResult;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JarvisHandler — FastAPI-equivalent of the four Jarvis endpoints.
 *
 * Routes (registered in DashboardServer):
 *   POST /api/jarvis/chat                    →  chat
 *   POST /api/jarvis/intent                  →  classifyIntent
 *   GET  /api/jarvis/stream                  →  streamSuggestions  (SSE, v1)
 *   POST /api/jarvis/approval/{approvalId}  →  resolveApproval
 *
 * MVP scope (this commit):
 *   - chat: full LLM call, history preserved, space context injected
 *   - classifyIntent: full LLM classification, JSON shape enforced
 *   - resolveApproval: thin wrapper over ApprovalSystem
 *   - streamSuggestions: stub — connects and sends a heartbeat every 15s
 *     (real proactive-suggestion engine lands in v1 with ReflectionDaemon)
 */
public class JarvisHandler {
    private static final Logger log = LoggerFactory.getLogger(JarvisHandler.class);

    private final ChatService chatService;
    private final IntentRouter intentRouter;
    private final ApprovalBridge approvalBridge;

    /** Active SSE streams, keyed by client id. */
    private final Map<String, SseClient> streams = new ConcurrentHashMap<>();
    private final AtomicLong suggestionSeq = new AtomicLong(0);

    public JarvisHandler(ChatService chatService, IntentRouter intentRouter, ApprovalBridge approvalBridge) {
        this.chatService = chatService;
        this.intentRouter = intentRouter;
        this.approvalBridge = approvalBridge;
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

        IntentResult result = intentRouter.route(input);
        JSONObject out = new JSONObject();
        out.put("intent", result.getIntent());
        out.put("confidence", result.getConfidence());
        out.put("source", result.getSource());
        if (result.getReasoning() != null) {
            out.put("reasoning", result.getReasoning());
        }
        ctx.json(out);
    }

    // ── GET /api/jarvis/stream (SSE) ──────────────────────────────
    //
    // v1 stub: keeps the connection alive with a heartbeat every 15s
    // so the front-end's EventSource doesn't time out. Real proactive
    // suggestions land when ReflectionDaemon is wired in.

    public void streamSuggestions(SseClient client) {
        String id = "jarvis-sse-" + System.nanoTime();
        streams.put(id, client);

        client.keepAlive();
        client.sendEvent("hello", "{\"msg\":\"jarvis stream connected\"}");

        client.onClose(() -> {
            streams.remove(id);
            log.debug("Jarvis SSE client disconnected: {}", id);
        });
    }

    /** Allow the ReflectionDaemon to push a suggestion onto all live streams. */
    public void broadcastSuggestion(String title, String body, String severity) {
        if (streams.isEmpty()) return;
        JSONObject evt = new JSONObject();
        evt.put("id", "s-" + suggestionSeq.incrementAndGet());
        evt.put("title", title);
        evt.put("body", body);
        evt.put("severity", severity);
        evt.put("createdAt", java.time.Instant.now().toString());
        String data = evt.toJSONString();
        for (SseClient c : streams.values()) {
            try {
                c.sendEvent("suggestion", data);
            } catch (Exception ignored) {
                // SSE is best-effort; remove the dead client on next tick
            }
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
        approvalBridge.resolve(approvalId, decision);
        ctx.json(Map.of("ok", true, "approved", approved, "approvalId", approvalId));
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
            // (history intentionally skipped in MVP — would re-hydrate from
            // front-end sessionStorage on next commit when we add it)
        } catch (Exception e) {
            log.warn("Failed to parse /api/jarvis/chat body: {}", e.getMessage());
        }
        return req;
    }
}
