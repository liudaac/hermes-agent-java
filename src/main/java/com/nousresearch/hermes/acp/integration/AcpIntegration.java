/**
 * ACP 与主系统集成入口 — 将 ACP 服务器接入 HermesAgentV2 启动流程。
 *
 * <p>职责：
 * <ul>
 *   <li>从 Main.java 接收所有核心服务实例</li>
 *   <li>创建并配置 AcpServer</li>
 *   <li>注册关闭钩子（ graceful shutdown ）</li>
 *   <li>暴露 ACP 健康状态到 DashboardServer</li>
 * </ul>
 */
package com.nousresearch.hermes.acp.integration;

import com.nousresearch.hermes.acp.AcpServer;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AcpIntegration {
    private static final Logger logger = LoggerFactory.getLogger(AcpIntegration.class);

    private AcpServer acpServer;

    /**
     * 启动 ACP 服务器 — 由 Main.java 在初始化完成后调用。
     *
     * @param port               ACP 服务端口（建议与 DashboardServer 不同）
     * @param tenantManager      租户管理器（隔离核心）
     * @param toolRegistry       工具注册中心（命令路由目标）
     * @param approvalService    审批服务（高风险门控）
     * @param runService         运行服务（审计记录）
     * @param workspaceService   工作空间服务（workspace 校验）
     * @param scenarioOrchestrator 场景编排器（场景执行）
     */
    public void start(int port,
                      TenantManager tenantManager,
                      ToolRegistry toolRegistry,
                      BusinessApprovalService approvalService,
                      BusinessRunService runService,
                      WorkspaceService workspaceService,
                      ScenarioOrchestrator scenarioOrchestrator) {
        if (acpServer != null) {
            logger.warn("ACP Server already started");
            return;
        }

        acpServer = new AcpServer(
            port, tenantManager, toolRegistry,
            approvalService, runService, workspaceService, scenarioOrchestrator
        );
        acpServer.start();

        // 注册 JVM 关闭钩子 — 确保 graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("ACP shutdown hook triggered");
            if (acpServer != null) acpServer.stop();
        }));

        logger.info("ACP integrated successfully on port {}", port);
    }

    /**
     * 停止 ACP 服务器。
     */
    public void stop() {
        if (acpServer != null) {
            acpServer.stop();
            acpServer = null;
        }
    }

    public boolean isRunning() {
        return acpServer != null;
    }

    public int getActiveSessionCount() {
        return acpServer != null ? acpServer.getActiveSessionCount() : 0;
    }
}
