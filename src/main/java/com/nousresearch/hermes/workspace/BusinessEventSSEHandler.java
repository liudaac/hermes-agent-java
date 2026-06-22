package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.business.event.BusinessEventBus;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * SSE（Server-Sent Events）处理器 — 将业务事件总线的事件推送到前端浏览器。
 *
 * <p>端点：<code>/api/v1/business/events/stream</code></p>
 * <p>生命周期：
 * <ol>
 *   <li>前端建立 EventSource 连接</li>
 *   <li>本类注册为 BusinessEventBus 订阅者</li>
 *   <li>任何模块发布事件时，通过 sendEvent 推送到浏览器</li>
 *   <li>前端断开时自动取消订阅，防止内存泄漏</li>
 * </ol>
 */
public class BusinessEventSSEHandler {
    private static final Logger logger = LoggerFactory.getLogger(BusinessEventSSEHandler.class);

    private final BusinessEventBus eventBus;
    /** 维护 SSE 客户端与事件监听器的映射，断开时清理 */
    private final ConcurrentHashMap<SseClient, Consumer<BusinessEventBus.BusinessEvent>> clientMap = new ConcurrentHashMap<>();

    public BusinessEventSSEHandler(BusinessEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 处理新的 SSE 连接。
     * @param client Javalin SSE 客户端实例
     */
    public void handle(SseClient client) {
        logger.info("SSE client connected: {}", client.hashCode());

        // 为每个客户端创建一个独立的事件监听器
        Consumer<BusinessEventBus.BusinessEvent> listener = event -> {
            try {
                String json = buildEventJson(event);
                client.sendEvent(event.type(), json);
            } catch (Exception e) {
                logger.warn("Failed to send SSE event to client {}: {}", client.hashCode(), e.getMessage());
            }
        };

        clientMap.put(client, listener);
        eventBus.subscribe(listener);

        // Send connected ping
        client.sendEvent("connected", "{\"ok\":true,\"message\":\"Business event stream connected\"}");

        client.onClose(() -> {
            logger.info("SSE client disconnected: {}", client.hashCode());
            Consumer<BusinessEventBus.BusinessEvent> removed = clientMap.remove(client);
            if (removed != null) {
                eventBus.unsubscribe(removed);
            }
        });
    }

    private String buildEventJson(BusinessEventBus.BusinessEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"").append(escapeJson(event.type())).append("\"");
        sb.append(",\"entityId\":\"").append(escapeJson(event.entityId())).append("\"");
        sb.append(",\"workspaceId\":\"").append(escapeJson(event.workspaceId())).append("\"");
        sb.append(",\"data\":{");
        if (event.data() != null) {
            boolean first = true;
            for (Map.Entry<String, Object> entry : event.data().entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                Object value = entry.getValue();
                if (value instanceof Number) {
                    sb.append(value);
                } else if (value instanceof Boolean) {
                    sb.append(value);
                } else {
                    sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
                }
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
