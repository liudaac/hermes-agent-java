/**
 * 工具注册中心 — 管理 Agent 可用的工具集合。
 *
 * <p>ACP 模块通过 ToolRegistry 调用内部工具。
 * 当前为简化实现，后续可扩展为支持动态工具发现、版本管理等。</p>
 */
package com.nousresearch.hermes.org.tools;

import com.nousresearch.hermes.tenant.core.TenantContext;

import java.util.List;
import java.util.Map;

public class ToolRegistry {

    /**
     * 调用指定工具。
     *
     * @param toolName  工具名称
     * @param params    工具参数
     * @param tenantContext  租户上下文（用于隔离）
     * @return 工具执行结果
     */
    public Object callTool(String toolName, Map<String, Object> params, TenantContext tenantContext) {
        // TODO: 实现真正的工具调用逻辑
        // 当前为占位实现，返回参数本身作为回声测试
        return Map.of("tool", toolName, "params", params, "status", "placeholder");
    }

    /**
     * 列出指定租户下可用的工具。
     *
     * @param tenantId 租户 ID（null 表示返回所有公共工具）
     * @return 工具名称列表
     */
    public List<String> listTools(String tenantId) {
        // TODO: 从实际工具存储中读取
        return List.of("tenant_bus", "memory", "web_search", "scenario.execute");
    }
}
