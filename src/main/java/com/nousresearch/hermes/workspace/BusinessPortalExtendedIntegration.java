package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.business.analytics.ApprovalAnalytics;
import com.nousresearch.hermes.business.dlq.DeadLetterQueue;
import com.nousresearch.hermes.business.humanintheloop.HumanOverrideService;
import com.nousresearch.hermes.business.sla.SLADefinition;
import com.nousresearch.hermes.business.sla.SLAManager;
import com.nousresearch.hermes.business.vertical.ecommerce.EcommerceScenarioFactory;
import com.nousresearch.hermes.business.workflow.BusinessWorkflowService;
import com.nousresearch.hermes.connector.ConnectorRegistry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Business Portal 扩展 API 路由注册 — 提供高级编排功能接口。
 *
 * <p>覆盖 6 大模块的 REST API：
 * <ul>
 *   <li>SLA 管理 — 模板查询（前端 SLA 页面初始化）</li>
 *   <li>死信队列 — 列表 / 重试 / 解决（DLQ 页面操作）</li>
 *   <li>审批分析 — 效率统计和瓶颈检测（Analytics 页面数据）</li>
 *   <li>人机协同 — 接管请求 / 确认 / 释放（HITL 页面操作）</li>
 *   <li>工作流 — 列表 / 状态 / 检查点审批（Workflow 页面操作）</li>
 *   <li>连接器 — 列表 / 执行（外部系统对接）</li>
 *   <li>垂直场景 — 电商物流一键播种（快速初始化）</li>
 * </ul>
 * <p>所有路由前缀为 <code>/api/v1/business/</code>。</p>
 */
public final class BusinessPortalExtendedIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessPortalExtendedIntegration.class);

    private BusinessPortalExtendedIntegration() {}

    /**
     * 注册所有扩展路由到 Javalin 应用实例。
     * 由 DashboardServer 在启动时调用。
     */
    public static void registerRoutes(
        Javalin app,
        WorkspaceService workspaceService,
        SLAManager slaManager,
        DeadLetterQueue deadLetterQueue,
        ApprovalAnalytics approvalAnalytics,
        HumanOverrideService humanOverrideService,
        BusinessWorkflowService workflowService,
        ConnectorRegistry connectorRegistry,
        EcommerceScenarioFactory ecommerceFactory
    ) {
        logger.info("Registering Business Portal extended routes");

        // ---- SLA 模板接口 — 供前端 SLA 页面初始化下拉选项 ----
        app.get("/api/v1/business/sla/templates", ctx -> {
            ctx.status(200).json(Map.of(
                "ok", true,
                "templates", Map.of(
                    "customer_service", SLADefinition.customerService(),
                    "order_processing", SLADefinition.orderProcessing(),
                    "inventory_alert", SLADefinition.inventoryAlert(),
                    "payment_processing", SLADefinition.paymentProcessing(),
                    "general", SLADefinition.general()
                )
            ));
        });

        // ---- 死信队列接口 — DLQ 页面的核心 CRUD ----
        app.get("/api/v1/business/dlq", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            var items = deadLetterQueue.list(workspaceId);
            ctx.status(200).json(Map.of(
                "ok", true,
                "workspaceId", workspaceId == null ? "" : workspaceId,
                "items", items,
                "stats", deadLetterQueue.stats(workspaceId)
            ));
        });

        /** 将死信项标记为重试状态（实际重试逻辑由后台调度器处理） */
        app.post("/api/v1/business/dlq/{itemId}/retry", ctx -> {
            String itemId = ctx.pathParam("itemId");
            deadLetterQueue.markRetried(itemId, "new-run-pending");
            ctx.status(200).json(Map.of("ok", true, "itemId", itemId, "status", "RETRIED"));
        });

        /** 将死信项标记为已解决（人工确认无需重试） */
        app.post("/api/v1/business/dlq/{itemId}/resolve", ctx -> {
            String itemId = ctx.pathParam("itemId");
            deadLetterQueue.markResolved(itemId);
            ctx.status(200).json(Map.of("ok", true, "itemId", itemId, "status", "RESOLVED"));
        });

        // ---- 审批分析接口 — 供 Analytics Dashboard 拉取指标 ----
        app.get("/api/v1/business/approval-analytics", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            ctx.status(200).json(Map.of(
                "ok", true,
                "workspaceId", workspaceId == null ? "" : workspaceId,
                "summary", approvalAnalytics.getSummary(workspaceId)
            ));
        });

        // ---- 人机协同接口 — HITL 页面的接管管理 ----
        app.get("/api/v1/business/takeovers", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            var takeovers = humanOverrideService.listActiveTakeovers(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "takeovers", takeovers));
        });

        /** 请求接管一个正在运行的 Agent 会话 */
        app.post("/api/v1/business/takeovers", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            String workspaceId = String.valueOf(body.get("workspaceId"));
            String runId = String.valueOf(body.get("runId"));
            String operatorId = String.valueOf(body.get("operatorId"));
            var session = humanOverrideService.requestTakeover(workspaceId, runId, operatorId);
            ctx.status(200).json(Map.of("ok", true, "takeover", session));
        });

        /** 运营人员确认接管，暂停 Agent 执行 */
        app.post("/api/v1/business/takeovers/{takeoverId}/confirm", ctx -> {
            humanOverrideService.confirmTakeover(ctx.pathParam("takeoverId"));
            ctx.status(200).json(Map.of("ok", true));
        });

        /** 运营人员释放接管，恢复 Agent 自主执行 */
        app.post("/api/v1/business/takeovers/{takeoverId}/release", ctx -> {
            humanOverrideService.releaseTakeover(ctx.pathParam("takeoverId"));
            ctx.status(200).json(Map.of("ok", true));
        });

        // ---- 工作流接口 — Workflow 页面状态查询和检查点审批 ----
        app.get("/api/v1/business/workflows", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            var workflows = workflowService.listActiveWorkflows(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workflows", workflows));
        });

        app.get("/api/v1/business/workflows/{workflowId}", ctx -> {
            var status = workflowService.getWorkflowStatus(ctx.pathParam("workflowId"));
            ctx.status(200).json(Map.of("ok", true, "status", status));
        });

        /** 审批工作流中的人工检查点（approve / reject / modify） */
        app.post("/api/v1/business/workflows/{workflowId}/checkpoint", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            workflowService.approveCheckpoint(ctx.pathParam("workflowId"), String.valueOf(body.get("decision")));
            ctx.status(200).json(Map.of("ok", true));
        });

        // ---- 连接器接口 — 外部系统管理和执行 ----
        app.get("/api/v1/business/connectors", ctx -> {
            var connectors = connectorRegistry.listAll();
            ctx.status(200).json(Map.of(
                "ok", true,
                "connectors", connectors.stream().map(c -> Map.of(
                    "name", c.getName(),
                    "label", c.getLabel(),
                    "description", c.getDescription(),
                    "healthy", c.isHealthy()
                )).toList(),
                "health", connectorRegistry.getHealthSummary()
            ));
        });

        /** 在指定连接器上执行操作（如查询淘宝订单、推送菜鸟运单） */
        app.post("/api/v1/business/connectors/{connectorName}/execute", ctx -> {
            String connectorName = ctx.pathParam("connectorName");
            var body = ctx.bodyAsClass(Map.class);
            String operation = String.valueOf(body.get("operation"));
            @SuppressWarnings("unchecked")
            var params = (Map<String, Object>) body.getOrDefault("params", Map.of());
            var result = connectorRegistry.execute(connectorName, operation, params);
            ctx.status(200).json(Map.of("ok", true, "result", result));
        });

        // ---- 垂直场景接口 — 一键播种电商物流标准场景 ----
        app.post("/api/v1/business/verticals/ecommerce/seed", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            String workspaceId = String.valueOf(body.get("workspaceId"));
            var setups = ecommerceFactory.seedAll(workspaceId);
            ctx.status(200).json(Map.of(
                "ok", true,
                "workspaceId", workspaceId,
                "scenarios", setups.stream().map(s -> Map.of(
                    "teamId", s.teamId(),
                    "scenarioId", s.scenarioId(),
                    "scenarioType", s.scenarioType()
                )).toList()
            ));
        });
    }
}
