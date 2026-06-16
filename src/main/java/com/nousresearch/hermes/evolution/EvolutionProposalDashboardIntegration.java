package com.nousresearch.hermes.evolution;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Business evolution proposal routes. */
public final class EvolutionProposalDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(EvolutionProposalDashboardIntegration.class);

    private EvolutionProposalDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, EvolutionProposalService service) {
        logger.info("Registering Evolution Proposal routes");
        app.get("/api/v1/workspaces/{workspaceId}/evolution-proposals", ctx -> listProposals(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/evolution-proposals", ctx -> createProposal(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/evolution-proposals/{proposalId}", ctx -> getProposal(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/evolution-proposals/{proposalId}/evaluate", ctx -> evaluate(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/evolution-proposals/{proposalId}/request-approval", ctx -> requestApproval(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/evolution-proposals/{proposalId}/approve", ctx -> approve(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/evolution-proposals/{proposalId}/reject", ctx -> reject(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/evolution-proposals/{proposalId}/apply", ctx -> apply(ctx, service));
    }

    static void listProposals(Context ctx, EvolutionProposalService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String status = ctx.queryParam("status");
        try {
            var proposals = service.listProposals(workspaceId, status);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "proposals", proposals, "total", proposals.size()));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createProposal(Context ctx, EvolutionProposalService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String proposalId = firstNonBlank(body.getString("proposalId"), body.getString("id"));
        try {
            EvolutionProposalRecord record = service.createProposal(
                workspaceId,
                proposalId,
                body.getString("scenarioId"),
                body.getString("teamId"),
                body.getString("sourceInsightId"),
                body.getString("title"),
                body.getString("finding"),
                body.getString("proposedChange"),
                body.getString("expectedBenefit"),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("evidence")),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "proposalId", record.getProposalId(), "proposal", record, "message", "Evolution proposal created"));
        } catch (WorkspaceService.WorkspaceNotFoundException | EvolutionProposalService.EvolutionProposalAlreadyExistsException e) {
            int status = e instanceof EvolutionProposalService.EvolutionProposalAlreadyExistsException ? 409 : 404;
            ctx.status(status).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId == null ? "" : proposalId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId == null ? "" : proposalId));
        } catch (Exception e) {
            logger.error("Failed to create evolution proposal", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId == null ? "" : proposalId));
        }
    }

    static void getProposal(Context ctx, EvolutionProposalService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String proposalId = ctx.pathParam("proposalId");
        try {
            EvolutionProposalRecord record = service.requireProposal(workspaceId, proposalId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "proposalId", proposalId, "proposal", record));
        } catch (WorkspaceService.WorkspaceNotFoundException | EvolutionProposalService.EvolutionProposalNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId));
        }
    }

    static void evaluate(Context ctx, EvolutionProposalService service) {
        transition(ctx, () -> service.startEvaluation(ctx.pathParam("workspaceId"), ctx.pathParam("proposalId")), "Proposal evaluation started");
    }

    static void requestApproval(Context ctx, EvolutionProposalService service) {
        JSONObject body = parseBody(ctx);
        transition(ctx, () -> service.requestApproval(ctx.pathParam("workspaceId"), ctx.pathParam("proposalId"), body.getString("approvalId")), "Proposal needs approval");
    }

    static void approve(Context ctx, EvolutionProposalService service) {
        JSONObject body = parseBody(ctx);
        transition(ctx, () -> service.approve(ctx.pathParam("workspaceId"), ctx.pathParam("proposalId"), body.getString("actor"), body.getString("reason")), "Proposal approved");
    }

    static void reject(Context ctx, EvolutionProposalService service) {
        JSONObject body = parseBody(ctx);
        transition(ctx, () -> service.reject(ctx.pathParam("workspaceId"), ctx.pathParam("proposalId"), body.getString("actor"), body.getString("reason")), "Proposal rejected");
    }

    static void apply(Context ctx, EvolutionProposalService service) {
        JSONObject body = parseBody(ctx);
        transition(ctx, () -> service.apply(ctx.pathParam("workspaceId"), ctx.pathParam("proposalId"), body.getString("targetTeamId")), "Proposal applied");
    }

    private static void transition(Context ctx, ProposalAction action, String message) {
        String workspaceId = ctx.pathParam("workspaceId");
        String proposalId = ctx.pathParam("proposalId");
        try {
            EvolutionProposalRecord record = action.run();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("workspaceId", workspaceId);
            response.put("proposalId", proposalId);
            response.put("status", record.getStatus());
            response.put("proposal", record);
            response.put("message", message);
            ctx.status(200).json(response);
        } catch (WorkspaceService.WorkspaceNotFoundException | EvolutionProposalService.EvolutionProposalNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId));
        } catch (EvolutionProposalService.InvalidEvolutionProposalTransitionException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId));
        } catch (Exception e) {
            logger.error("Failed to transition evolution proposal", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "proposalId", proposalId));
        }
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ProposalAction {
        EvolutionProposalRecord run();
    }
}
