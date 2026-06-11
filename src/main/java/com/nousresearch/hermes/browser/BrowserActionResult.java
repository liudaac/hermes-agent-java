package com.nousresearch.hermes.browser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral result returned by a BrowserBridge implementation.
 */
public record BrowserActionResult(
    boolean ok,
    String sessionId,
    String url,
    String title,
    String content,
    String message,
    List<Map<String, Object>> actions,
    String errorCode,
    Map<String, Object> meta
) {
    public static BrowserActionResult ok(String sessionId, String url, String title, String content, String message, List<Map<String, Object>> actions) {
        return new BrowserActionResult(true, sessionId, url, title, content, message, actions != null ? actions : List.of(), null, Map.of());
    }

    public static BrowserActionResult ok(String sessionId, String url, String title, String content, String message, List<Map<String, Object>> actions, Map<String, Object> meta) {
        return new BrowserActionResult(true, sessionId, url, title, content, message, actions != null ? actions : List.of(), null, meta != null ? meta : Map.of());
    }

    public static BrowserActionResult error(String sessionId, String message) {
        return error(sessionId, "bridge_error", message, Map.of());
    }

    public static BrowserActionResult error(String sessionId, String errorCode, String message) {
        return error(sessionId, errorCode, message, Map.of());
    }

    public static BrowserActionResult error(String sessionId, String errorCode, String message, Map<String, Object> meta) {
        return new BrowserActionResult(false, sessionId, null, null, null, message, List.of(), errorCode, meta != null ? meta : Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", ok);
        if (sessionId != null) map.put("session_id", sessionId);
        if (url != null) map.put("url", url);
        if (title != null) map.put("title", title);
        if (content != null) map.put("content", content);
        if (message != null) map.put("message", message);
        if (errorCode != null) map.put("error_code", errorCode);
        map.put("actions", actions);
        if (meta != null && !meta.isEmpty()) map.put("meta", meta);
        return map;
    }
}
