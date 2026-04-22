package com.nousresearch.hermes.acp;

import com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ACP Tools utilities.
 * Mirrors Python acp_adapter/tools.py
 */
public class AcpTools {
    
    private static final AtomicInteger callIdCounter = new AtomicInteger(0);
    private static final Map<String, ToolCallInfo> toolCallRegistry = new ConcurrentHashMap<>();
    
    /**
     * Generate a unique tool call ID.
     */
    public static String makeToolCallId() {
        return "tc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    /**
     * Generate a sequential tool call ID for tracking.
     */
    public static String makeSequentialToolCallId(String toolName) {
        return toolName + "_" + callIdCounter.incrementAndGet();
    }
    
    /**
     * Register a tool call.
     */
    public static void registerToolCall(String callId, String toolName, Map<String, Object> arguments) {
        ToolCallInfo info = new ToolCallInfo(callId, toolName, arguments, System.currentTimeMillis());
        toolCallRegistry.put(callId, info);
    }
    
    /**
     * Get tool call info.
     */
    public static ToolCallInfo getToolCallInfo(String callId) {
        return toolCallRegistry.get(callId);
    }
    
    /**
     * Complete a tool call and remove from registry.
     */
    public static ToolCallInfo completeToolCall(String callId, String result) {
        ToolCallInfo info = toolCallRegistry.remove(callId);
        if (info != null) {
            info.setResult(result);
            info.setCompletedAt(System.currentTimeMillis());
        }
        return info;
    }
    
    /**
     * Build tool call start event.
     */
    public static JSONObject buildToolStart(String toolCallId, String toolName, Map<String, Object> arguments) {
        JSONObject event = new JSONObject();
        event.put("type", "tool_call_start");
        event.put("tool_call_id", toolCallId);
        event.put("tool_name", toolName);
        event.put("arguments", arguments != null ? arguments : new JSONObject());
        event.put("timestamp", System.currentTimeMillis());
        return event;
    }
    
    /**
     * Build tool call complete event.
     */
    public static JSONObject buildToolComplete(String toolCallId, String toolName, String result, long durationMs) {
        JSONObject event = new JSONObject();
        event.put("type", "tool_call_complete");
        event.put("tool_call_id", toolCallId);
        event.put("tool_name", toolName);
        event.put("result", result);
        event.put("duration_ms", durationMs);
        event.put("timestamp", System.currentTimeMillis());
        return event;
    }
    
    /**
     * Build tool call error event.
     */
    public static JSONObject buildToolError(String toolCallId, String toolName, String error) {
        JSONObject event = new JSONObject();
        event.put("type", "tool_call_error");
        event.put("tool_call_id", toolCallId);
        event.put("tool_name", toolName);
        event.put("error", error);
        event.put("timestamp", System.currentTimeMillis());
        return event;
    }
    
    /**
     * Tool call information.
     */
    public static class ToolCallInfo {
        private final String callId;
        private final String toolName;
        private final Map<String, Object> arguments;
        private final long startedAt;
        private long completedAt;
        private String result;
        
        public ToolCallInfo(String callId, String toolName, Map<String, Object> arguments, long startedAt) {
            this.callId = callId;
            this.toolName = toolName;
            this.arguments = arguments;
            this.startedAt = startedAt;
        }
        
        public String getCallId() { return callId; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getArguments() { return arguments; }
        public long getStartedAt() { return startedAt; }
        public long getCompletedAt() { return completedAt; }
        public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        
        public long getDurationMs() {
            return completedAt > 0 ? completedAt - startedAt : System.currentTimeMillis() - startedAt;
        }
    }
}
