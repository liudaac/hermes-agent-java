package com.nousresearch.hermes.harness.code;

/**
 * Result of code execution.
 */
public record CodeResult(
    String stdout,
    String stderr,
    String returnValue,
    int exitCode,
    long durationMs,
    boolean success
) {
    public static CodeResult success(String stdout, String returnValue, long durationMs) {
        return new CodeResult(stdout, "", returnValue, 0, durationMs, true);
    }

    public static CodeResult failure(String stderr, int exitCode, long durationMs) {
        return new CodeResult("", stderr, null, exitCode, durationMs, false);
    }

    /**
     * Format for model history: stdout + return value (not stderr).
     */
    public String toToolResult() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) {
            sb.append(stdout);
        }
        if (returnValue != null && !returnValue.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Result: ").append(returnValue);
        }
        if (!success) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Error: ").append(stderr);
        }
        return sb.toString();
    }
}
