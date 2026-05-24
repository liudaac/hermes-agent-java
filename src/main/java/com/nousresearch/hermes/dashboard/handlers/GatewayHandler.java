package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for gateway control and action status API endpoints.
 */
public class GatewayHandler {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHandler.class);

    // Track actions exposed through /api/actions/{name}/status.
    private final Map<String, ActionStatus> runningActions = new ConcurrentHashMap<>();

    public GatewayHandler() {
    }

    /**
     * POST /api/gateway/restart - Restart the gateway.
     *
     * Restart requires an external supervisor or an explicit GatewayRunner control API.
     * Do not fake success here: returning 501 is safer than pretending a restart happened.
     */
    public void restartGateway(Context ctx) {
        try {
            Map<String, Object> result = markUnsupported(
                "gateway-restart",
                "Gateway restart is not wired to a runtime supervisor yet. Use the process/service manager directly."
            );
            ctx.status(501).json(result);
        } catch (Exception e) {
            logger.error("Error reporting gateway restart unsupported: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    /**
     * POST /api/hermes/update - Update Hermes.
     *
     * Updating the running application is a privileged external action and must be
     * implemented by an explicit updater/supervisor, not simulated in the dashboard.
     */
    public void updateHermes(Context ctx) {
        try {
            Map<String, Object> result = markUnsupported(
                "hermes-update",
                "Hermes update is not wired to a trusted updater yet. Run the update command outside the dashboard."
            );
            ctx.status(501).json(result);
        } catch (Exception e) {
            logger.error("Error reporting Hermes update unsupported: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    /**
     * GET /api/actions/{name}/status - Get action status
     */
    public void getActionStatus(Context ctx) {
        try {
            String actionName = ctx.pathParam("name");
            String linesStr = ctx.queryParam("lines");
            int lines = linesStr != null ? Integer.parseInt(linesStr) : 200;

            ActionStatus status = runningActions.get(actionName);

            if (status == null) {
                ctx.json(Map.of(
                    "name", actionName,
                    "running", false,
                    "exit_code", (Integer) null,
                    "pid", (Integer) null,
                    "lines", List.of()
                ));
                return;
            }

            ctx.json(toActionStatusMap(status, lines));

            // Clean up completed actions older than 5 minutes
            if (!status.running && status.exitCode != null) {
                if (System.currentTimeMillis() - status.startTime > 5 * 60 * 1000) {
                    runningActions.remove(actionName);
                }
            }
        } catch (Exception e) {
            logger.error("Error getting action status: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    Map<String, Object> markUnsupported(String actionName, String message) {
        ActionStatus status = new ActionStatus();
        status.name = actionName;
        status.running = false;
        status.exitCode = 2;
        status.pid = null;
        status.startTime = System.currentTimeMillis();
        status.lines = new ArrayList<>(List.of("Unsupported action: " + message));
        runningActions.put(actionName, status);

        Map<String, Object> response = toActionStatusMap(status, 200);
        response.put("ok", false);
        response.put("unsupported", true);
        response.put("message", message);
        return response;
    }

    Map<String, Object> toActionStatusMap(ActionStatus status, int lines) {
        List<String> outputLines = status.lines != null ? status.lines : List.of();
        if (outputLines.size() > lines) {
            outputLines = outputLines.subList(outputLines.size() - lines, outputLines.size());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("name", status.name);
        result.put("running", status.running);
        result.put("exit_code", status.exitCode);
        result.put("pid", status.pid);
        result.put("lines", outputLines);
        return result;
    }

    // Data class
    static class ActionStatus {
        public String name;
        public boolean running;
        public Integer exitCode;
        public Integer pid;
        public long startTime;
        public List<String> lines;
    }
}
