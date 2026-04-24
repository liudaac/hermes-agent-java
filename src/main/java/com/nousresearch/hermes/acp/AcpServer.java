package com.nousresearch.hermes.acp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP (Agent Communication Protocol) Server implementation.
 * Mirrors Python acp_adapter/server.py
 * Exposes Hermes Agent via the Agent Client Protocol.
 */
public class AcpServer {
    private static final Logger logger = LoggerFactory.getLogger(AcpServer.class);
    
    private final Javalin app;
    private final int port;
    private final AcpSessionManager sessionManager;
    private final AcpPermissions permissions;
    private final Map<String, AcpSession> sessions = new ConcurrentHashMap<>();
    
    public AcpServer(int port) {
        this.port = port;
        this.sessionManager = new AcpSessionManager();
        this.permissions = new AcpPermissions();
        this.app = createApp();
    }
    
    private Javalin createApp() {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });
        
        // Health check
        app.get("/health", this::handleHealth);
        
        // ACP Protocol endpoints
        app.post("/acp/initialize", this::handleInitialize);
        app.post("/acp/authenticate", this::handleAuthenticate);
        app.post("/acp/sessions/new", this::handleNewSession);
        app.post("/acp/sessions/{id}/fork", this::handleForkSession);
        app.post("/acp/sessions/{id}/resume", this::handleResumeSession);
        app.post("/acp/sessions/{id}/load", this::handleLoadSession);
        app.get("/acp/sessions", this::handleListSessions);
        app.get("/acp/sessions/{id}", this::handleGetSession);
        app.post("/acp/sessions/{id}/config", this::handleSetSessionConfig);
        app.post("/acp/sessions/{id}/model", this::handleSetSessionModel);
        app.post("/acp/sessions/{id}/mode", this::handleSetSessionMode);
        app.post("/acp/sessions/{id}/command", this::handleRunCommand);
        app.post("/acp/sessions/{id}/abort", this::handleAbortSession);
        app.ws("/acp/sessions/{id}/events", this::handleWebSocket);
        
        return app;
    }
    
    public void start() {
        app.start(port);
        logger.info("ACP Server started on port {}", port);
    }
    
    public void stop() {
        app.stop();
        logger.info("ACP Server stopped");
    }
    
    // Handler methods
    private void handleHealth(Context ctx) {
        ctx.json(Map.of("status", "healthy", "protocol", "acp-0.1.0"));
    }
    
    private void handleInitialize(Context ctx) {
        JSONObject body = JSON.parseObject(ctx.body());
        logger.debug("Initialize request: {}", body);
        
        Map<String, Object> response = new HashMap<>();
        response.put("protocol_version", "0.1.0");
        response.put("server_info", Map.of(
            "name", "hermes-java-acp",
            "version", "0.1.0"
        ));
        response.put("capabilities", Map.of(
            "sessions", true,
            "forking", true,
            "tools", true,
            "resources", true,
            "prompts", true
        ));
        
        ctx.json(response);
    }
    
    private void handleAuthenticate(Context ctx) {
        JSONObject body = JSON.parseObject(ctx.body());
        String token = body.getString("token");
        
        if (permissions.validateToken(token)) {
            ctx.json(Map.of(
                "authenticated", true,
                "session_token", UUID.randomUUID().toString()
            ));
        } else {
            ctx.status(401).json(Map.of("error", "Invalid token"));
        }
    }
    
    private void handleNewSession(Context ctx) {
        JSONObject body = JSON.parseObject(ctx.body());
        String sessionId = UUID.randomUUID().toString();
        
        AcpSession session = new AcpSession(sessionId, body);
        sessions.put(sessionId, session);
        
        ctx.json(Map.of(
            "session_id", sessionId,
            "status", "created",
            "capabilities", session.getCapabilities()
        ));
    }
    
    private void handleForkSession(Context ctx) {
        String parentId = ctx.pathParam("id");
        JSONObject body = JSON.parseObject(ctx.body());
        
        AcpSession parent = sessions.get(parentId);
        if (parent == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        String newSessionId = UUID.randomUUID().toString();
        AcpSession forked = parent.fork(newSessionId, body);
        sessions.put(newSessionId, forked);
        
        ctx.json(Map.of(
            "session_id", newSessionId,
            "parent_id", parentId,
            "status", "forked"
        ));
    }
    
    private void handleResumeSession(Context ctx) {
        String sessionId = ctx.pathParam("id");
        AcpSession session = sessions.get(sessionId);
        
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        session.resume();
        ctx.json(Map.of("session_id", sessionId, "status", "resumed"));
    }
    
    private void handleLoadSession(Context ctx) {
        String sessionId = ctx.pathParam("id");
        JSONObject body = JSON.parseObject(ctx.body());
        
        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        session.loadState(body);
        ctx.json(Map.of("session_id", sessionId, "status", "loaded"));
    }
    
    private void handleListSessions(Context ctx) {
        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (AcpSession session : sessions.values()) {
            sessionList.add(session.getInfo());
        }
        ctx.json(Map.of("sessions", sessionList));
    }
    
    private void handleGetSession(Context ctx) {
        String sessionId = ctx.pathParam("id");
        AcpSession session = sessions.get(sessionId);
        
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        ctx.json(session.getInfo());
    }
    
    private void handleSetSessionConfig(Context ctx) {
        String sessionId = ctx.pathParam("id");
        JSONObject body = JSON.parseObject(ctx.body());
        
        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        session.setConfig(body);
        ctx.json(Map.of("session_id", sessionId, "status", "config_updated"));
    }
    
    private void handleSetSessionModel(Context ctx) {
        String sessionId = ctx.pathParam("id");
        JSONObject body = JSON.parseObject(ctx.body());
        
        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        session.setModel(body.getString("model"));
        ctx.json(Map.of("session_id", sessionId, "status", "model_updated"));
    }
    
    private void handleSetSessionMode(Context ctx) {
        String sessionId = ctx.pathParam("id");
        JSONObject body = JSON.parseObject(ctx.body());
        
        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        session.setMode(body.getString("mode"));
        ctx.json(Map.of("session_id", sessionId, "status", "mode_updated"));
    }
    
    private void handleRunCommand(Context ctx) {
        String sessionId = ctx.pathParam("id");
        JSONObject body = JSON.parseObject(ctx.body());
        
        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        String command = body.getString("command");
        String result = session.runCommand(command, body);
        
        ctx.json(Map.of(
            "session_id", sessionId,
            "result", result,
            "status", "completed"
        ));
    }
    
    private void handleAbortSession(Context ctx) {
        String sessionId = ctx.pathParam("id");
        AcpSession session = sessions.get(sessionId);
        
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        
        session.abort();
        ctx.json(Map.of("session_id", sessionId, "status", "aborted"));
    }
    
    private void handleWebSocket(WsConfig ws) {
        ws.onConnect(ctx -> {
            String sessionId = ctx.pathParam("id");
            logger.debug("WebSocket connected for session: {}", sessionId);
            
            AcpSession session = sessions.get(sessionId);
            if (session != null) {
                session.addWebSocketConnection(ctx);
            }
        });
        
        ws.onMessage(ctx -> {
            String message = ctx.message();
            String sessionId = ctx.pathParam("id");
            logger.debug("WebSocket message for session {}: {}", sessionId, message);

            AcpSession session = sessions.get(sessionId);
            if (session != null) {
                session.handleWebSocketMessage(message);
            }
        });

        ws.onClose(ctx -> {
            String sessionId = ctx.pathParam("id");
            logger.debug("WebSocket closed for session: {}", sessionId);

            AcpSession session = sessions.get(sessionId);
            if (session != null) {
                session.removeWebSocketConnection(ctx);
            }
        });
    }
}
