/**
 * ACP（Agent Collaboration Protocol）服务器 — 基于 Javalin 的协议入口。
 *
 * <p>为外部 MCP 客户端提供统一的 Agent 协作接口，核心能力：
 * <ul>
 *   <li>WebSocket 长连接会话管理（多客户端并发）</li>
 *   <li>HTTP REST API（状态查询、管理操作）</li>
 *   <li>命令路由 — 将 MCP 协议命令映射到内部 ToolRegistry</li>
 *   <li>租户隔离 — 每个会话绑定到特定 workspace/tenant</li>
 *   <li>审批门控 — 高风险操作自动进入审批流</li>
 *   <li>权限控制 — 基于角色的操作权限校验</li>
 * </ul>
 * <p>与 DashboardServer 的区别：DashboardServer 面向人类运营者（Web UI），
 * AcpServer 面向机器客户端（MCP Protocol）。</p>
 */
package com.nousresearch.hermes.acp;

import com.nousresearch.hermes.acp.integration.*;
import com.nousresearch.hermes.acp.protocol.*;
import com.nousresearch.hermes.acp.security.*;
import com.nousresearch.hermes.acp.session.*;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.websocket.WsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class AcpServer {
    private static final Logger logger = LoggerFactory.getLogger(AcpServer.class);

    private final int port;
    private Javalin app;

    // 依赖注入（由 Main.java 启动时传入）
    private final TenantManager tenantManager;
    private final ToolRegistry toolRegistry;
    private final BusinessApprovalService approvalService;
    private final BusinessRunService runService;
    private final WorkspaceService workspaceService;
    private final ScenarioOrchestrator scenarioOrchestrator;

    // 活跃会话表 — sessionId → AcpSession
    private final ConcurrentHashMap<String, AcpSession> sessions = new ConcurrentHashMap<>();
    private final AcpSessionFactory sessionFactory;

    public AcpServer(int port,
                     TenantManager tenantManager,
                     ToolRegistry toolRegistry,
                     BusinessApprovalService approvalService,
                     BusinessRunService runService,
                     WorkspaceService workspaceService,
                     ScenarioOrchestrator scenarioOrchestrator) {
        this.port = port;
        this.tenantManager = tenantManager;
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService;
        this.runService = runService;
        this.workspaceService = workspaceService;
        this.scenarioOrchestrator = scenarioOrchestrator;
        this.sessionFactory = new AcpSessionFactory(
            tenantManager, toolRegistry, approvalService,
            runService, workspaceService, scenarioOrchestrator,
            this::onSessionClose
        );
    }

    /**
     * 启动 ACP 服务器 — 注册 WebSocket 和 HTTP 路由。
     */
    public void start() {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        // WebSocket 入口 — MCP 客户端长连接
        app.ws("/acp/v1/ws", this::configureWebSocket);

        // HTTP REST API — 状态查询、会话管理
        app.get("/acp/v1/health", ctx -> ctx.json(Map.of("status", "ok", "sessions", sessions.size())));
        app.get("/acp/v1/sessions", ctx -> ctx.json(Map.of(
            "sessions", sessions.values().stream().map(AcpSession::toSummary).toList()
        )));
        app.post("/acp/v1/sessions/{sessionId}/close", ctx -> {
            AcpSession session = sessions.remove(ctx.pathParam("sessionId"));
            if (session != null) session.close();
            ctx.json(Map.of("ok", true));
        });

        // 工具列表 — 客户端发现可用工具
        app.get("/acp/v1/tools", ctx -> {
            String tenantId = ctx.queryParam("tenantId");
            ctx.json(Map.of("tools", toolRegistry.listTools(tenantId)));
        });

        app.start(port);
        logger.info("ACP Server started on port {}", port);
    }

    /**
     * 停止服务器，清理所有会话。
     */
    public void stop() {
        sessions.values().forEach(AcpSession::close);
        sessions.clear();
        if (app != null) app.stop();
        logger.info("ACP Server stopped");
    }

    // ---- WebSocket 配置 ----

    private void configureWebSocket(WsConfig ws) {
        ws.onConnect(ctx -> {
            String sessionId = ctx.getSessionId();
            // 从 query param 提取租户信息
            String workspaceId = ctx.queryParam("workspaceId");
            String apiKey = ctx.queryParam("apiKey");

            AcpSession session = sessionFactory.create(sessionId, ctx, workspaceId, apiKey);
            sessions.put(sessionId, session);
            logger.info("ACP session connected: {} (workspace={})", sessionId, workspaceId);
        });

        ws.onMessage(ctx -> {
            AcpSession session = sessions.get(ctx.getSessionId());
            if (session != null) {
                session.onMessage(ctx.message());
            }
        });

        ws.onClose(ctx -> {
            AcpSession session = sessions.remove(ctx.getSessionId());
            if (session != null) session.close();
            logger.info("ACP session closed: {}", ctx.getSessionId());
        });
    }

    private void onSessionClose(String sessionId) {
        sessions.remove(sessionId);
    }

    public int getPort() { return port; }
    public int getActiveSessionCount() { return sessions.size(); }
}
