package com.nousresearch.hermes.tenant.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Webhook 告警渠道
 * 
 * 支持钉钉、飞书、Slack 等通过 Webhook 接收告警的系统。
 * 配置：ALERT_WEBHOOK_URL 环境变量
 */
public class WebhookAlertChannel implements AlertChannel {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookAlertChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    private final String webhookUrl;
    private final String platform; // dingtalk, feishu, slack, generic
    private final boolean enabled;
    
    public WebhookAlertChannel() {
        this.webhookUrl = System.getenv().getOrDefault("ALERT_WEBHOOK_URL", 
            System.getProperty("alert.webhook.url", ""));
        this.platform = detectPlatform(webhookUrl);
        this.enabled = !webhookUrl.isEmpty();
    }
    
    @Override
    public boolean send(MetricsCollector.AlertLevel level, String tenantId, String type, String message) {
        if (!enabled) {
            logger.debug("Webhook alert channel not configured");
            return false;
        }
        
        try {
            String payload = buildPayload(level, tenantId, type, message);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            
            if (success) {
                logger.info("Webhook alert sent to {}: {} - {}", platform, type, tenantId);
            } else {
                logger.error("Webhook alert failed: HTTP {} - {}", 
                    response.statusCode(), response.body());
            }
            
            return success;
            
        } catch (Exception e) {
            logger.error("Failed to send webhook alert: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 根据 URL 检测平台类型
     */
    private String detectPlatform(String url) {
        if (url.contains("dingtalk")) return "dingtalk";
        if (url.contains("feishu") || url.contains("larksuite")) return "feishu";
        if (url.contains("slack")) return "slack";
        if (url.contains("discord")) return "discord";
        return "generic";
    }
    
    /**
     * 构建对应平台的 payload
     */
    private String buildPayload(MetricsCollector.AlertLevel level, String tenantId, 
                                 String type, String message) throws Exception {
        String color = switch (level) {
            case CRITICAL -> "red";
            case WARNING -> "orange";
            case INFO -> "green";
        };
        
        String title = String.format("[%s] Hermes Alert: %s", level, type);
        String content = String.format("Tenant: %s\n%s", tenantId, message);
        
        return switch (platform) {
            case "dingtalk" -> buildDingTalkPayload(title, content, color);
            case "feishu" -> buildFeishuPayload(title, content, color);
            case "slack" -> buildSlackPayload(title, content, color);
            default -> buildGenericPayload(title, content, color);
        };
    }
    
    private String buildDingTalkPayload(String title, String content, String color) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "title", title,
                "text", String.format("### %s\n> %s", title, content.replace("\n", "\n> "))
            )
        ));
    }
    
    private String buildFeishuPayload(String title, String content, String color) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "msg_type", "interactive",
            "card", Map.of(
                "header", Map.of(
                    "title", Map.of("tag", "plain_text", "content", title),
                    "template", color
                ),
                "elements", new Object[]{
                    Map.of("tag", "div", "text", Map.of(
                        "tag", "lark_md", 
                        "content", content
                    ))
                }
            )
        ));
    }
    
    private String buildSlackPayload(String title, String content, String color) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "attachments", new Object[]{
                Map.of(
                    "color", color,
                    "title", title,
                    "text", content,
                    "footer", "Hermes Agent",
                    "ts", System.currentTimeMillis() / 1000
                )
            }
        ));
    }
    
    private String buildGenericPayload(String title, String content, String color) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "level", title,
            "title", title,
            "message", content,
            "color", color,
            "timestamp", java.time.Instant.now().toString()
        ));
    }
    
    @Override
    public String getName() {
        return "webhook-" + platform;
    }
    
    @Override
    public boolean isAvailable() {
        return enabled;
    }
}
