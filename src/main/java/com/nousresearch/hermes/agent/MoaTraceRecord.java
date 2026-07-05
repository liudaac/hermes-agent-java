package com.nousresearch.hermes.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * S2-3: MOA（Multi-agent Orchestrated Agent）trace 记录。
 *
 * <p>每 turn 结束写一行 JSONL，记录这一轮的完整信息。
 * 多 agent 写入同一 trace 文件，通过 agentId 区分。</p>
 *
 * <p>字段对齐计划要求：turnId / role / model / tokens / durationMs / toolCalls[]</p>
 *
 * <p>S2 阶段（最小版）：单 agent + 基础字段。
 * S4-1 阶段（完整）：补齐 SubAgent 协调点 + 多智能体全回合。</p>
 */
public record MoaTraceRecord(
    String traceId,          // 链路 ID（对齐 S2-2 OTEL traceId）
    String turnId,           // 回合 ID（唯一标识这一轮）
    String agentId,          // agent 标识
    String role,             // agent / llm / tool / subagent
    String model,            // 使用的模型
    long tokens,             // token 用量
    long durationMs,         // 这一轮耗时
    List<String> toolCalls,  // 调用的工具列表
    Instant timestamp,       // 时间戳
    Map<String, Object> metadata  // 扩展字段
) {
    public static Builder builder(String traceId, String turnId) {
        return new Builder(traceId, turnId);
    }

    public static final class Builder {
        private final String traceId;
        private final String turnId;
        private String agentId = "default";
        private String role = "agent";
        private String model;
        private long tokens;
        private long durationMs;
        private List<String> toolCalls = List.of();
        private Instant timestamp = Instant.now();
        private Map<String, Object> metadata = Map.of();

        Builder(String traceId, String turnId) {
            this.traceId = traceId;
            this.turnId = turnId;
        }

        public Builder agentId(String a) { this.agentId = a; return this; }
        public Builder role(String r) { this.role = r; return this; }
        public Builder model(String m) { this.model = m; return this; }
        public Builder tokens(long t) { this.tokens = t; return this; }
        public Builder durationMs(long d) { this.durationMs = d; return this; }
        public Builder toolCalls(List<String> t) { this.toolCalls = t != null ? t : List.of(); return this; }
        public Builder metadata(Map<String, Object> m) { this.metadata = m != null ? m : Map.of(); return this; }

        public MoaTraceRecord build() {
            return new MoaTraceRecord(traceId, turnId, agentId, role, model,
                tokens, durationMs, List.copyOf(toolCalls), timestamp, Map.copyOf(metadata));
        }
    }
}
