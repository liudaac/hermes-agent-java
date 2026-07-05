package com.nousresearch.hermes.org.observe;

/**
 * S2-2: Trace 导出器接口。
 */
public interface TraceExporter {

    /**
     * 导出一个完成的 span。
     */
    void export(Span span);
}
