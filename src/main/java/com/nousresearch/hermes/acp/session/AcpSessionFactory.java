/**
 * ACP 会话工厂 — 负责创建和初始化 AcpSession 实例。
 *
 * <p>集中管理会话依赖注入，确保每个会话获得正确的租户上下文和权限检查器。</p>
 */
package com.nousresearch.hermes.acp.session;

import com.nousresearch.hermes.acp.security.AcpPermissionChecker;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.org.tools.ToolRegistry;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class AcpSessionFactory {
    private static final Logger logger = LoggerFactory.getLogger(AcpSessionFactory.class);

    private final TenantManager tenantManager;
    private final ToolRegistry toolRegistry;
    private final BusinessApprovalService approvalService;
    private final BusinessRunService runService;
    private final WorkspaceService workspaceService;
    private final ScenarioOrchestrator scenarioOrchestrator;
    private final Consumer<String> onCloseCallback;

    public AcpSessionFactory(TenantManager tenantManager,
                             ToolRegistry toolRegistry,
                             BusinessApprovalService approvalService,
                             BusinessRunService runService,
                             WorkspaceService workspaceService,
                             ScenarioOrchestrator scenarioOrchestrator,
                             Consumer<String> onCloseCallback) {
        this.tenantManager = tenantManager;
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService;
        this.runService = runService;
        this.workspaceService = workspaceService;
        this.scenarioOrchestrator = scenarioOrchestrator;
        this.onCloseCallback = onCloseCallback;
    }

    /**
     * 创建新会话 — 自动解析租户上下文并初始化权限检查器。
     */
    public AcpSession create(String sessionId, WsContext wsContext,
                              String workspaceId, String apiKey) {
        // 租户隔离：从 workspaceId 解析或创建 TenantContext
        TenantContext tenantContext;
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceService.requireWorkspace(workspaceId);
            tenantContext = tenantManager.resolveForWorkspace(workspaceId);
        } else {
            // 未指定 workspace 时使用默认租户
            tenantContext = tenantManager.getDefaultContext();
            logger.warn("ACP session {} created without workspaceId, using default tenant", sessionId);
        }

        // 权限检查器 — 基于 API Key 和 workspace 角色
        AcpPermissionChecker permissionChecker = new AcpPermissionChecker(
            workspaceService, workspaceId, apiKey
        );

        return new AcpSession(
            sessionId, wsContext, workspaceId,
            tenantContext,
            toolRegistry, approvalService, runService,
            workspaceService, scenarioOrchestrator,
            permissionChecker,
            onCloseCallback
        );
    }
}
