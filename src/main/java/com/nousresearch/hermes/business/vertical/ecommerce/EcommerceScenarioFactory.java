package com.nousresearch.hermes.business.vertical.ecommerce;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.collaboration.pattern.CollaborationPattern;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 电商物流垂直场景工厂 — 预置标准业务场景，加速 B 端用户 onboarding。
 *
 * <p>提供 4 套开箱即用的场景 + 团队蓝图：
 * <ul>
 *   <li>订单处理（Order Processing）</li>
 *   <li>客户服务（Customer Service）</li>
 *   <li>库存预警（Inventory Alert）</li>
 *   <li>物流追踪（Logistics Tracking）</li>
 * </ul>
 * <p>每个场景自动创建：TeamBlueprint + Scenario + 标准 Prompt + SLA 绑定。</p>
 */
public class EcommerceScenarioFactory {
    private static final Logger logger = LoggerFactory.getLogger(EcommerceScenarioFactory.class);

    private final WorkspaceService workspaceService;
    private final ScenarioService scenarioService;
    private final TeamBlueprintService teamBlueprintService;

    public EcommerceScenarioFactory(WorkspaceService workspaceService,
                                     ScenarioService scenarioService,
                                     TeamBlueprintService teamBlueprintService) {
        this.workspaceService = workspaceService;
        this.scenarioService = scenarioService;
        this.teamBlueprintService = teamBlueprintService;
    }

    /**
     * 创建完整的订单处理场景，包含团队蓝图和标准工作流。
     * 步骤：验证 → 库存检查 → 支付 → 履约协调 → 通知
     */
    public VerticalScenarioSetup createOrderProcessingScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        // 1. Create team blueprint
        String teamId = "order-processing-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Order Processing Team",
                "End-to-end order validation, payment, and fulfillment coordination",
                "Order Processing", null,
                List.of(
                    agent("order-validator", "Order Validator", "Validate order details, check for fraud indicators, verify customer information"),
                    agent("inventory-checker", "Inventory Checker", "Check real-time inventory levels, reserve stock, flag shortages"),
                    agent("payment-processor", "Payment Processor", "Process payments, handle refunds, manage transaction status"),
                    agent("fulfillment-coordinator", "Fulfillment Coordinator", "Coordinate warehouse picking, packing, and shipping handoff"),
                    agent("notification-sender", "Notification Sender", "Send order confirmations, shipping updates, and delivery notifications")
                ),
                List.of("prompt://order-processing-standard"),
                getOrderProcessingManual(),
                Map.of("vertical", "ecommerce", "domain", "order_management")
            );
            logger.info("Created order processing team for workspace {}", workspaceId);
        }

        // 2. Create scenario
        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "order-processing",
            "Order Processing", "Process customer orders from validation through fulfillment",
            teamId,
            List.of("Order validated within 30s", "Payment confirmed", "Inventory reserved", "Fulfillment request created"),
            List.of("high-risk", "external-action"),
            Map.of("sla", "order_processing", "vertical", "ecommerce"),
            CollaborationPattern.SEQUENTIAL,
            "order_processing"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "order-processing");
    }

    /**
     * 创建客户服务场景。
     * 团队：分类 → 历史检索 → 方案起草 → 合规复核
     */
    public VerticalScenarioSetup createCustomerServiceScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "customer-service-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Customer Service Team",
                "Handle customer inquiries, returns, refunds, and complaints",
                "Customer Service", null,
                List.of(
                    agent("inquiry-classifier", "Inquiry Classifier", "Classify customer inquiries by type, urgency, and sentiment"),
                    agent("history-retriever", "History Retriever", "Retrieve customer order history, past interactions, and preferences"),
                    agent("solution-drafter", "Solution Drafter", "Draft responses, solutions, and compensation offers"),
                    agent("policy-reviewer", "Policy Reviewer", "Review solutions for policy compliance and approve escalations")
                ),
                List.of("prompt://customer-service-standard"),
                getCustomerServiceManual(),
                Map.of("vertical", "ecommerce", "domain", "customer_service")
            );
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "customer-service",
            "Customer Service", "Handle inbound customer service requests with empathy and efficiency",
            teamId,
            List.of("Response drafted within 2min", "Policy compliance verified", "Customer satisfaction score > 4.0"),
            List.of("always"),
            Map.of("sla", "customer_service", "vertical", "ecommerce"),
            CollaborationPattern.SEQUENTIAL,
            "customer_service"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "customer-service");
    }

    /**
     * 创建库存预警场景。
     * 团队：库存分析 → 需求预测 → 采购单生成 → 供应商通知
     */
    public VerticalScenarioSetup createInventoryAlertScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "inventory-management-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Inventory Management Team",
                "Monitor inventory levels, predict demand, and trigger replenishment",
                "Inventory Management", null,
                List.of(
                    agent("inventory-analyzer", "Inventory Analyzer", "Analyze current inventory levels and consumption trends"),
                    agent("demand-forecaster", "Demand Forecaster", "Forecast future demand using historical data and seasonality"),
                    agent("purchase-generator", "Purchase Order Generator", "Generate purchase orders based on forecast and lead times"),
                    agent("supplier-notifier", "Supplier Notifier", "Notify suppliers of new orders and track confirmations")
                ),
                List.of("prompt://inventory-management-standard"),
                getInventoryManagementManual(),
                Map.of("vertical", "ecommerce", "domain", "inventory_management")
            );
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "inventory-alert",
            "Inventory Alert", "Monitor inventory and trigger replenishment workflows",
            teamId,
            List.of("Low stock detected within 1hr", "Purchase order generated", "Supplier notified within 4hrs"),
            List.of("external-action"),
            Map.of("sla", "inventory_alert", "vertical", "ecommerce"),
            CollaborationPattern.SEQUENTIAL,
            "inventory_alert"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "inventory-alert");
    }

    /**
     * 创建物流追踪场景。
     * 团队：运单跟踪 → 异常检测 → 客户通知 → 承运商升级
     */
    public VerticalScenarioSetup createLogisticsTrackingScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "logistics-tracking-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Logistics Tracking Team",
                "Track shipments, detect anomalies, and proactively notify stakeholders",
                "Logistics Tracking", null,
                List.of(
                    agent("shipment-tracker", "Shipment Tracker", "Track shipment status across carriers and update internal records"),
                    agent("anomaly-detector", "Anomaly Detector", "Detect delays, route deviations, and delivery exceptions"),
                    agent("customer-notifier", "Customer Notifier", "Proactively notify customers of delays with updated ETAs"),
                    agent("carrier-escalator", "Carrier Escalator", "Escalate issues to carriers and track resolution")
                ),
                List.of("prompt://logistics-tracking-standard"),
                getLogisticsTrackingManual(),
                Map.of("vertical", "ecommerce", "domain", "logistics")
            );
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "logistics-tracking",
            "Logistics Tracking", "Monitor shipments and handle delivery exceptions",
            teamId,
            List.of("Anomaly detected within 15min", "Customer notified within 30min", "Carrier ticket created"),
            List.of("external-action"),
            Map.of("sla", "logistics_tracking", "vertical", "ecommerce"),
            CollaborationPattern.SEQUENTIAL,
            "logistics_tracking"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "logistics-tracking");
    }

    /**
     * 一键播种所有标准电商场景到指定 Workspace。
     * 适合新租户首次初始化。
     */
    public List<VerticalScenarioSetup> seedAll(String workspaceId) {
        return List.of(
            createOrderProcessingScenario(workspaceId),
            createCustomerServiceScenario(workspaceId),
            createInventoryAlertScenario(workspaceId),
            createLogisticsTrackingScenario(workspaceId)
        );
    }

    // ---- Helper: 快速创建 AgentBlueprintRecord ----
    private AgentBlueprintRecord agent(String id, String name, String responsibility) {
        return new AgentBlueprintRecord()
            .setAgentId(id)
            .setDisplayName(name)
            .setResponsibility(responsibility)
            .setAllowedTools(List.of("tenant_bus", "memory", "web_search"))
            .setAllowedSkills(List.of());
    }

    // ---- 各场景运营手册（作为系统提示注入） ----
    private String getOrderProcessingManual() {
        return """
            # Order Processing Operating Manual
            1. Validate order within 30 seconds
            2. Check inventory and reserve stock
            3. Process payment securely
            4. Create fulfillment request
            5. Send confirmation to customer
            6. Escalate fraud indicators immediately
            """;
    }

    private String getCustomerServiceManual() {
        return """
            # Customer Service Operating Manual
            1. Classify inquiry type and urgency
            2. Retrieve customer context and history
            3. Draft empathetic response with solution
            4. Verify policy compliance
            5. Route to human if sentiment is negative
            6. Follow up within 24 hours
            """;
    }

    private String getInventoryManagementManual() {
        return """
            # Inventory Management Operating Manual
            1. Monitor stock levels hourly
            2. Flag items below safety stock
            3. Forecast demand for next 7-30 days
            4. Generate purchase orders for flagged items
            5. Notify suppliers with lead time requirements
            6. Update inventory after receipt confirmation
            """;
    }

    private String getLogisticsTrackingManual() {
        return """
            # Logistics Tracking Operating Manual
            1. Poll carrier APIs every 15 minutes
            2. Compare actual vs expected progress
            3. Flag deviations > 2 hours
            4. Notify customers proactively
            5. Create carrier escalation tickets
            6. Update delivery estimates continuously
            """;
    }

    /** 垂直场景创建结果 — 包含 teamId、scenarioId 和场景类型 */
    public record VerticalScenarioSetup(String teamId, String scenarioId, String scenarioType) {}
}
