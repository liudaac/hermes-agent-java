package com.nousresearch.hermes.agent.transports;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;

import java.util.List;
import java.util.Map;

/**
 * Represents a message in the transport layer.
 * Mirrors Python TransportMessage dataclass.
 */
public record TransportMessage(
    String role,
    String content,
    List<ToolCall> toolCalls,
    JSONObject toolResult,
    Map<String, Object> metadata
) {
    public TransportMessage {
        if (role == null) {
            role = "user";
        }
    }

    public static TransportMessage user(String content) {
        return new TransportMessage("user", content, null, null, null);
    }

    public static TransportMessage assistant(String content) {
        return new TransportMessage("assistant", content, null, null, null);
    }

    public static TransportMessage withToolCalls(String content, List<ToolCall> toolCalls) {
        return new TransportMessage("assistant", content, toolCalls, null, null);
    }

    public static TransportMessage toolResult(String toolCallId, String result) {
        JSONObject toolResultObj = new JSONObject();
        toolResultObj.put("tool_call_id", toolCallId);
        toolResultObj.put("content", result);
        return new TransportMessage("tool", null, null, toolResultObj, null);
    }

    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("role", role);
        if (content != null) {
            json.put("content", content);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JSONArray calls = new JSONArray();
            for (ToolCall call : toolCalls) {
                calls.add(call.toJSON());
            }
            json.put("tool_calls", calls);
        }
        if (toolResult != null) {
            json.put("tool_result", toolResult);
        }
        return json;
    }

    /**
     * Inner class representing a tool call within a message.
     */
    public static class ToolCall {
        private final String id;
        private final String type;
        private final ToolFunction function;

        public ToolCall(String id, String type, ToolFunction function) {
            this.id = id;
            this.type = type;
            this.function = function;
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public ToolFunction getFunction() { return function; }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("type", type);
            if (function != null) {
                json.put("function", function.toJSON());
            }
            return json;
        }
    }

    /**
     * Represents a function call in a tool call.
     */
    public static class ToolFunction {
        private final String name;
        private final String arguments;

        public ToolFunction(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() { return name; }
        public String getArguments() { return arguments; }

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("arguments", arguments);
            return json;
        }
    }
}
