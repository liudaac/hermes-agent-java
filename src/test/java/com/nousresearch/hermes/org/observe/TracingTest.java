package com.nousresearch.hermes.org.observe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2-2: OTEL 骨架测试
 */
class TracingTest {

    // ========================================================================
    // Span 值对象
    // ========================================================================

    @Nested
    @DisplayName("Span 值对象")
    class SpanTest {

        @Test
        @DisplayName("generateTraceId 生成 32 字符 hex")
        void traceIdFormat() {
            String id = Span.generateTraceId();
            assertEquals(32, id.length());
            assertTrue(id.matches("[0-9a-f]{32}"));
        }

        @Test
        @DisplayName("generateSpanId 生成 16 字符 hex")
        void spanIdFormat() {
            String id = Span.generateSpanId();
            assertEquals(16, id.length());
            assertTrue(id.matches("[0-9a-f]{16}"));
        }

        @Test
        @DisplayName("generateTraceId 唯一性")
        void traceIdUnique() {
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 1000; i++) ids.add(Span.generateTraceId());
            assertEquals(1000, ids.size());
        }

        @Test
        @DisplayName("Builder 基本构建")
        void builderBasic() {
            Span span = Span.builder("span-1", "trace-1", Span.SPAN_GATEWAY_REQUEST)
                .attr("tenant", "acme")
                .attr("duration", 100L)
                .event(Span.EVENT_HOOK_PRE)
                .status(Span.SpanStatus.OK)
                .end()
                .build();

            assertEquals("span-1", span.spanId());
            assertEquals("trace-1", span.traceId());
            assertEquals(Span.SPAN_GATEWAY_REQUEST, span.name());
            assertEquals(Span.SpanStatus.OK, span.status());
            assertEquals("acme", span.attributes().get("tenant"));
            assertEquals("100", span.attributes().get("duration"));
            assertEquals(1, span.events().size());
            assertEquals(Span.EVENT_HOOK_PRE, span.events().get(0).name());
        }

        @Test
        @DisplayName("Builder 带 parent")
        void builderWithParent() {
            Span span = Span.builder("child", "trace-1", Span.SPAN_TOOL_CALL)
                .parent("parent-span")
                .build();
            assertEquals("parent-span", span.parentSpanId());
        }

        @Test
        @DisplayName("durationMs 计算耗时")
        void durationMs() throws Exception {
            Span.Builder builder = Span.builder("s1", "t1", "test");
            Thread.sleep(10);
            Span span = builder.end().build();
            assertTrue(span.durationMs() >= 10);
        }

        @Test
        @DisplayName("5 类 span 常量")
        void spanTypeConstants() {
            assertEquals("gateway.request", Span.SPAN_GATEWAY_REQUEST);
            assertEquals("agent.turn", Span.SPAN_AGENT_TURN);
            assertEquals("llm.call", Span.SPAN_LLM_CALL);
            assertEquals("tool.call", Span.SPAN_TOOL_CALL);
            assertEquals("subagent.spawn", Span.SPAN_SUBAGENT_SPAWN);
        }

        @Test
        @DisplayName("8 关卡 event 常量")
        void eventConstants() {
            assertEquals("hook.pre_tool_call", Span.EVENT_HOOK_PRE);
            assertEquals("tool.check_permission", Span.EVENT_CHECK_PERMISSION);
            assertEquals("tool.prelude", Span.EVENT_TOOL_PRELUDE);
            assertEquals("tool.approval", Span.EVENT_APPROVAL);
            assertEquals("tool.negotiator", Span.EVENT_NEGOTIATOR);
            assertEquals("tool.dispatch", Span.EVENT_DISPATCH);
            assertEquals("hook.post_tool_call", Span.EVENT_HOOK_POST);
            assertEquals("hook.transform_result", Span.EVENT_HOOK_TRANSFORM);
        }

        @Test
        @DisplayName("event 带 attributes")
        void eventWithAttrs() {
            Span span = Span.builder("s1", "t1", "test")
                .event(Span.EVENT_APPROVAL, Map.of("approvalId", "ap-1", "approved", "true"))
                .build();
            assertEquals(1, span.events().size());
            assertEquals("ap-1", span.events().get(0).attributes().get("approvalId"));
        }

        @Test
        @DisplayName("多个 event 按顺序")
        void multipleEvents() {
            Span span = Span.builder("s1", "t1", Span.SPAN_TOOL_CALL)
                .event(Span.EVENT_HOOK_PRE)
                .event(Span.EVENT_CHECK_PERMISSION)
                .event(Span.EVENT_TOOL_PRELUDE)
                .event(Span.EVENT_APPROVAL)
                .event(Span.EVENT_NEGOTIATOR)
                .event(Span.EVENT_DISPATCH)
                .event(Span.EVENT_HOOK_POST)
                .event(Span.EVENT_HOOK_TRANSFORM)
                .build();
            assertEquals(8, span.events().size());
            // 验证顺序
            assertEquals(Span.EVENT_HOOK_PRE, span.events().get(0).name());
            assertEquals(Span.EVENT_HOOK_TRANSFORM, span.events().get(7).name());
        }
    }

    // ========================================================================
    // Tracer — 父子 span + 导出
    // ========================================================================

    @Nested
    @DisplayName("Tracer")
    class TracerTest {

        private Tracer tracer;

        @BeforeEach
        void setUp() {
            tracer = new Tracer(List.of()); // 不用 stdout exporter，避免日志噪音
        }

        @Test
        @DisplayName("startSpan 创建根 span，带新 traceId")
        void startRootSpan() {
            Span.Builder builder = tracer.startSpan(Span.SPAN_GATEWAY_REQUEST);
            Span span = builder.end().build();
            assertNotNull(span.traceId());
            assertEquals(32, span.traceId().length());
            assertNull(span.parentSpanId());
            tracer.endSpan(span);
        }

        @Test
        @DisplayName("startChildSpan 继承 traceId + parentSpanId")
        void childSpanInherits() {
            Span root = tracer.startSpan(Span.SPAN_GATEWAY_REQUEST).end().build();
            tracer.pushActiveSpan(root.spanId());
            tracer.endSpan(root);

            Span child = tracer.startChildSpan(Span.SPAN_AGENT_TURN).end().build();
            assertEquals(root.traceId(), child.traceId());
            assertEquals(root.spanId(), child.parentSpanId());
            tracer.popActiveSpan();
            tracer.endSpan(child);
        }

        @Test
        @DisplayName("多级子 span 链")
        void multiLevelChain() {
            Span root = tracer.startSpan(Span.SPAN_GATEWAY_REQUEST).end().build();
            tracer.pushActiveSpan(root.spanId());
            tracer.endSpan(root);

            Span turn = tracer.startChildSpan(Span.SPAN_AGENT_TURN).end().build();
            tracer.pushActiveSpan(turn.spanId());
            tracer.endSpan(turn);

            Span llm = tracer.startChildSpan(Span.SPAN_LLM_CALL).end().build();
            tracer.pushActiveSpan(llm.spanId());
            tracer.endSpan(llm);

            Span tool = tracer.startChildSpan(Span.SPAN_TOOL_CALL).end().build();
            tracer.endSpan(tool);

            tracer.popActiveSpan(); // llm
            tracer.popActiveSpan(); // turn
            tracer.popActiveSpan(); // root

            // 所有 span 共享同一 traceId
            List<Span> completed = tracer.getCompletedSpans();
            assertEquals(4, completed.size());
            for (Span s : completed) {
                assertEquals(root.traceId(), s.traceId());
            }

            // 父子链
            assertEquals(root.spanId(), turn.parentSpanId());
            assertEquals(turn.spanId(), llm.parentSpanId());
            assertEquals(llm.spanId(), tool.parentSpanId());
        }

        @Test
        @DisplayName("endSpan 导出到 exporter")
        void endSpanExports() {
            List<Span> exported = new ArrayList<>();
            Tracer t = new Tracer(List.of(exported::add));
            Span span = t.startSpan("test").end().build();
            t.endSpan(span);
            assertEquals(1, exported.size());
            assertEquals(span, exported.get(0));
        }

        @Test
        @DisplayName("clearContext 清除线程局部变量")
        void clearContext() {
            Span root = tracer.startSpan("root").end().build();
            tracer.pushActiveSpan(root.spanId());
            tracer.endSpan(root);

            assertNotNull(tracer.currentTraceId());
            assertNotNull(tracer.peekActiveSpan());

            tracer.clearContext();

            assertNull(tracer.currentTraceId());
            assertNull(tracer.peekActiveSpan());
        }

        @Test
        @DisplayName("getCompletedSpans 返回所有已完成 span")
        void completedSpans() {
            for (int i = 0; i < 5; i++) {
                Span s = tracer.startSpan("span-" + i).end().build();
                tracer.endSpan(s);
                tracer.clearContext();
            }
            assertEquals(5, tracer.getCompletedSpans().size());
        }

        @Test
        @DisplayName("addExporter 动态添加")
        void addExporter() {
            List<Span> exported = new ArrayList<>();
            tracer.addExporter(exported::add);
            Span s = tracer.startSpan("test").end().build();
            tracer.endSpan(s);
            assertEquals(1, exported.size());
        }
    }

    // ========================================================================
    // StdoutTraceExporter
    // ========================================================================

    @Nested
    @DisplayName("StdoutTraceExporter")
    class StdoutExporterTest {

        @Test
        @DisplayName("enabled=true 时不崩溃")
        void enabledNoCrash() {
            StdoutTraceExporter exporter = new StdoutTraceExporter(true);
            Span span = Span.builder("s1", "t1", "test")
                .attr("key", "val")
                .event("evt")
                .status(Span.SpanStatus.OK)
                .end()
                .build();
            assertDoesNotThrow(() -> exporter.export(span));
        }

        @Test
        @DisplayName("enabled=false 时不输出")
        void disabledNoOutput() {
            StdoutTraceExporter exporter = new StdoutTraceExporter(false);
            Span span = Span.builder("s1", "t1", "test").build();
            assertDoesNotThrow(() -> exporter.export(span));
        }
    }

    // ========================================================================
    // OtlpTraceExporter
    // ========================================================================

    @Nested
    @DisplayName("OtlpTraceExporter")
    class OtlpExporterTest {

        @Test
        @DisplayName("无 endpoint → disabled")
        void noEndpoint() {
            OtlpTraceExporter exporter = new OtlpTraceExporter(null);
            assertFalse(exporter.isEnabled());
        }

        @Test
        @DisplayName("有 endpoint → enabled")
        void withEndpoint() {
            OtlpTraceExporter exporter = new OtlpTraceExporter("http://localhost:4317");
            assertTrue(exporter.isEnabled());
            assertEquals("http://localhost:4317", exporter.getEndpoint());
        }

        @Test
        @DisplayName("disabled 时不崩溃")
        void disabledNoOp() {
            OtlpTraceExporter exporter = new OtlpTraceExporter(null);
            Span span = Span.builder("s1", "t1", "test").build();
            assertDoesNotThrow(() -> exporter.export(span));
        }
    }

    // ========================================================================
    // 全链路模拟（gateway → agent → llm → tool）
    // ========================================================================

    @Nested
    @DisplayName("全链路模拟")
    class FullTraceTest {

        @Test
        @DisplayName("gateway.request → agent.turn → llm.call → tool.call 一根 traceId")
        void fullChain() {
            List<Span> exported = new ArrayList<>();
            Tracer tracer = new Tracer(List.of(exported::add));

            // gateway.request（根 span）
            Span gateway = tracer.startSpan(Span.SPAN_GATEWAY_REQUEST)
                .attr("tenant", "acme")
                .attr("channel", "feishu")
                .end().build();
            tracer.pushActiveSpan(gateway.spanId());
            tracer.endSpan(gateway);

            // agent.turn（子 span）
            Span turn = tracer.startChildSpan(Span.SPAN_AGENT_TURN)
                .attr("agentId", "agent-1")
                .attr("turn", "1")
                .end().build();
            tracer.pushActiveSpan(turn.spanId());
            tracer.endSpan(turn);

            // llm.call（孙 span）
            Span llm = tracer.startChildSpan(Span.SPAN_LLM_CALL)
                .attr("model", "claude-3.5-sonnet")
                .attr("tokens", "1500")
                .end().build();
            tracer.pushActiveSpan(llm.spanId());
            tracer.endSpan(llm);

            // tool.call（曾孙 span，含 8 关卡 events）
            Span tool = tracer.startChildSpan(Span.SPAN_TOOL_CALL)
                .attr("tool", "web_search")
                .event(Span.EVENT_HOOK_PRE)
                .event(Span.EVENT_CHECK_PERMISSION)
                .event(Span.EVENT_TOOL_PRELUDE)
                .event(Span.EVENT_APPROVAL, Map.of("approved", "true"))
                .event(Span.EVENT_DISPATCH)
                .event(Span.EVENT_HOOK_POST)
                .event(Span.EVENT_HOOK_TRANSFORM)
                .status(Span.SpanStatus.OK)
                .end().build();
            tracer.endSpan(tool);

            tracer.popActiveSpan(); // llm
            tracer.popActiveSpan(); // turn
            tracer.popActiveSpan(); // gateway
            tracer.clearContext();

            // 验证：4 个 span，同一 traceId
            assertEquals(4, exported.size());
            String traceId = exported.get(0).traceId();
            for (Span s : exported) {
                assertEquals(traceId, s.traceId(), "All spans should share traceId");
            }

            // 验证 span 名称
            assertEquals(Span.SPAN_GATEWAY_REQUEST, exported.get(0).name());
            assertEquals(Span.SPAN_AGENT_TURN, exported.get(1).name());
            assertEquals(Span.SPAN_LLM_CALL, exported.get(2).name());
            assertEquals(Span.SPAN_TOOL_CALL, exported.get(3).name());

            // 验证父子链
            assertEquals(exported.get(0).spanId(), exported.get(1).parentSpanId());
            assertEquals(exported.get(1).spanId(), exported.get(2).parentSpanId());
            assertEquals(exported.get(2).spanId(), exported.get(3).parentSpanId());

            // 验证 tool span 有 8 关卡 events（这里 7 个，negotiator 跳过了）
            assertEquals(7, exported.get(3).events().size());
        }

        @Test
        @DisplayName("subagent.spawn 也共享 traceId")
        void subagentSharesTrace() {
            List<Span> exported = new ArrayList<>();
            Tracer tracer = new Tracer(List.of(exported::add));

            Span parent = tracer.startSpan(Span.SPAN_AGENT_TURN).end().build();
            tracer.pushActiveSpan(parent.spanId());
            tracer.endSpan(parent);

            Span subagent = tracer.startChildSpan(Span.SPAN_SUBAGENT_SPAWN)
                .attr("subagentId", "sub-1")
                .attr("task", "search and summarize")
                .end().build();
            tracer.endSpan(subagent);

            tracer.popActiveSpan();
            tracer.clearContext();

            assertEquals(2, exported.size());
            assertEquals(exported.get(0).traceId(), exported.get(1).traceId());
            assertEquals(exported.get(0).spanId(), exported.get(1).parentSpanId());
        }
    }
}
