package com.nousresearch.hermes.acp;

import com.alibaba.fastjson2.JSONObject;
import io.javalin.websocket.WsContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP Session implementation.
 * Mirrors Python acp_adapter/session.py
 */
public class AcpSession {
    private final String sessionId;
    private final String parentId;
    private final JSONObject config;
    private final Map<String, Object> state;
    private final List<WsContext> webSocketConnections;
    private final List<Map<String, Object>> history;
    
    private String model;
    private String mode;
    private boolean active;
    private boolean aborted;
    
    public AcpSession(String sessionId, JSONObject config) {
        this(sessionId, null, config);
    }
    
    public AcpSession(String sessionId, String parentId, JSONObject config) {
        this.sessionId = sessionId;
        this.parentId = parentId;
        this.config = config != null ? config : new JSONObject();
        this.state = new ConcurrentHashMap<>();
        this.webSocketConnections = new ArrayList<>();
        this.history = new ArrayList<>();
        this.model = this.config.getString("model");
        this.mode = this.config.getString("mode");
        this.active = true;
        this.aborted = false;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public String getParentId() {
        return parentId;
    }
    
    public JSONObject getConfig() {
        return config;
    }
    
    public void setConfig(JSONObject config) {
        this.config.putAll(config);
    }
    
    public String getModel() {
        return model;
    }
    
    public void setModel(String model) {
        this.model = model;
    }
    
    public String getMode() {
        return mode;
    }
    
    public void setMode(String mode) {
        this.mode = mode;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public boolean isAborted() {
        return aborted;
    }
    
    public void resume() {
        this.active = true;
        this.aborted = false;
    }
    
    public void abort() {
        this.aborted = true;
        this.active = false;
    }
    
    public void loadState(JSONObject state) {
        this.state.clear();
        for (String key : state.keySet()) {
            this.state.put(key, state.get(key));
        }
    }
    
    public Map<String, Object> getState() {
        return new HashMap<>(state);
    }
    
    public String runCommand(String command, JSONObject params) {
        // Add to history
        Map<String, Object> entry = new HashMap<>();
        entry.put("command", command);
        entry.put("params", params);
        entry.put("timestamp", System.currentTimeMillis());
        history.add(entry);
        
        // Execute command logic here
        // This would integrate with HermesAgent
        return "Command executed: " + command;
    }
    
    public void addWebSocketConnection(WsContext ctx) {
        webSocketConnections.add(ctx);
    }
    
    public void removeWebSocketConnection(WsContext ctx) {
        webSocketConnections.remove(ctx);
    }
    
    public void handleWebSocketMessage(String message) {
        // Process incoming WebSocket messages
        for (WsContext ctx : webSocketConnections) {
            ctx.send("Received: " + message);
        }
    }
    
    public void broadcastEvent(String eventType, Object data) {
        JSONObject event = new JSONObject();
        event.put("type", eventType);
        event.put("data", data);
        event.put("session_id", sessionId);
        event.put("timestamp", System.currentTimeMillis());
        
        String eventJson = event.toJSONString();
        for (WsContext ctx : webSocketConnections) {
            ctx.send(eventJson);
        }
    }
    
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("session_id", sessionId);
        info.put("parent_id", parentId);
        info.put("model", model);
        info.put("mode", mode);
        info.put("active", active);
        info.put("aborted", aborted);
        info.put("config", config);
        info.put("state", state);
        info.put("history_size", history.size());
        info.put("connections", webSocketConnections.size());
        return info;
    }
    
    public Map<String, Object> getCapabilities() {
        Map<String, Object> caps = new HashMap<>();
        caps.put("tools", true);
        caps.put("resources", true);
        caps.put("prompts", true);
        caps.put("streaming", true);
        caps.put("forking", true);
        return caps;
    }
    
    public AcpSession fork(String newSessionId, JSONObject forkConfig) {
        JSONObject mergedConfig = new JSONObject();
        mergedConfig.putAll(this.config);
        if (forkConfig != null) {
            mergedConfig.putAll(forkConfig);
        }
        
        AcpSession forked = new AcpSession(newSessionId, this.sessionId, mergedConfig);
        forked.state.putAll(this.state);
        return forked;
    }
}
