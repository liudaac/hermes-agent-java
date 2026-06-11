package com.nousresearch.hermes.browser;

import java.util.Locale;
import java.util.Map;

/**
 * A normalized browser action issued by an agent through a BrowserBridge.
 */
public record BrowserAction(
    String action,
    String sessionId,
    String url,
    String target,
    String text,
    String instruction,
    String actor,
    String reason
) {
    public static BrowserAction from(Map<String, Object> args) {
        String action = string(args, "action", "observe").toLowerCase(Locale.ROOT).trim();
        return new BrowserAction(
            action,
            string(args, "session_id", string(args, "sessionId", null)),
            string(args, "url", null),
            string(args, "target", null),
            string(args, "text", null),
            string(args, "instruction", null),
            string(args, "actor", "agent"),
            string(args, "reason", "")
        );
    }

    private static String string(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        String s = String.valueOf(value);
        return s.isBlank() ? defaultValue : s;
    }
}
