/**
 * ACP 协议请求 — 解析 MCP 客户端发送的 JSON-RPC 风格消息。
 *
 * <p>协议格式：
 * <pre>
 * {
 *   "id": "cmd-001",
 *   "tool": "tenant_bus.send",
 *   "params": { "agentId": "agent-1", "message": "..." },
 *   "metadata": { "timeoutMs": 30000 }
 * }
 * </pre>
 */
package com.nousresearch.hermes.acp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public record AcpRequest(
    String id,
    String tool,
    Map<String, Object> params,
    Map<String, Object> metadata
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AcpRequest parse(String json) throws Exception {
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        return new AcpRequest(
            String.valueOf(map.getOrDefault("id", "unknown")),
            String.valueOf(map.get("tool")),
            (Map<String, Object>) map.getOrDefault("params", Map.of()),
            (Map<String, Object>) map.getOrDefault("metadata", Map.of())
        );
    }

    public String getId() { return id; }
    public String getToolName() { return tool; }
    public Map<String, Object> getParams() { return params != null ? params : Map.of(); }
}
