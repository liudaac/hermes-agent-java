package com.nousresearch.hermes.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Collects {@link CognitiveTrace} entries during a session.
 *
 * <p>Traces are kept in memory (bounded) and optionally flushed to a JSONL
 * file on disk. They never enter the LLM context window, so there is zero
 * token overhead.</p>
 *
 * <p>The collector is designed to be cheap to call from hot paths:</p>
 * <ul>
 *   <li>Observe: user message arrives</li>
 *   <li>Orient: before LLM call (goal + hypothesis derived from conversation)</li>
 *   <li>Decide: LLM returns tool calls or text</li>
 *   <li>Act: tool result or response sent</li>
 *   <li>Evaluate: tool result parsed / user follow-up received</li>
 * </ul>
 */
public class CognitiveTraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(CognitiveTraceCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Keep last N traces in memory (bounded to avoid leaking across long sessions)
    private static final int MAX_IN_MEMORY = 200;

    private final String sessionId;
    private final Path jsonlPath;
    private final ConcurrentLinkedDeque<CognitiveTrace> traces = new ConcurrentLinkedDeque<>();
    private volatile boolean closed;

    public CognitiveTraceCollector(String sessionId, Path dataDir) {
        this.sessionId = sessionId;
        this.jsonlPath = dataDir.resolve("traces_" + sanitize(sessionId) + ".jsonl");
    }

    /**
     * Append a trace. Thread-safe.
     */
    public void append(CognitiveTrace trace) {
        if (closed || trace == null) return;
        traces.addLast(trace);
        if (traces.size() > MAX_IN_MEMORY) {
            traces.pollFirst(); // drop oldest
        }
        // Best-effort flush to disk
        flushOne(trace);
    }

    /**
     * Quick OBSERVE trace for a user message.
     */
    public void observe(int turn, String userMessage) {
        append(CognitiveTrace.builder(turn, CognitiveTrace.Phase.OBSERVE)
            .observation(truncate(userMessage, 300))
            .build());
    }

    /**
     * Quick ORIENT trace before LLM call.
     */
    public void orient(int turn, String goal, String hypothesis) {
        append(CognitiveTrace.builder(turn, CognitiveTrace.Phase.ORIENT)
            .goal(truncate(goal, 200))
            .hypothesis(truncate(hypothesis, 300))
            .build());
    }

    /**
     * Quick DECIDE trace when LLM returns.
     */
    public void decide(int turn, String action, String toolUsed, long durationMs) {
        append(CognitiveTrace.builder(turn, CognitiveTrace.Phase.DECIDE)
            .action(truncate(action, 300))
            .toolUsed(toolUsed)
            .durationMs(durationMs)
            .build());
    }

    /**
     * Quick ACT trace after tool execution / response delivery.
     */
    public void act(int turn, String actionResult) {
        append(CognitiveTrace.builder(turn, CognitiveTrace.Phase.ACT)
            .action(truncate(actionResult, 300))
            .build());
    }

    /**
     * Quick EVALUATE trace after tool result or user reaction.
     */
    public void evaluate(int turn, String evaluation) {
        append(CognitiveTrace.builder(turn, CognitiveTrace.Phase.EVALUATE)
            .evaluation(truncate(evaluation, 300))
            .build());
    }

    /**
     * Get all traces as a list (newest last).
     */
    public List<CognitiveTrace> getTraces() {
        return new ArrayList<>(traces);
    }

    /**
     * Export traces to JSONL for external analysis or fine-tuning.
     */
    public void exportToJsonl(Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            for (CognitiveTrace t : traces) {
                Files.writeString(destination,
                    MAPPER.writeValueAsString(toJson(t)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            logger.info("Exported {} cognitive traces to {}", traces.size(), destination);
        } catch (Exception e) {
            logger.warn("Failed to export traces: {}", e.getMessage());
        }
    }

    public void close() {
        closed = true;
        exportToJsonl(jsonlPath);
    }

    public boolean isEmpty() { return traces.isEmpty(); }
    public int size() { return traces.size(); }

    // ------------------------------------------------------------------

    private void flushOne(CognitiveTrace t) {
        try {
            Files.createDirectories(jsonlPath.getParent());
            Files.writeString(jsonlPath,
                MAPPER.writeValueAsString(toJson(t)) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Silent: disk flush is best-effort
        }
    }

    private ObjectNode toJson(CognitiveTrace t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("sessionId", sessionId);
        n.put("timestamp", t.timestamp().toString());
        n.put("turn", t.turn());
        n.put("phase", t.phase().name());
        if (t.goal() != null) n.put("goal", t.goal());
        if (t.hypothesis() != null) n.put("hypothesis", t.hypothesis());
        if (t.action() != null) n.put("action", t.action());
        if (t.observation() != null) n.put("observation", t.observation());
        if (t.evaluation() != null) n.put("evaluation", t.evaluation());
        if (t.toolUsed() != null) n.put("toolUsed", t.toolUsed());
        n.put("durationMs", t.durationMs());
        if (t.metadata() != null && !t.metadata().isEmpty()) {
            ObjectNode meta = n.putObject("metadata");
            t.metadata().forEach((k, v) -> meta.put(k, String.valueOf(v)));
        }
        return n;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
