/**
 * ACP 协议响应 — 统一 MCP 命令返回格式。
 *
 * <p>三种状态：
 * <ul>
 *   <li>success — 命令执行成功，包含 result 数据</li>
 *   <li>error — 执行失败，包含错误信息</li>
 *   <li>pending — 操作进入审批流，等待人工决议</li>
 * </ul>
 */
package com.nousresearch.hermes.acp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public record AcpResponse(
    String id,
    String status, // "success" | "error" | "pending"
    Object result,
    String error,
    Map<String, Object> metadata
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AcpResponse success(String id, Object result) {
        return new AcpResponse(id, "success", result, null, Map.of());
    }

    public static AcpResponse error(String id, String error) {
        return new AcpResponse(id, "error", null, error, Map.of());
    }

    public static AcpResponse pending(String id, String message) {
        return new AcpResponse(id, "pending", null, null,
            Map.of("message", message));
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"id\":\"" + id + "\",\"status\":\"error\",\"error\":\"Serialization failed\"}";
        }
    }
}
