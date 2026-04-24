package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for gateway control and action status API endpoints.
 */
public class GatewayHandler {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHandler.class);

    // Track running actions
    private final Map<String, ActionStatus> runningActions = new ConcurrentHashMap<>();

    public GatewayHandler() {
    }

    /**
     * POST /api/gateway/restart - Restart the gateway
     */
    public void restartGateway(Context ctx) {
        try {
            String actionName = "gateway-restart";

            // Check if already running
            if (runningActions.containsKey(actionName)) {
                ActionStatus existing = runningActions.get(actionName);
                ctx.json(createActionResponse(actionName, true, existing.pid));
                return;
            }

            // Start restart process
            ActionStatus status = new ActionStatus();
            status.name = actionName;
            status.running = true;
            status.pid = (int) (Math.random() * 10000) + 1000; // Simulated PID
            status.startTime = System.currentTimeMillis();
            status.lines = new ArrayList<>();

            runningActions.put(actionName, status);

            // Simulate async restart
            new Thread(() -> {
                try {
                    status.lines.add("Stopping gateway...");
                    Thread.sleep(1000);
                    status.lines.add("Gateway stopped");
                    Thread.sleep(500);
                    status.lines.add("Starting gateway...");
                    Thread.sleep(1500);
                    status.lines.add("Gateway started successfully");
                    status.running = false;
                    status.exitCode = 0;
                } catch (InterruptedException e) {
                    status.running = false;
                    status.exitCode = 1;
                    status.lines.add("Restart interrupted: " + e.getMessage());
                }
            }).start();

            ctx.json(createActionResponse(actionName, true, status.pid));
        } catch (Exception e) {
            logger.error("Error restarting gateway: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    /**
     * POST /api/hermes/update - Update Hermes
     */
    public void updateHermes(Context ctx) {
        try {
            String actionName = "hermes-update";

            // Check if already running
            if (runningActions.containsKey(actionName)) {
                ActionStatus existing = runningActions.get(actionName);
                ctx.json(createActionResponse(actionName, true, existing.pid));
                return;
            }

            // Start update process
            ActionStatus status = new ActionStatus();
            status.name = actionName;
            status.running = true;
            status.pid = (int) (Math.random() * 10000) + 1000;
            status.startTime = System.currentTimeMillis();
            status.lines = new ArrayList<>();

            runningActions.put(actionName, status);

            // Simulate async update
            new Thread(() -> {
                try {
                    status.lines.add("Checking for updates...");
                    Thread.sleep(1000);
                    status.lines.add("Found new version: 0.2.0");
                    Thread.sleep(500);
                    status.lines.add("Downloading...");
                    Thread.sleep(2000);
                    status.lines.add("Installing...");
                    Thread.sleep(2000);
                    status.lines.add("Update completed successfully");
                    status.running = false;
                    status.exitCode = 0;
                } catch (InterruptedException e) {
                    status.running = false;
                    status.exitCode = 1;
                    status.lines.add("Update interrupted: " + e.getMessage());
                }
            }).start();

            ctx.json(createActionResponse(actionName, true, status.pid));
        } catch (Exception e) {
            logger.error("Error updating Hermes: {}", e.getMessage());
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

            // Get last N lines
            List<String> outputLines = status.lines;
            if (outputLines.size() > lines) {
                outputLines = outputLines.subList(outputLines.size() - lines, outputLines.size());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("name", status.name);
            result.put("running", status.running);
            result.put("exit_code", status.exitCode);
            result.put("pid", status.pid);
            result.put("lines", outputLines);

            ctx.json(result);

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

    private Map<String, Object> createActionResponse(String name, boolean ok, int pid) {
        Map<String, Object> response = new HashMap<>();
        response.put("name", name);
        response.put("ok", ok);
        response.put("pid", pid);
        return response;
    }

    // Data class
    private static class ActionStatus {
        public String name;
        public boolean running;
        public Integer exitCode;
        public Integer pid;
        public long startTime;
        public List<String> lines;
    }
}
