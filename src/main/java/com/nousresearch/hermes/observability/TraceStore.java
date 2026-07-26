package com.nousresearch.hermes.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * F2: Trace store - in-memory ring buffer for execution traces.
 *
 * <p>Stores the last N traces (default 10,000) for query via API.
 * Falls back gracefully when memory is constrained.</p>
 *
 * <p>API endpoints (exposed via DashboardServer):</p>
 * <ul>
 *   <li>GET /api/v1/traces/{traceId} - get trace by ID</li>
 *   <li>GET /api/v1/traces?tenantId=&agentId=&limit= - list traces</li>
 * </ul>
 */
public class TraceStore {

    private static final Logger logger = LoggerFactory.getLogger(TraceStore.class);
    private static final int DEFAULT_MAX_TRACES = 10_000;

    private final Deque<ExecutionTrace> traces;
    private final Map<String, ExecutionTrace> traceIndex;
    private final int maxTraces;

    public TraceStore() {
        this(DEFAULT_MAX_TRACES);
    }

    public TraceStore(int maxTraces) {
        this.maxTraces = maxTraces;
        this.traces = new ConcurrentLinkedDeque<>();
        this.traceIndex = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /**
     * Store a trace.
     */
    public void store(ExecutionTrace trace) {
        traces.addFirst(trace);
        traceIndex.put(trace.getTraceId(), trace);

        // Evict oldest if over limit
        while (traces.size() > maxTraces) {
            ExecutionTrace evicted = traces.removeLast();
            if (evicted != null) {
                traceIndex.remove(evicted.getTraceId());
            }
        }
    }

    /**
     * Get a trace by ID.
     */
    public ExecutionTrace get(String traceId) {
        return traceIndex.get(traceId);
    }

    /**
     * List traces with optional filters.
     */
    public List<ExecutionTrace> list(String tenantId, String agentId, int limit) {
        int max = Math.min(limit, 100);
        return traces.stream()
            .filter(t -> tenantId == null || tenantId.equals(t.getTenantId()))
            .filter(t -> agentId == null || agentId.equals(t.getAgentId()))
            .limit(max)
            .collect(Collectors.toList());
    }

    /**
     * Get trace count.
     */
    public int size() {
        return traces.size();
    }

    /**
     * Clear all traces.
     */
    public void clear() {
        traces.clear();
        traceIndex.clear();
    }
}
