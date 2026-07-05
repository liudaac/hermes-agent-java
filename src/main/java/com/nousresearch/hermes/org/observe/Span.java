package com.nousresearch.hermes.org.observe;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * S2-2: 轻量级 Span — 一段可观测的操作。
 *
 * <p>5 类 span 类型（对齐计划）：</p>
 * <ul>
 *   <li>{@code gateway.request} — 网关接收请求</li>
 *   <li>{@code agent.turn} — agent 一轮思考+行动</li>
 *   <li>{@code llm.call} — LLM 调用</li>
 *   <li>{@code tool.call} — 工具调用</li>
 *   <li>{@code subagent.spawn} — 子 agent 生成</li>
 * </ul>
 *
 * <p>8 关卡 event（TenantAwareToolDispatcher 的 8 个检查点，不变成 span 避免爆炸）：</p>
 * <ul>
 *   <li>{@code hook.pre_tool_call} — HookEngine 前置钩子</li>
 *   <li>{@code tool.check_permission} — 权限检查</li>
 *   <li>{@code tool.prelude} — ToolCallPrelude（LLM 自检 + dry-run）</li>
 *   <li>{@code tool.approval} — 审批系统</li>
 *   <li>{@code tool.negotiator} — 高风险协商</li>
 *   <li>{@code tool.dispatch} — 真分发到沙箱</li>
 *   <li>{@code hook.post_tool_call} — HookEngine 后置钩子</li>
 *   <li>{@code hook.transform_result} — 结果脱敏/改写</li>
 * </ul>
 */
public record Span(
    String spanId,
    String traceId,
    String parentSpanId,
    String name,
    Instant startTime,
    Instant endTime,
    Map<String, String> attributes,
    List<SpanEvent> events,
    SpanStatus status
) {
    /** Span 状态 */
    public enum SpanStatus { OK, ERROR, UNSET }

    /** Span 事件（8 关卡用 event 不用 span） */
    public record SpanEvent(String name, Instant timestamp, Map<String, String> attributes) {}

    // ============ 5 类 span 名称常量 ============
    public static final String SPAN_GATEWAY_REQUEST = "gateway.request";
    public static final String SPAN_AGENT_TURN = "agent.turn";
    public static final String SPAN_LLM_CALL = "llm.call";
    public static final String SPAN_TOOL_CALL = "tool.call";
    public static final String SPAN_SUBAGENT_SPAWN = "subagent.spawn";

    // ============ 8 关卡 event 名称常量 ============
    public static final String EVENT_HOOK_PRE = "hook.pre_tool_call";
    public static final String EVENT_CHECK_PERMISSION = "tool.check_permission";
    public static final String EVENT_TOOL_PRELUDE = "tool.prelude";
    public static final String EVENT_APPROVAL = "tool.approval";
    public static final String EVENT_NEGOTIATOR = "tool.negotiator";
    public static final String EVENT_DISPATCH = "tool.dispatch";
    public static final String EVENT_HOOK_POST = "hook.post_tool_call";
    public static final String EVENT_HOOK_TRANSFORM = "hook.transform_result";

    /** 生成随机 spanId（16 字符 hex） */
    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 生成随机 traceId（32 字符 hex） */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 计算耗时（毫秒） */
    public long durationMs() {
        if (endTime == null) return 0;
        return Duration.between(startTime, endTime).toMillis();
    }

    /** Builder */
    public static Builder builder(String spanId, String traceId, String name) {
        return new Builder(spanId, traceId, name);
    }

    public static class Builder {
        private final String spanId;
        private final String traceId;
        private String parentSpanId;
        private final String name;
        private final Instant startTime = Instant.now();
        private Instant endTime;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<SpanEvent> events = new ArrayList<>();
        private SpanStatus status = SpanStatus.UNSET;

        Builder(String spanId, String traceId, String name) {
            this.spanId = spanId;
            this.traceId = traceId;
            this.name = name;
        }

        public Builder parent(String parentSpanId) { this.parentSpanId = parentSpanId; return this; }
        public Builder attr(String key, String value) { attributes.put(key, value); return this; }
        public Builder attr(String key, long value) { attributes.put(key, String.valueOf(value)); return this; }
        public Builder event(String name) { events.add(new SpanEvent(name, Instant.now(), Map.of())); return this; }
        public Builder event(String name, Map<String, String> attrs) {
            events.add(new SpanEvent(name, Instant.now(), attrs != null ? attrs : Map.of()));
            return this;
        }
        public Builder status(SpanStatus status) { this.status = status; return this; }
        public Builder end() { this.endTime = Instant.now(); return this; }
        public Builder end(Instant endTime) { this.endTime = endTime; return this; }

        public Span build() {
            return new Span(spanId, traceId, parentSpanId, name, startTime,
                endTime != null ? endTime : Instant.now(),
                Map.copyOf(attributes), List.copyOf(events), status);
        }
    }
}
