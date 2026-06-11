package com.nousresearch.hermes.browser;

import java.util.Locale;
import java.util.Map;

/**
 * Safety policy for real-browser actions, especially actions carrying user login state.
 */
public class BrowserBridgePolicy {
    public record Decision(boolean allowed, String reason, boolean requiresConfirmation) {}

    public Decision check(BrowserAction action, Map<String, Object> rawArgs) {
        if (action == null || action.action() == null || action.action().isBlank()) {
            return new Decision(false, "Browser action is required", false);
        }

        String kind = action.action().toLowerCase(Locale.ROOT).trim();
        if (!kind.matches("open|observe|click|type|extract|close|scroll|press|submit")) {
            return new Decision(false, "Unsupported browser action: " + action.action(), false);
        }

        if ("open".equals(kind)) {
            String url = action.url();
            if (url == null || url.isBlank()) {
                return new Decision(false, "url is required for open", false);
            }
            String lowerUrl = url.toLowerCase(Locale.ROOT).trim();
            if (!(lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://"))) {
                return new Decision(false, "Only http(s) URLs are allowed by BrowserBridge", false);
            }
        }

        boolean sensitive = isSensitive(kind, action);
        if (sensitive && !truthy(rawArgs.get("confirmed"))) {
            return new Decision(false,
                "Browser action requires explicit confirmation because it may submit, publish, delete, purchase, or change account state",
                true);
        }

        return new Decision(true, "allowed", false);
    }

    private boolean isSensitive(String kind, BrowserAction action) {
        String haystack = String.join(" ",
            nullToEmpty(kind), nullToEmpty(action.target()), nullToEmpty(action.text()),
            nullToEmpty(action.instruction()), nullToEmpty(action.reason())
        ).toLowerCase(Locale.ROOT);

        if ("submit".equals(kind)) return true;
        return haystack.contains("submit")
            || haystack.contains("publish")
            || haystack.contains("post")
            || haystack.contains("delete")
            || haystack.contains("remove")
            || haystack.contains("purchase")
            || haystack.contains("buy")
            || haystack.contains("pay")
            || haystack.contains("transfer")
            || haystack.contains("checkout")
            || haystack.contains("支付")
            || haystack.contains("转账")
            || haystack.contains("删除")
            || haystack.contains("发布")
            || haystack.contains("购买")
            || haystack.contains("提交");
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("confirmed");
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
