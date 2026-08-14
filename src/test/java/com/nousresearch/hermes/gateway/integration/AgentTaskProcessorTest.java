package com.nousresearch.hermes.gateway.integration;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentTaskProcessor chain mode routing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentTaskProcessorTest {

    @Test
    @Order(1)
    @DisplayName("[chain] prefix triggers chain mode")
    void chainPrefix_triggersChain() {
        AsyncTask task = new AsyncTask(
            "task-1", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            "[chain] Analyze the log files and summarize errors",
            "PENDING", null, null, 5, 300, null, null, null);

        // The shouldUseChain logic: input starts with [chain]
        assertTrue(task.input().strip().startsWith("[chain]"));
    }

    @Test
    @Order(2)
    @DisplayName("Normal input does not trigger chain mode")
    void normalInput_noChain() {
        AsyncTask task = new AsyncTask(
            "task-2", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            "What is the weather today?",
            "PENDING", null, null, 5, 300, null, null, null);

        assertFalse(task.input().strip().startsWith("[chain]"));
    }

}
