package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.harness.AgentContext;
import com.nousresearch.hermes.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Schedules tool calls for parallel or exclusive execution.
 *
 * - PARALLEL tools (read-only: read, grep, glob, web_search, etc.) run concurrently
 * - EXCLUSIVE tools (write side-effects: file_write, code_exec, etc.) run serially
 * - Results are always returned in the original call order
 * - Approval system runs before scheduling (all calls pre-approved)
 */
public class ToolCallScheduler {
    private static final Logger logger = LoggerFactory.getLogger(ToolCallScheduler.class);

    private final int maxParallel;
    private final ExecutorService executor;

    /** Default set of read-only tools that can run in parallel */
    private static final Set<String> PARALLEL_TOOLS = Set.of(
        "read", "read_file", "grep", "glob", "list_files",
        "web_search", "web_fetch", "search",
        "ls", "cat", "head", "tail", "find", "wc",
        "memory_search", "memory_get"
    );

    public ToolCallScheduler() {
        this(3);
    }

    public ToolCallScheduler(int maxParallel) {
        this.maxParallel = Math.max(1, maxParallel);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Execute a list of tool calls, respecting PARALLEL/EXCLUSIVE modes.
     * Results are returned in the same order as the input calls.
     */
    public List<ToolCallResult> execute(List<ToolCall> calls, AgentContext ctx) {
        return execute(calls, ctx::executeToolCall);
    }

    /**
     * Execute a list of tool calls using a provided executor function.
     * This overload is primarily for testing.
     */
    public List<ToolCallResult> execute(List<ToolCall> calls, Function<ToolCall, String> executorFn) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        if (calls.size() == 1) {
            ToolCall tc = calls.get(0);
            return List.of(executeSingle(tc, executorFn));
        }

        // Classify each call
        List<CallEntry> entries = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall tc = calls.get(i);
            ToolExecutionMode mode = classifyTool(tc);
            entries.add(new CallEntry(i, tc, mode));
        }

        List<ToolCallResult> results = new ArrayList<>(Collections.nCopies(calls.size(), null));

        // Process in segments: consecutive PARALLEL calls run together,
        // EXCLUSIVE calls run alone as barriers
        int i = 0;
        while (i < entries.size()) {
            if (entries.get(i).mode == ToolExecutionMode.EXCLUSIVE) {
                // Run exclusive call alone
                CallEntry entry = entries.get(i);
                results.set(entry.index, executeSingle(entry.call, executorFn));
                i++;
            } else {
                // Collect consecutive PARALLEL calls
                List<CallEntry> batch = new ArrayList<>();
                while (i < entries.size() && entries.get(i).mode == ToolExecutionMode.PARALLEL) {
                    batch.add(entries.get(i));
                    i++;
                }

                if (batch.size() == 1) {
                    CallEntry entry = batch.get(0);
                    results.set(entry.index, executeSingle(entry.call, executorFn));
                } else {
                    // Run batch in parallel
                    List<Future<ToolCallResult>> futures = new ArrayList<>();
                    for (CallEntry entry : batch) {
                        futures.add(executor.submit(() -> executeSingle(entry.call, executorFn)));
                    }

                    for (int j = 0; j < batch.size(); j++) {
                        try {
                            results.set(batch.get(j).index, futures.get(j).get(60, TimeUnit.SECONDS));
                        } catch (TimeoutException e) {
                            futures.get(j).cancel(true);
                            CallEntry entry = batch.get(j);
                            results.set(entry.index, ToolCallResult.failure(
                                entry.call.getId(),
                                "Tool execution timed out (60s)",
                                60000,
                                entry.call.getFunction().getName()
                            ));
                        } catch (Exception e) {
                            CallEntry entry = batch.get(j);
                            results.set(entry.index, ToolCallResult.failure(
                                entry.call.getId(),
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                0,
                                entry.call.getFunction().getName()
                            ));
                        }
                    }
                }
            }
        }

        return results;
    }

    /**
     * Determine execution mode for a tool call.
     */
    ToolExecutionMode classifyTool(ToolCall tc) {
        if (tc == null || tc.getFunction() == null || tc.getFunction().getName() == null) {
            return ToolExecutionMode.EXCLUSIVE;
        }
        String name = tc.getFunction().getName().toLowerCase();
        if (PARALLEL_TOOLS.contains(name)) {
            return ToolExecutionMode.PARALLEL;
        }
        return ToolExecutionMode.EXCLUSIVE;
    }

    /**
     * Execute a single tool call.
     */
    private ToolCallResult executeSingle(ToolCall tc, Function<ToolCall, String> executorFn) {
        String toolName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
        long start = System.currentTimeMillis();
        try {
            String result = executorFn.apply(tc);
            long duration = System.currentTimeMillis() - start;
            boolean success = result != null && !result.contains("\"error\"");
            return ToolCallResult.success(tc.getId(), result, duration, toolName);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolCallResult.failure(tc.getId(),
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                duration, toolName);
        }
    }

    /**
     * Shutdown the executor.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    private record CallEntry(int index, ToolCall call, ToolExecutionMode mode) {}
}
