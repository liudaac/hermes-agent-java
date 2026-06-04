package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tenant.core.SharedBlackboard;
import com.nousresearch.hermes.tenant.core.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Blackboard tool — allows agents to read/write a tenant-scoped shared memory.
 *
 * <p>This enables multi-agent collaboration: sub-agents publish intermediate
 * results to the blackboard, and the main agent (or other sub-agents) can
 * read them to build a unified response.</p>
 */
public class BlackboardTool {

    private static final Logger logger = LoggerFactory.getLogger(BlackboardTool.class);

    private final TenantContext tenantContext;

    public BlackboardTool(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    /**
     * Register all blackboard actions with the tool registry.
     */
    public void register(ToolRegistry registry) {
        registry.register(writeEntry());
        registry.register(readEntry());
        registry.register(listEntry());
        registry.register(clearEntry());
    }

    private ToolEntry writeEntry() {
        return new ToolEntry.Builder()
            .name("blackboard_write")
            .toolset("blackboard")
            .schema(buildSchema(Map.of(
                "key", "string:Unique key for this entry",
                "value", "string:Content to store",
                "author", "string:Who is writing (e.g. sub-agent name)",
                "topic", "string:Logical namespace (default: default)",
                "ttl_minutes", "integer:TTL in minutes (default: 30)"
            ), new String[]{"key", "value", "author"}))
            .handler(args -> {
                String key = str(args, "key");
                String value = str(args, "value");
                String author = str(args, "author");
                String topic = strOr(args, "topic", "default");
                int ttl = intOr(args, "ttl_minutes", 30);
                if (key == null || value == null || author == null) {
                    return "Error: key, value, and author are required";
                }
                getBoard().write(key, value, author, topic, ttl * 60_000L);
                return "Written: " + key + " to topic " + topic;
            })
            .description("Write an entry to the tenant shared blackboard.")
            .emoji("📝")
            .build();
    }

    private ToolEntry readEntry() {
        return new ToolEntry.Builder()
            .name("blackboard_read")
            .toolset("blackboard")
            .schema(buildSchema(Map.of(
                "key", "string:Key to read",
                "topic", "string:Namespace (default: default)"
            ), new String[]{"key"}))
            .handler(args -> {
                String key = str(args, "key");
                String topic = strOr(args, "topic", "default");
                var opt = getBoard().read(key, topic);
                if (opt.isEmpty()) return "Not found: " + key + " in topic " + topic;
                var e = opt.get();
                return e.value + "\n[author=" + e.author + ", at=" + e.getInstant() + "]";
            })
            .description("Read a single entry from the tenant shared blackboard.")
            .emoji("📖")
            .build();
    }

    private ToolEntry listEntry() {
        return new ToolEntry.Builder()
            .name("blackboard_list")
            .toolset("blackboard")
            .schema(buildSchema(Map.of(
                "topic", "string:Namespace (default: default)"
            ), new String[]{}))
            .handler(args -> {
                String topic = strOr(args, "topic", "default");
                var entries = getBoard().list(topic);
                if (entries.isEmpty()) return "No entries in topic: " + topic;
                StringBuilder sb = new StringBuilder();
                sb.append("Entries in topic '").append(topic).append("':\n");
                for (var e : entries) {
                    sb.append("- [").append(e.author).append("] ")
                      .append(truncate(e.value, 120)).append("\n");
                }
                return sb.toString();
            })
            .description("List all entries in a blackboard topic.")
            .emoji("📋")
            .build();
    }

    private ToolEntry clearEntry() {
        return new ToolEntry.Builder()
            .name("blackboard_clear")
            .toolset("blackboard")
            .schema(buildSchema(Map.of(
                "topic", "string:Namespace to clear (omit = all)"
            ), new String[]{}))
            .handler(args -> {
                String topic = str(args, "topic");
                getBoard().clear(topic);
                return "Cleared " + (topic != null ? "topic: " + topic : "all topics");
            })
            .description("Clear entries from the blackboard.")
            .emoji("🗑️")
            .build();
    }

    // ------------------------------------------------------------------

    private SharedBlackboard getBoard() {
        return tenantContext.getSharedBlackboard();
    }

    private static Map<String, Object> buildSchema(Map<String, String> fields, String[] required) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        fields.forEach((k, v) -> {
            int idx = v.indexOf(':');
            String type = idx > 0 ? v.substring(0, idx) : "string";
            String desc = idx > 0 ? v.substring(idx + 1) : v;
            Map<String, String> field = new HashMap<>();
            field.put("type", type);
            field.put("description", desc);
            props.put(k, field);
        });
        params.put("properties", props);
        params.put("required", required);
        return params;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }

    private static String strOr(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v != null ? v.toString() : def;
    }

    private static int intOr(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v != null ? Integer.parseInt(v.toString()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
