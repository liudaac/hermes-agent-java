package com.nousresearch.hermes.org.observe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * S2-2: 轻量级 Tracer — 创建和管理 Span，支持父子关系 + 导出。
 *
 * <p>不依赖 OpenTelemetry SDK，自建轻量实现。未来可替换为 OTEL SDK。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * Span span = tracer.startSpan("gateway.request");
 * span.event(Span.EVENT_HOOK_PRE);
 * span.attr("tenant", "acme");
 * span.end();
 * tracer.endSpan(span);
 * }</pre>
 */
public class Tracer {
    private static final Logger logger = LoggerFactory.getLogger(Tracer.class);

    private final List<TraceExporter> exporters;
    private final ConcurrentLinkedQueue<Span> completedSpans = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Deque<String>> activeSpanStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<String> currentTraceId = new ThreadLocal<>();

    public Tracer() {
        this.exporters = new CopyOnWriteArrayList<>();
        this.exporters.add(new StdoutTraceExporter());
    }

    public Tracer(List<TraceExporter> exporters) {
        this.exporters = exporters != null ? new CopyOnWriteArrayList<>(exporters) : new CopyOnWriteArrayList<>();
    }

    /**
     * 开始一个新的根 span（新 traceId）。
     */
    public Span.Builder startSpan(String name) {
        return startSpan(name, Span.generateTraceId(), null);
    }

    /**
     * 开始一个子 span（继承当前线程的 traceId 和 parentSpanId）。
     */
    public Span.Builder startChildSpan(String name) {
        String traceId = currentTraceId.get();
        if (traceId == null) traceId = Span.generateTraceId();
        String parentSpanId = peekActiveSpan();
        return startSpan(name, traceId, parentSpanId);
    }

    /**
     * 开始一个 span（指定 traceId 和 parent）。
     */
    public Span.Builder startSpan(String name, String traceId, String parentSpanId) {
        String spanId = Span.generateSpanId();
        currentTraceId.set(traceId);
        return Span.builder(spanId, traceId, name).parent(parentSpanId);
    }

    /**
     * 完成 span（导出到所有 exporter）。
     */
    public void endSpan(Span span) {
        if (span == null) return;
        completedSpans.add(span);
        for (TraceExporter exporter : exporters) {
            try {
                exporter.export(span);
            } catch (Exception e) {
                logger.warn("Trace exporter failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 推入活跃 span 栈（用于自动建立父子关系）。
     */
    public void pushActiveSpan(String spanId) {
        activeSpanStack.get().push(spanId);
    }

    /**
     * 弹出活跃 span 栈。
     */
    public String popActiveSpan() {
        Deque<String> stack = activeSpanStack.get();
        String spanId = stack.poll();
        if (stack.isEmpty()) {
            currentTraceId.remove();
        }
        return spanId;
    }

    /**
     * 查看当前活跃 span（不弹出）。
     */
    public String peekActiveSpan() {
        return activeSpanStack.get().peek();
    }

    /**
     * 获取当前线程的 traceId。
     */
    public String currentTraceId() {
        return currentTraceId.get();
    }

    /**
     * 清除线程局部变量（请求结束时调用）。
     */
    public void clearContext() {
        activeSpanStack.get().clear();
        currentTraceId.remove();
    }

    /**
     * 获取所有已完成的 span（用于测试和调试）。
     */
    public List<Span> getCompletedSpans() {
        return new ArrayList<>(completedSpans);
    }

    /**
     * 清空已完成的 span。
     */
    public void clearCompletedSpans() {
        completedSpans.clear();
    }

    /**
     * 添加 exporter。
     */
    public void addExporter(TraceExporter exporter) {
        exporters.add(exporter);
    }
}
