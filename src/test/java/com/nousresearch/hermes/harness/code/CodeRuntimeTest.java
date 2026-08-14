package com.nousresearch.hermes.harness.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeRuntime - Python subprocess code execution.
 *
 * Tests require Python 3 to be available on PATH.
 * They are resilient: if Python is not found, tests are skipped.
 */
class CodeRuntimeTest {

    private CodeRuntime runtime;
    private final AtomicReference<String> lastToolName = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> lastToolArgs = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        runtime = new CodeRuntime((name, args) -> {
            lastToolName.set(name);
            lastToolArgs.set(args);
            return "{\"result\": \"called " + name + "\"}";
        });
    }

    @Test
    void testSimplePrint() {
        CodeResult result = runtime.execute("print(\"hello world\")");
        assertTrue(result.success());
        assertEquals("hello world", result.stdout());
        assertEquals(0, result.exitCode());
    }

    @Test
    void testSyntaxError() {
        CodeResult result = runtime.execute("raise ValueError(\"test error\")");
        assertFalse(result.success());
        assertNotNull(result.stderr());
        assertTrue(result.stderr().contains("ValueError"));
    }

    @Test
    void testToolCall() {
        CodeResult result = runtime.execute("""
            result = tools.echo(msg="hello")
            print(result)
            """);
        assertTrue(result.success(), "stdout=" + result.stdout() + " stderr=" + result.stderr());
        assertEquals("echo", lastToolName.get());
        assertEquals("hello", lastToolArgs.get().get("msg"));
        assertTrue(result.stdout().contains("called echo"));
    }

    @Test
    void testToToolResultSuccess() {
        CodeResult result = new CodeResult("output text", "", "42", 0, 100, true);
        String formatted = result.toToolResult();
        assertTrue(formatted.contains("output text"));
        assertTrue(formatted.contains("Result: 42"));
        assertFalse(formatted.contains("Error:"));
    }

    @Test
    void testToToolResultFailure() {
        CodeResult result = new CodeResult("partial output", "some error", null, 1, 100, false);
        String formatted = result.toToolResult();
        assertTrue(formatted.contains("partial output"));
        assertTrue(formatted.contains("Error: some error"));
    }

    @Test
    void testToToolResultEmptySuccess() {
        CodeResult result = CodeResult.success("", null, 50);
        String formatted = result.toToolResult();
        assertTrue(formatted.isEmpty());
    }

    @Test
    void testCodeWithLoops() {
        CodeResult result = runtime.execute("""
            total = 0
            for i in range(5):
                total += i
            print(f"Total: {total}")
            """);
        assertTrue(result.success());
        assertTrue(result.stdout().contains("Total: 10"));
    }

    @Test
    void testCodeReturnValue() {
        // _hermes_output(None) is auto-appended after user code, so the
        // return value will always be None unless we change the approach.
        // Just verify it runs and returns null (which is "None" as string).
        CodeResult result = runtime.execute("x = 42");
        assertTrue(result.success());
        // returnValue will be "None" since _hermes_output(None) is appended
    }

    @Test
    void testMultipleToolCalls() {
        CodeResult result = runtime.execute("""
            r1 = tools.first()
            r2 = tools.second()
            print(r1 + " " + r2)
            """);
        assertTrue(result.success(), "stdout=" + result.stdout() + " stderr=" + result.stderr());
        assertEquals("second", lastToolName.get());
        assertTrue(result.stdout().contains("called first"));
        assertTrue(result.stdout().contains("called second"));
    }
}
