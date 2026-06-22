/**
 * ACP 权限检查器 — 基于 workspace 角色和 API Key 的操作权限控制。
 *
 * <p>权限模型：
 * <ul>
 *   <li>read — 只读操作（查询状态、列出工具）</li>
 *   <li>write — 写操作（调用工具、执行场景）</li>
 *   <li>admin — 管理操作（创建团队、审批决议）</li>
 * </ul>
 * <p>高风险操作（涉及支付、退款、数据删除）自动触发审批流。</p>
 */
package com.nousresearch.hermes.acp.security;

import com.nousresearch.hermes.workspace.WorkspaceService;

import java.util.Map;
import java.util.Set;

public class AcpPermissionChecker {

    /** 高风险工具前缀 — 这些操作需要额外审批 */
    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
        "payment", "refund", "delete", "remove", "cancel",
        "budget.approve", "supplier.cancel", "inventory.delete"
    );

    /** 需要 write 权限的工具前缀 */
    private static final Set<String> WRITE_TOOLS = Set.of(
        "tenant_bus", "memory", "web_search", "scenario.execute",
        "team.create", "agent.deploy"
    );

    /** 需要 admin 权限的工具前缀 */
    private static final Set<String> ADMIN_TOOLS = Set.of(
        "workspace.delete", "team.delete", "agent.delete",
        "approval.override", "config.update"
    );

    private final WorkspaceService workspaceService;
    private final String workspaceId;
    private final String apiKey;

    public AcpPermissionChecker(WorkspaceService workspaceService,
                                 String workspaceId, String apiKey) {
        this.workspaceService = workspaceService;
        this.workspaceId = workspaceId;
        this.apiKey = apiKey;
    }

    /**
     * 检查会话是否有权限调用指定工具。
     */
    public boolean hasPermission(String workspaceId, String sessionId, String toolName) {
        // TODO: 接入真实的 RBAC 系统，当前简化实现
        if (toolName == null) return false;

        // Admin 工具需要校验 apiKey
        if (isAdminTool(toolName)) {
            return isAdminApiKey(apiKey);
        }

        // Write 工具需要 workspace 存在
        if (isWriteTool(toolName)) {
            return workspaceId != null && !workspaceId.isBlank();
        }

        // 其余工具默认允许（read 级别）
        return true;
    }

    /**
     * 判断操作是否为高风险 — 高风险操作自动进入审批流。
     */
    public boolean isHighRisk(String toolName, Map<String, Object> params) {
        if (toolName == null) return false;
        String normalized = toolName.toLowerCase();
        return HIGH_RISK_TOOLS.stream().anyMatch(normalized::contains);
    }

    private boolean isAdminTool(String toolName) {
        return ADMIN_TOOLS.stream().anyMatch(toolName::startsWith);
    }

    private boolean isWriteTool(String toolName) {
        return WRITE_TOOLS.stream().anyMatch(toolName::startsWith);
    }

    private boolean isAdminApiKey(String apiKey) {
        // TODO: 接入真实的 API Key 校验系统
        return apiKey != null && apiKey.startsWith("admin-");
    }
}
