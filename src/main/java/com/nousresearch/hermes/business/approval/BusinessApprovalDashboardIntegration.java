package com.nousresearch.hermes.business.approval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Business Portal approval center routes. */
public final class BusinessApprovalDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessApprovalDashboardIntegration.class);

    private BusinessApprovalDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, BusinessApprovalService service, ScenarioService scenarioService, com.nousresearch.hermes.business.run.BusinessRunService runService) {
        logger.info("Registering Business Approval Center routes");
        app.get("/api/v1/workspaces/{workspaceId}/approvals", ctx -> listApprovals(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals", ctx -> createApproval(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}", ctx -> getApproval(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/approve", ctx -> approve(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/reject", ctx -> reject(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/request-info", ctx -> requestInfo(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/resume-execution", ctx -> resumeExecution(ctx, service, scenarioService, runService));
    }

    static void listApprovals(Context ctx, BusinessApprovalService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String status = ctx.queryParam("status");
        try {
            var approvals = service.listApprovals(workspaceId, status);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "approvals", approvals, "total", approvals.size()));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createApproval(Context ctx, BusinessApprovalService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        try {
            BusinessApprovalRecord record = service.createApproval(
                workspaceId,
                body.getString("teamId"),
                body.getString("title"),
                body.getString("summary"),
                body.getString("reasonRequired"),
                body.getString("approveEffect"),
                body.getString("rejectEffect"),
                body.getString("recommendation"),
                body.getString("riskLevel"),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("evidence")),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "approvalId", record.getApprovalId(), "approval", record, "message", "Approval created"));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        } catch (Exception e) {
            logger.error("Failed to create business approval", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void getApproval(Context ctx, BusinessApprovalService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String approvalId = ctx.pathParam("approvalId");
        try {
            BusinessApprovalRecord record = service.requireApproval(workspaceId, approvalId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "approvalId", approvalId, "approval", record));
        } catch (WorkspaceService.WorkspaceNotFoundException | BusinessApprovalService.BusinessApprovalNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        }
    }

    static void approve(Context ctx, BusinessApprovalService service) {
        resolve(ctx, service, "approve");
    }

    static void reject(Context ctx, BusinessApprovalService service) {
        resolve(ctx, service, "reject");
    }

    static void requestInfo(Context ctx, BusinessApprovalService service) {
        resolve(ctx, service, "request-info");
    }

    static void resumeExecution(Context ctx, BusinessApprovalService service, ScenarioService scenarioService, com.nousresearch.hermes.business.run.BusinessRunService runService) {
        String workspaceId = ctx.pathParam("workspaceId");
        String approvalId = ctx.pathParam("approvalId");
        JSONObject body = parseBody(ctx);
        try {
            BusinessApprovalRecord record = service.requireApproval(workspaceId, approvalId);
            if (!BusinessApprovalService.APPROVED.equals(record.getStatus())) {
                ctx.status(409).json(Map.of("ok", false, "error", "Approval is not approved", "workspaceId", workspaceId, "approvalId", approvalId, "status", record.getStatus()));
                return;
            }
            String scenarioId = body.getString("scenarioId");
            String userInput = body.getString("userInput");
            if (scenarioId == null || scenarioId.isBlank()) {
                ctx.status(400).json(Map.of("ok", false, "error", "scenarioId is required", "workspaceId", workspaceId, "approvalId", approvalId));
                return;
            }
            var run = scenarioService.executeScenario(workspaceId, scenarioId, userInput, runService, true);
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "approvalId", approvalId, "runId", run.getRunId(), "run", run, "message", "Execution resumed"));
        } catch (ScenarioService.ApprovalRequiredException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        } catch (WorkspaceService.WorkspaceNotFoundException | BusinessApprovalService.BusinessApprovalNotFoundException | ScenarioService.ScenarioNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        } catch (Exception e) {
            logger.error("Failed to resume execution", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        }
    }

    private static void resolve(Context ctx, BusinessApprovalService service, String action) {
        String workspaceId = ctx.pathParam("workspaceId");
        String approvalId = ctx.pathParam("approvalId");
        JSONObject body = parseBody(ctx);
        String actor = body.getString("actor");
        String reason = body.getString("reason");
        try {
            BusinessApprovalRecord record = switch (action) {
                case "approve" -> service.approve(workspaceId, approvalId, actor, reason);
                case "reject" -> service.reject(workspaceId, approvalId, actor, reason);
                case "request-info" -> service.requestInfo(workspaceId, approvalId, actor, body.getString("requestedInfo"));
                default -> throw new IllegalArgumentException("Unsupported approval action: " + action);
            };
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("workspaceId", workspaceId);
            response.put("approvalId", approvalId);
            response.put("status", record.getStatus());
            response.put("approval", record);
            response.put("message", "Approval " + record.getStatus().toLowerCase().replace('_', ' '));
            ctx.status(200).json(response);
        } catch (WorkspaceService.WorkspaceNotFoundException | BusinessApprovalService.BusinessApprovalNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        } catch (BusinessApprovalService.BusinessApprovalAlreadyResolvedException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        }
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }
}
