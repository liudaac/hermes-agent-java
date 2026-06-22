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
 * Extended Business Portal APIs for advanced orchestration features:
 * SLA, DLQ, approval analytics, human-in-the-loop, workflows, connectors, and vertical scenarios.
 */
public final class BusinessPortalExtendedIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessPortalExtendedIntegration.class);

    private BusinessPortalExtendedIntegration() {}

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

        // ---- SLA ----
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

        // ---- DLQ ----
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

        app.post("/api/v1/business/dlq/{itemId}/retry", ctx -> {
            String itemId = ctx.pathParam("itemId");
            deadLetterQueue.markRetried(itemId, "new-run-pending");
            ctx.status(200).json(Map.of("ok", true, "itemId", itemId, "status", "RETRIED"));
        });

        app.post("/api/v1/business/dlq/{itemId}/resolve", ctx -> {
            String itemId = ctx.pathParam("itemId");
            deadLetterQueue.markResolved(itemId);
            ctx.status(200).json(Map.of("ok", true, "itemId", itemId, "status", "RESOLVED"));
        });

        // ---- Approval Analytics ----
        app.get("/api/v1/business/approval-analytics", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            ctx.status(200).json(Map.of(
                "ok", true,
                "workspaceId", workspaceId == null ? "" : workspaceId,
                "summary", approvalAnalytics.getSummary(workspaceId)
            ));
        });

        // ---- Human-in-the-loop ----
        app.get("/api/v1/business/takeovers", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            var takeovers = humanOverrideService.listActiveTakeovers(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "takeovers", takeovers));
        });

        app.post("/api/v1/business/takeovers", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            String workspaceId = String.valueOf(body.get("workspaceId"));
            String runId = String.valueOf(body.get("runId"));
            String operatorId = String.valueOf(body.get("operatorId"));
            var session = humanOverrideService.requestTakeover(workspaceId, runId, operatorId);
            ctx.status(200).json(Map.of("ok", true, "takeover", session));
        });

        app.post("/api/v1/business/takeovers/{takeoverId}/confirm", ctx -> {
            humanOverrideService.confirmTakeover(ctx.pathParam("takeoverId"));
            ctx.status(200).json(Map.of("ok", true));
        });

        app.post("/api/v1/business/takeovers/{takeoverId}/release", ctx -> {
            humanOverrideService.releaseTakeover(ctx.pathParam("takeoverId"));
            ctx.status(200).json(Map.of("ok", true));
        });

        // ---- Workflows ----
        app.get("/api/v1/business/workflows", ctx -> {
            String workspaceId = ctx.queryParam("workspaceId");
            var workflows = workflowService.listActiveWorkflows(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workflows", workflows));
        });

        app.get("/api/v1/business/workflows/{workflowId}", ctx -> {
            var status = workflowService.getWorkflowStatus(ctx.pathParam("workflowId"));
            ctx.status(200).json(Map.of("ok", true, "status", status));
        });

        app.post("/api/v1/business/workflows/{workflowId}/checkpoint", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            workflowService.approveCheckpoint(ctx.pathParam("workflowId"), String.valueOf(body.get("decision")));
            ctx.status(200).json(Map.of("ok", true));
        });

        // ---- Connectors ----
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

        app.post("/api/v1/business/connectors/{connectorName}/execute", ctx -> {
            String connectorName = ctx.pathParam("connectorName");
            var body = ctx.bodyAsClass(Map.class);
            String operation = String.valueOf(body.get("operation"));
            @SuppressWarnings("unchecked")
            var params = (Map<String, Object>) body.getOrDefault("params", Map.of());
            var result = connectorRegistry.execute(connectorName, operation, params);
            ctx.status(200).json(Map.of("ok", true, "result", result));
        });

        // ---- Vertical Scenarios ----
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
