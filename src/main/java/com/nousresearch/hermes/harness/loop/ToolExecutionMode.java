package com.nousresearch.hermes.harness.loop;

/**
 * Execution mode for a tool call.
 */
public enum ToolExecutionMode {
    /** Can run in parallel with other PARALLEL tools */
    PARALLEL,
    /** Must run exclusively (serialized with other tools) */
    EXCLUSIVE
}
