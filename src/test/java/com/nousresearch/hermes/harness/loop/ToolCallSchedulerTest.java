package com.nousresearch.hermes.harness.loop;

import com.nousresearch.hermes.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallSchedulerTest {

    private ToolCall makeCall(String id, String name) {
        ToolCall tc = new ToolCall();
        tc.setId(id);
        tc.setType("function");
        ToolCall.Function fn = new ToolCall.Function();
        fn.setName(name);
        fn.setArguments("{}");
        tc.setFunction(fn);
        return tc;
    }

    @Test
    void singleCallExecutesSuccessfully() {
        var scheduler = new ToolCallScheduler();
        Function<ToolCall, String> executor = tc ->
            "{\"result\": \"ok for " + tc.getId() + "\"}";

        var results = scheduler.execute(List.of(makeCall("c1", "read")), executor);
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("c1", results.get(0).callId());
        assertEquals("read", results.get(0).toolName());
        scheduler.shutdown();
    }

    @Test
    void multipleParallelCallsAllComplete() {
        var scheduler = new ToolCallScheduler();
        var callIds = List.of("c1", "c2", "c3");
        var calls = new ArrayList<ToolCall>();
        for (String id : callIds) {
            calls.add(makeCall(id, "read"));
        }

        Function<ToolCall, String> executor = tc -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "{\"result\": \"" + tc.getId() + "\"}";
        };

        var results = scheduler.execute(calls, executor);
        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(results.get(i).success());
            assertEquals(callIds.get(i), results.get(i).callId());
        }
        scheduler.shutdown();
    }

    @Test
    void exclusiveCallsRunSerially() {
        var scheduler = new ToolCallScheduler();
        var concurrent = new AtomicInteger(0);
        var maxConcurrent = new AtomicInteger(0);

        Function<ToolCall, String> executor = tc -> {
            int current = concurrent.incrementAndGet();
            maxConcurrent.set(Math.max(maxConcurrent.get(), current));
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
            return "{\"result\": \"" + tc.getId() + "\"}";
        };

        // Two exclusive calls
        var calls = List.of(
            makeCall("c1", "file_write"),
            makeCall("c2", "file_write")
        );

        var results = scheduler.execute(calls, executor);
        assertEquals(2, results.size());
        assertEquals(1, maxConcurrent.get(), "Exclusive calls should not run concurrently");
        scheduler.shutdown();
    }

    @Test
    void mixedParallelAndExclusiveCalls() {
        var scheduler = new ToolCallScheduler();
        var concurrent = new AtomicInteger(0);
        var maxConcurrent = new AtomicInteger(0);

        Function<ToolCall, String> executor = tc -> {
            int current = concurrent.incrementAndGet();
            maxConcurrent.set(Math.max(maxConcurrent.get(), current));
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
            return "{\"result\": \"" + tc.getId() + "\"}";
        };

        // Two parallel + one exclusive + two parallel
        var calls = List.of(
            makeCall("c1", "read"),
            makeCall("c2", "grep"),
            makeCall("c3", "file_write"),
            makeCall("c4", "read"),
            makeCall("c5", "grep")
        );

        var results = scheduler.execute(calls, executor);
        assertEquals(5, results.size());

        // Results should be in original order
        assertEquals("c1", results.get(0).callId());
        assertEquals("c2", results.get(1).callId());
        assertEquals("c3", results.get(2).callId());
        assertEquals("c4", results.get(3).callId());
        assertEquals("c5", results.get(4).callId());

        // Exclusive call should not overlap with parallel calls
        // Max concurrent should be 2 (from the parallel batches), never more
        assertTrue(maxConcurrent.get() <= 2, "Max concurrent should be <= 2, got " + maxConcurrent.get());
        scheduler.shutdown();
    }

    @Test
    void toolExceptionReturnedAsFailure() {
        var scheduler = new ToolCallScheduler();
        Function<ToolCall, String> executor = tc -> {
            throw new RuntimeException("tool crashed");
        };

        var results = scheduler.execute(List.of(makeCall("c1", "read")), executor);
        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertEquals("tool crashed", results.get(0).content());
        scheduler.shutdown();
    }

    @Test
    void resultsInOriginalOrderRegardlessOfCompletion() {
        var scheduler = new ToolCallScheduler();
        // Make the first call slow, second fast - results should still be in order
        Function<ToolCall, String> executor = tc -> {
            if (tc.getId().equals("c1")) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return "{\"result\": \"" + tc.getId() + "\"}";
        };

        var calls = List.of(
            makeCall("c1", "read"),
            makeCall("c2", "read"),
            makeCall("c3", "read")
        );

        var results = scheduler.execute(calls, executor);
        assertEquals("c1", results.get(0).callId());
        assertEquals("c2", results.get(1).callId());
        assertEquals("c3", results.get(2).callId());
        scheduler.shutdown();
    }

    @Test
    void classifyToolReturnsParallelForReadOnlyTools() {
        var scheduler = new ToolCallScheduler();
        var parallelTools = List.of("read", "read_file", "grep", "glob", "list_files",
            "web_search", "web_fetch", "search", "ls", "cat", "head", "tail",
            "find", "wc", "memory_search", "memory_get");

        for (String name : parallelTools) {
            var tc = makeCall("id", name);
            assertEquals(ToolExecutionMode.PARALLEL, scheduler.classifyTool(tc),
                "Tool '" + name + "' should be PARALLEL");
        }
        scheduler.shutdown();
    }

    @Test
    void classifyToolReturnsExclusiveForWriteTools() {
        var scheduler = new ToolCallScheduler();
        var exclusiveTools = List.of("file_write", "code_exec", "bash", "write", "edit",
            "delete", "move", "mkdir", "rmdir", "git_commit");

        for (String name : exclusiveTools) {
            var tc = makeCall("id", name);
            assertEquals(ToolExecutionMode.EXCLUSIVE, scheduler.classifyTool(tc),
                "Tool '" + name + "' should be EXCLUSIVE");
        }
        scheduler.shutdown();
    }

    @Test
    void emptyCallListReturnsEmptyResults() {
        var scheduler = new ToolCallScheduler();
        Function<ToolCall, String> executor = tc -> "ok";

        var results = scheduler.execute(List.of(), executor);
        assertTrue(results.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void nullCallListReturnsEmptyResults() {
        var scheduler = new ToolCallScheduler();
        Function<ToolCall, String> executor = tc -> "ok";

        var results = scheduler.execute(null, executor);
        assertTrue(results.isEmpty());
        scheduler.shutdown();
    }
}
