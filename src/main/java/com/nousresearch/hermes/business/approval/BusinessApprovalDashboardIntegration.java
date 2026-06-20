package com.nousresearch.hermes.business.approval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Business Portal approval center routes. */
public final class BusinessApprovalDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessApprovalDashboardIntegration.class);

    private BusinessApprovalDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, BusinessApprovalService service,
                                       ScenarioService scenarioService,
                                       com.nousresearch.hermes.business.run.BusinessRunService runService,
                                       ToolApprovalCoordinator toolApprovalCoordinator) {
        logger.info("Registering Business Approval Center routes");
        app.get("/api/v1/workspaces/{workspaceId}/approvals", ctx -> listApprovals(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals", ctx -> createApproval(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}", ctx -> getApproval(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/approve", ctx -> approve(ctx, service, scenarioService, runService, toolApprovalCoordinator));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/reject", ctx -> reject(ctx, service, runService, toolApprovalCoordinator));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/request-info", ctx -> requestInfo(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/resume-execution", ctx -> resumeExecution(ctx, service, scenarioService, runService));
        // SSE: real-time approval events for a workspace
        app.sse("/api/v1/workspaces/{workspaceId}/approvals/stream", client -> streamApprovals(service, client));
        // SSE: real-time events for a single approval
        app.sse("/api/v1/workspaces/{workspaceId}/approvals/{approvalId}/stream", client -> streamApprovalDetail(service, client));
    }

    /** Backward-compat: register without tool approval coordinator. */
    public static void registerRoutes(Javalin app, BusinessApprovalService service,
                                       ScenarioService scenarioService,
                                       com.nousresearch.hermes.business.run.BusinessRunService runService) {
        registerRoutes(app, service, scenarioService, runService, null);
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

    static void approve(Context ctx, BusinessApprovalService service, ScenarioService scenarioService,
                        com.nousresearch.hermes.business.run.BusinessRunService runService,
                        ToolApprovalCoordinator toolApprovalCoordinator) {
        resolveWithAutoResume(ctx, service, "approve", scenarioService, runService, toolApprovalCoordinator);
    }

    static void reject(Context ctx, BusinessApprovalService service,
                       com.nousresearch.hermes.business.run.BusinessRunService runService,
                       ToolApprovalCoordinator toolApprovalCoordinator) {
        resolveWithAutoResume(ctx, service, "reject", null, runService, toolApprovalCoordinator);
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

            // Fall back to approval record's linked scenario
            if ((scenarioId == null || scenarioId.isBlank()) && record.getScenarioId() != null) {
                scenarioId = record.getScenarioId();
            }
            if (scenarioId == null || scenarioId.isBlank()) {
                ctx.status(400).json(Map.of("ok", false, "error", "scenarioId is required", "workspaceId", workspaceId, "approvalId", approvalId));
                return;
            }

            // Fall back to evidence userInput
            if ((userInput == null || userInput.isBlank()) && record.getEvidence() != null && record.getEvidence().get("userInput") != null) {
                userInput = String.valueOf(record.getEvidence().get("userInput"));
            }
            var run = scenarioService.executeScenario(workspaceId, scenarioId, userInput, runService, true);
            // Add timeline entry for execution resume
            record.addTimelineEntry("EXECUTION_RESUMED", "system", "审批通过，执行已恢复",
                Map.of("runId", run.getRunId(), "scenarioId", scenarioId));
            record.setRunId(run.getRunId());
            service.updateApproval(record);
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

    /**
     * Resolve an approval and auto-resume execution if:
     * - action is "approve" AND the approval has a linked scenario
     * - action is "reject" AND the approval has a linked run (mark it FAILED)
     */
    private static void resolveWithAutoResume(Context ctx, BusinessApprovalService service, String action,
                                               ScenarioService scenarioService,
                                               com.nousresearch.hermes.business.run.BusinessRunService runService,
                                               ToolApprovalCoordinator toolApprovalCoordinator) {
        String workspaceId = ctx.pathParam("workspaceId");
        String approvalId = ctx.pathParam("approvalId");
        JSONObject body = parseBody(ctx);
        String actor = body.getString("actor");
        String reason = body.getString("reason");
        try {
            BusinessApprovalRecord record = switch (action) {
                case "approve" -> service.approve(workspaceId, approvalId, actor, reason);
                case "reject" -> service.reject(workspaceId, approvalId, actor, reason);
                default -> throw new IllegalArgumentException("Unsupported action for auto-resume: " + action);
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("workspaceId", workspaceId);
            response.put("approvalId", approvalId);
            response.put("status", record.getStatus());
            response.put("approval", record);
            response.put("message", "Approval " + record.getStatus().toLowerCase().replace('_', ' '));

            // Tool-level approval: if metadata.type == "tool-call", resume the agent's tool call
            boolean isToolApproval = record.getMetadata() != null
                && "tool-call".equals(record.getMetadata().get("type"));

            if (isToolApproval && toolApprovalCoordinator != null
                    && toolApprovalCoordinator.isPending(approvalId)) {
                try {
                    boolean approved = "approve".equals(action);
                    String result = toolApprovalCoordinator.resumeToolApproval(approvalId, approved, reason);
                    record.addTimelineEntry(approved ? "TOOL_APPROVED" : "TOOL_REJECTED", "system",
                        approved ? "工具调用已批准并恢复执行" : "工具调用已拒绝",
                        Map.of("agentResult", result != null
                            ? (result.length() > 200 ? result.substring(0, 200) + "..." : result)
                            : "no result"));
                    service.updateApproval(record);
                    response.put("toolApproval", true);
                    response.put("agentResult", result);
                    response.put("message", "Tool approval " + (approved ? "approved" : "rejected") + " — agent execution resumed");
                } catch (Exception toolEx) {
                    logger.warn("Tool approval resume failed for {}: {}", approvalId, toolEx.getMessage());
                    response.put("toolApproval", true);
                    response.put("toolResumeError", toolEx.getMessage());
                }
            }
            // Scenario-level: Auto-resume if approved and has linked scenario + run
            else if ("approve".equals(action) && scenarioService != null && runService != null
                    && record.getScenarioId() != null && !record.getScenarioId().isBlank()) {
                try {
                    String userInput = record.getEvidence() != null && record.getEvidence().get("userInput") != null
                        ? String.valueOf(record.getEvidence().get("userInput"))
                        : "";
                    var run = scenarioService.executeScenario(workspaceId, record.getScenarioId(), userInput, runService, true);
                    record.setRunId(run.getRunId());
                    record.addTimelineEntry("EXECUTION_RESUMED", "system", "审批通过，执行已自动恢复",
                        Map.of("runId", run.getRunId(), "scenarioId", record.getScenarioId()));
                    service.updateApproval(record);
                    response.put("runId", run.getRunId());
                    response.put("run", run);
                    response.put("autoResumed", true);
                    response.put("message", "Approval approved — execution auto-resumed");
                    ctx.status(201);
                } catch (Exception execEx) {
                    logger.warn("Approval approved but auto-resume failed: {}", execEx.getMessage());
                    response.put("autoResumed", false);
                    response.put("autoResumeError", execEx.getMessage());
                    ctx.status(200);
                }
            }
            // If rejected and has a linked run, mark the run as FAILED
            else if ("reject".equals(action) && runService != null
                    && record.getRunId() != null && !record.getRunId().isBlank()) {
                try {
                    runService.updateRunStatus(workspaceId, record.getRunId(),
                        com.nousresearch.hermes.business.run.BusinessRunService.FAILED,
                        "审批被拒绝：" + (reason != null ? reason : "未提供原因"));
                    response.put("runStatusUpdated", true);
                    response.put("message", "Approval rejected — linked run marked as FAILED");
                } catch (Exception runEx) {
                    logger.warn("Failed to update linked run status after rejection: {}", runEx.getMessage());
                }
            }

            ctx.json(response);
        } catch (WorkspaceService.WorkspaceNotFoundException | BusinessApprovalService.BusinessApprovalNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        } catch (BusinessApprovalService.BusinessApprovalAlreadyResolvedException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        } catch (Exception e) {
            logger.error("Failed to resolve approval with auto-resume", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "approvalId", approvalId));
        }
    }

    /** SSE endpoint: real-time approval events for an entire workspace (approval center feed). */
    static void streamApprovals(BusinessApprovalService service, SseClient client) {
        String workspaceId = client.ctx().pathParam("workspaceId");
        client.keepAlive();

        // Send initial state: list of pending approvals count
        try {
            var pending = service.listApprovals(workspaceId, "PENDING");
            JSONObject init = new JSONObject();
            init.put("type", "state");
            init.put("workspaceId", workspaceId);
            init.put("pendingCount", pending.size());
            init.put("pending", pending.stream().map(a -> {
                JSONObject o = new JSONObject();
                o.put("approvalId", a.getApprovalId());
                o.put("title", a.getTitle());
                o.put("teamId", a.getTeamId());
                o.put("riskLevel", a.getRiskLevel());
                o.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
                return o;
            }).toList());
            client.sendEvent("state", init.toJSONString());
        } catch (Exception e) {
            client.sendEvent("error", "{\"error\":\"" + escapeJsonStr(e.getMessage()) + "\"}");
            client.close();
            return;
        }

        Consumer<BusinessApprovalService.ApprovalEvent> subscriber = event -> {
            try {
                client.sendEvent(
                    event.type().name().toLowerCase().replace('_', '.'),
                    approvalEventToJson(event)
                );
            } catch (Exception ex) {
                logger.debug("SSE send failed for approval {}: {}", event.approvalId(), ex.getMessage());
            }
        };

        service.subscribeWorkspace(workspaceId, subscriber);
        client.onClose(() -> {
            // Workspace subscribers are lightweight; no explicit unsub needed for workspace
            // (CopyOnWriteArrayList handles it, but we don't have a reference here for per-workspace)
        });
    }

    /** SSE endpoint: real-time events for a single approval (detail view). */
    static void streamApprovalDetail(BusinessApprovalService service, SseClient client) {
        String workspaceId = client.ctx().pathParam("workspaceId");
        String approvalId = client.ctx().pathParam("approvalId");
        client.keepAlive();

        try {
            var record = service.requireApproval(workspaceId, approvalId);
            client.sendEvent("state", approvalToJson(record));
        } catch (Exception e) {
            client.sendEvent("error", "{\"error\":\"" + escapeJsonStr(e.getMessage()) + "\"}");
            client.close();
            return;
        }

        Consumer<BusinessApprovalService.ApprovalEvent> subscriber = event -> {
            try {
                client.sendEvent(
                    event.type().name().toLowerCase().replace('_', '.'),
                    approvalEventToJson(event)
                );
                // Close after terminal events
                if (event.type() == BusinessApprovalService.ApprovalEventType.APPROVED
                    || event.type() == BusinessApprovalService.ApprovalEventType.REJECTED) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    client.close();
                }
            } catch (Exception ex) {
                logger.debug("SSE send failed for approval {}: {}", approvalId, ex.getMessage());
            }
        };

        service.subscribeApproval(workspaceId, approvalId, subscriber);
        client.onClose(() -> service.unsubscribeApproval(approvalId, subscriber));
    }

    private static String approvalEventToJson(BusinessApprovalService.ApprovalEvent event) {
        JSONObject obj = new JSONObject();
        obj.put("approvalId", event.approvalId());
        obj.put("workspaceId", event.workspaceId());
        obj.put("teamId", event.teamId());
        obj.put("type", event.type().name());
        obj.put("title", event.title());
        obj.put("status", event.status());
        obj.put("actor", event.actor());
        obj.put("reason", event.reason());
        obj.put("data", event.data());
        obj.put("timestamp", event.timestamp());
        return obj.toJSONString();
    }

    private static String approvalToJson(BusinessApprovalRecord record) {
        JSONObject obj = new JSONObject();
        obj.put("approvalId", record.getApprovalId());
        obj.put("workspaceId", record.getWorkspaceId());
        obj.put("teamId", record.getTeamId());
        obj.put("runId", record.getRunId());
        obj.put("scenarioId", record.getScenarioId());
        obj.put("title", record.getTitle());
        obj.put("summary", record.getSummary());
        obj.put("status", record.getStatus());
        obj.put("riskLevel", record.getRiskLevel());
        obj.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
        obj.put("resolvedAt", record.getResolvedAt() != null ? record.getResolvedAt().toString() : null);
        obj.put("resolvedBy", record.getResolvedBy());
        return obj.toJSONString();
    }

    private static String escapeJsonStr(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }
}
