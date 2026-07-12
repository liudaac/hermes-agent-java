package com.nousresearch.hermes.business.notification;

import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.approval.BusinessApprovalService.ApprovalEvent;
import com.nousresearch.hermes.business.approval.BusinessApprovalService.ApprovalEventType;
import com.nousresearch.hermes.business.template.BusinessTemplateService;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * M5 high-risk approval notification dispatcher.
 *
 * <p>Subscribes to {@link BusinessApprovalService} global events and forwards
 * high-risk approvals to configurable webhook targets (generic JSON / 飞书 /
 * 钉钉). Each notification is built as a Markdown-friendly story so the
 * receiver can read the request without opening the Portal.
 *
 * <p>Targets can be added at runtime via REST:
 *   POST /api/v1/business/notifications/targets
 *   GET  /api/v1/business/notifications/targets
 *   GET  /api/v1/business/notifications/recent
 */
public final class BusinessApprovalNotifier {
    private static final Logger logger = LoggerFactory.getLogger(BusinessApprovalNotifier.class);
    private static final int MAX_RECENT = 50;

    public enum TargetType { WEBHOOK, FEISHU, DINGTALK, EMAIL }

    public record NotificationTarget(String id, TargetType type, String url, String label) {}

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final List<NotificationTarget> targets = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> recent = new CopyOnWriteArrayList<>();

    private final BusinessApprovalService approvalService;
    private final EmailSender emailSender = new EmailSender();

    public BusinessApprovalNotifier(BusinessApprovalService approvalService) {
        this.approvalService = approvalService;
        approvalService.subscribeGlobal(this::handle);
        // Auto-register an email target if SMTP is configured and a default
        // recipient is set (NOTIFY_EMAIL_RECIPIENT / ALERT_EMAIL_RECIPIENT).
        autoRegisterEmailTarget();
    }

    private void autoRegisterEmailTarget() {
        if (!emailSender.isConfigured()) return;
        String recipient = System.getenv().getOrDefault("NOTIFY_EMAIL_RECIPIENT",
            System.getenv().getOrDefault("ALERT_EMAIL_RECIPIENT",
            System.getProperty("notify.email.recipient",
            System.getProperty("alert.email.recipient", ""))));
        if (recipient.isBlank()) return;
        NotificationTarget t = new NotificationTarget(
            "tgt-email-default", TargetType.EMAIL, recipient, "Default email alerts");
        targets.add(t);
        logger.info("Auto-registered default email notification target → {}", recipient);
    }

    public List<NotificationTarget> listTargets() { return List.copyOf(targets); }

    public NotificationTarget addTarget(TargetType type, String url, String label) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(url);
        NotificationTarget t = new NotificationTarget(
            "tgt-" + Long.toString(System.nanoTime(), 36),
            type, url, label == null ? type.name() : label);
        targets.add(t);
        logger.info("Notification target added: {} → {}", t.label(), t.url());
        return t;
    }

    public boolean removeTarget(String id) {
        return targets.removeIf(t -> t.id().equals(id));
    }

    public List<Map<String, Object>> recent() { return List.copyOf(recent); }

    private void handle(ApprovalEvent event) {
        if (event.type() != ApprovalEventType.CREATED) return;
        if (!isHighRisk(event)) return;

        Map<String, Object> notification = buildNotification(event);
        addRecent(notification);

        if (targets.isEmpty()) {
            logger.debug("High-risk approval {} created — no notification targets configured", event.approvalId());
            return;
        }

        for (NotificationTarget target : targets) {
            try {
                String body = renderForTarget(target, notification);
                if (target.type() == TargetType.EMAIL) {
                    // url field stores email address for EMAIL targets
                    String subject = "[Hermes] 高风险审批待处理: " + (event.title() != null ? event.title() : event.approvalId());
                    emailSender.send(target.url(), subject, body);
                } else {
                    send(target.url(), body);
                }
                logger.info("Notified {} → {} ({})", target.label(), event.approvalId(), event.title());
            } catch (Exception e) {
                logger.warn("Notify failed for {}: {}", target.label(), e.getMessage());
            }
        }
    }

    private static boolean isHighRisk(ApprovalEvent event) {
        Map<String, Object> data = event.data();
        if (data == null) return false;
        Object risk = data.get("riskLevel");
        if (risk instanceof String s) return s.equalsIgnoreCase("HIGH");
        return false;
    }

    private Map<String, Object> buildNotification(ApprovalEvent event) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("approvalId", event.approvalId());
        n.put("workspaceId", event.workspaceId());
        n.put("teamId", event.teamId());
        n.put("title", event.title());
        n.put("riskLevel", "HIGH");
        n.put("createdAt", event.timestamp());
        Map<String, Object> data = event.data() != null ? event.data() : Map.of();
        Object reason = data.get("riskReason");
        n.put("reason", reason);
        n.put("summary", buildSummary(event, data));
        return n;
    }

    private static String buildSummary(ApprovalEvent event, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔴 高风险审批待处理\n");
        sb.append("**").append(event.title() != null ? event.title() : event.approvalId()).append("**\n");
        if (event.teamId() != null) sb.append("团队：`").append(event.teamId()).append("`\n");
        if (event.workspaceId() != null) sb.append("空间：`").append(event.workspaceId()).append("`\n");
        Object reason = data.get("riskReason");
        if (reason != null) sb.append("原因：").append(reason).append("\n");
        Object action = data.get("riskAction");
        if (action != null) sb.append("动作：").append(action).append("\n");
        sb.append("\n→ 请在 Business Portal 的审批中心处理。");
        return sb.toString();
    }

    private String renderForTarget(NotificationTarget target, Map<String, Object> notification) {
        return switch (target.type()) {
            case FEISHU -> renderFeishu(notification);
            case DINGTALK -> renderDingTalk(notification);
            case WEBHOOK -> renderGenericJson(notification);
            case EMAIL -> renderEmailBody(notification); // url field = email address, body = plain text
        };
    }

    private static String renderFeishu(Map<String, Object> n) {
        // Feishu interactive markdown card.
        String summary = String.valueOf(n.getOrDefault("summary", ""));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "interactive");
        body.put("card", Map.of(
            "header", Map.of(
                "template", "red",
                "title", Map.of("tag", "plain_text", "content", "🔴 高风险审批")),
            "elements", List.of(Map.of(
                "tag", "markdown",
                "content", summary))));
        return toJson(body);
    }

    private static String renderDingTalk(Map<String, Object> n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of(
            "title", "高风险审批待处理",
            "text", n.getOrDefault("summary", "")));
        return toJson(body);
    }

    private static String renderGenericJson(Map<String, Object> n) {
        return toJson(n);
    }

    private static String renderEmailBody(Map<String, Object> n) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hermes — 高风险审批待处理\n");
        sb.append("========================\n\n");
        sb.append("标题: ").append(n.getOrDefault("title", "")).append("\n");
        sb.append("审批ID: ").append(n.getOrDefault("approvalId", "")).append("\n");
        sb.append("工作空间: ").append(n.getOrDefault("workspaceId", "")).append("\n");
        Object team = n.get("teamId");
        if (team != null) sb.append("团队: ").append(team).append("\n");
        Object reason = n.get("reason");
        if (reason != null) sb.append("风险原因: ").append(reason).append("\n");
        sb.append("\n请登录 Business Portal 审批中心处理：\n");
        sb.append("/portal/index.html?page=approvals\n");
        sb.append("\n时间: ").append(n.getOrDefault("createdAt", "")).append("\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object o) {
        return com.alibaba.fastjson2.JSON.toJSONString(o);
    }

    private void send(String url, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    private void addRecent(Map<String, Object> notification) {
        recent.add(0, notification);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
    }

    // ─── HTTP routes ────────────────────────────────────────────────────

    public static void registerRoutes(Javalin app, BusinessApprovalNotifier notifier) {
        logger.info("Registering Business Approval Notifier routes");
        app.get("/api/v1/business/notifications/targets", ctx -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (NotificationTarget t : notifier.listTargets()) {
                out.add(Map.of("id", t.id(), "type", t.type().name(), "url", t.url(), "label", t.label()));
            }
            ctx.status(200).json(Map.of("ok", true, "targets", out));
        });
        app.post("/api/v1/business/notifications/targets", ctx -> {
            var body = com.alibaba.fastjson2.JSON.parseObject(ctx.body());
            String typeRaw = body.getString("type");
            String url = body.getString("url");
            String label = body.getString("label");
            if (url == null || url.isBlank()) {
                ctx.status(400).json(Map.of("ok", false, "error", "url required"));
                return;
            }
            TargetType type;
            try {
                type = TargetType.valueOf(typeRaw == null ? "WEBHOOK" : typeRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("ok", false, "error", "invalid type; expected WEBHOOK|FEISHU|DINGTALK|EMAIL"));
                return;
            }
            // For EMAIL targets, url is the recipient address — basic email shape check
            if (type == TargetType.EMAIL && !url.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                ctx.status(400).json(Map.of("ok", false, "error", "url must be a valid email address for EMAIL targets"));
                return;
            }
            // For WEBHOOK targets, require http(s) URL
            if (type != TargetType.EMAIL && !url.startsWith("http://") && !url.startsWith("https://")) {
                ctx.status(400).json(Map.of("ok", false, "error", "url must start with http:// or https://"));
                return;
            }
            var t = notifier.addTarget(type, url, label);
            ctx.status(201).json(Map.of("ok", true,
                "target", Map.of("id", t.id(), "type", t.type().name(), "url", t.url(), "label", t.label())));
        });
        app.delete("/api/v1/business/notifications/targets/{id}", ctx -> {
            String id = ctx.pathParam("id");
            boolean removed = notifier.removeTarget(id);
            ctx.status(removed ? 200 : 404).json(Map.of("ok", removed));
        });
        app.get("/api/v1/business/notifications/recent", ctx -> {
            ctx.status(200).json(Map.of("ok", true, "items", notifier.recent()));
        });
    }
}
