package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ChatService — answer a user message with the LLM.
 *
 * MVP scope (design.md §16):
 *   - system prompt describes Jarvis's role + the current space context
 *   - multi-turn history preserved (capped at 10 turns)
 *   - returns plain text reply (no tool calls, no approval gating —
 *     those land in v1)
 *   - any LLM error → graceful fallback message so the front-end never
 *     sees an empty/null reply
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
- 如果用户的问题是危险操作（删除、配置变更、部署、跨空间批量动作），
  不要直接执行——告诉用户「我已准备好执行该操作，请确认」，等待用户在
  浮窗内批准/驳回。

回答用 Markdown，简短（< 120 字），不要客套。
""";

    private final ModelClient modelClient;

    public ChatService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public ChatReply reply(ChatRequest req) {
        String userMessage = req.message == null ? "" : req.message.trim();
        if (userMessage.isEmpty()) {
            return new ChatReply("（消息为空）", "portal", 0.0, List.of());
        }

        List<ModelMessage> messages = buildMessages(userMessage, req);
        ChatCompletionResponse resp;
        try {
            resp = modelClient.chatCompletion(messages, null, false);
        } catch (Exception e) {
            log.warn("Jarvis chat LLM call failed: {}", e.getMessage());
            return new ChatReply(
                "（后台繁忙，请稍后再试）",
                req.context != null && req.context.space != null ? req.context.space : "portal",
                0.0,
                List.of()
            );
        }

        if (resp == null || resp.getContent() == null) {
            return new ChatReply(
                "（后台未返回内容）",
                req.context != null && req.context.space != null ? req.context.space : "portal",
                0.0,
                List.of()
            );
        }

        String text = resp.getContent().trim();
        String intent = req.context != null && req.context.space != null ? req.context.space : "portal";
        double confidence = 0.5;
        return new ChatReply(text, intent, confidence, List.of());
    }

    private List<ModelMessage> buildMessages(String userMessage, ChatRequest req) {
        List<ModelMessage> out = new ArrayList<>();
        out.add(ModelMessage.system(BASE_SYSTEM_PROMPT + spaceContextLine(req)));

        if (req.history != null) {
            int n = Math.min(MAX_HISTORY_TURNS, req.history.size());
            int start = req.history.size() - n;
            for (int i = start; i < req.history.size(); i++) {
                HistoryTurn turn = req.history.get(i);
                if (turn.role == null) continue;
                String text = turn.text == null ? "" : turn.text;
                if (turn.role.equalsIgnoreCase("user")) {
                    out.add(ModelMessage.user(text));
                } else if (turn.role.equalsIgnoreCase("jarvis")) {
                    out.add(ModelMessage.assistant(text));
                }
            }
        }

        out.add(ModelMessage.user(userMessage));
        return out;
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
        return sb.toString();
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

        public ChatReply(String text, String intent, double confidence,
                         List<CrossSpaceLink> crossSpaceLinks) {
            this.text = text;
            this.intent = intent;
            this.confidence = confidence;
            this.crossSpaceLinks = crossSpaceLinks;
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
