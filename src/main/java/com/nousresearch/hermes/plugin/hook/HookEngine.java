package com.nousresearch.hermes.plugin.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central hook engine. Collects callbacks per hook type, invokes them with
 * isolated exception handling so a misbehaving plugin cannot break the core loop.
 *
 * Mirrors Python PluginManager.invoke_hook.
 */
public class HookEngine {
    private static final Logger logger = LoggerFactory.getLogger(HookEngine.class);
    private final Map<HookType, List<HookCallback>> hooks = new ConcurrentHashMap<>();

    public void register(HookType type, HookCallback callback) {
        hooks.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(callback);
        logger.debug("Registered hook: {}", type);
    }

    /**
     * Invoke all callbacks for a hook type. Returns list of non-null return values.
     */
    public List<Object> invoke(HookType type, Map<String, Object> context) {
        List<HookCallback> callbacks = hooks.getOrDefault(type, List.of());
        List<Object> results = new ArrayList<>();
        for (HookCallback cb : callbacks) {
            try {
                Object ret = cb.invoke(context);
                if (ret != null) {
                    results.add(ret);
                }
            } catch (Exception e) {
                logger.warn("Hook '{}' callback {} raised: {}",
                        type, cb.getClass().getName(), e.getMessage());
            }
        }
        return results;
    }

    /**
     * Check if a tool call should be blocked by pre_tool_call hooks.
     *
     * @return block message if blocked, empty if allowed
     */
    public Optional<String> checkToolBlocked(String toolName, Map<String, Object> args,
                                             String taskId, String sessionId, String toolCallId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("tool_name", toolName);
        ctx.put("args", args != null ? args : Map.of());
        ctx.put("task_id", taskId);
        ctx.put("session_id", sessionId);
        ctx.put("tool_call_id", toolCallId);

        List<Object> results = invoke(HookType.PRE_TOOL_CALL, ctx);
        for (Object r : results) {
            if (r instanceof Map<?, ?> m) {
                if ("block".equals(m.get("action"))) {
                    Object msg = m.get("message");
                    if (msg instanceof String s && !s.isEmpty()) {
                        return Optional.of(s);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Check pre_gateway_dispatch for skip/rewrite directives.
     *
     * @return action result: allow, skip, or rewrite with new text
     */
    public DispatchAction checkGatewayDispatch(Object event, Object gateway, Object sessionStore) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("event", event);
        ctx.put("gateway", gateway);
        ctx.put("session_store", sessionStore);

        List<Object> results = invoke(HookType.PRE_GATEWAY_DISPATCH, ctx);
        for (Object r : results) {
            if (r instanceof Map<?, ?> m) {
                String action = Objects.toString(m.get("action"), "");
                return switch (action) {
                    case "skip" -> DispatchAction.skip(Objects.toString(m.get("reason"), ""));
                    case "rewrite" -> DispatchAction.rewrite(Objects.toString(m.get("text"), ""));
                    default -> DispatchAction.allow();
                };
            }
        }
        return DispatchAction.allow();
    }

    public boolean hasHooks(HookType type) {
        return hooks.containsKey(type) && !hooks.get(type).isEmpty();
    }

    /**
     * Result type for gateway dispatch hook checks.
     */
    public record DispatchAction(String action, String text, String reason) {
        public static DispatchAction allow() {
            return new DispatchAction("allow", null, null);
        }
        public static DispatchAction skip(String reason) {
            return new DispatchAction("skip", null, reason);
        }
        public static DispatchAction rewrite(String text) {
            return new DispatchAction("rewrite", text, null);
        }
        public boolean isAllow() { return "allow".equals(action); }
        public boolean isSkip() { return "skip".equals(action); }
        public boolean isRewrite() { return "rewrite".equals(action); }
    }
}
