package com.nousresearch.hermes.acp;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ACP Events handling.
 * Mirrors Python acp_adapter/events.py
 * Bridges AIAgent events to ACP notifications.
 */
public class AcpEvents {
    private static final Logger logger = LoggerFactory.getLogger(AcpEvents.class);
    
    private final Map<String, Consumer<JSONObject>> eventHandlers;
    private final AcpSessionManager sessionManager;
    
    public AcpEvents(AcpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.eventHandlers = new ConcurrentHashMap<>();
    }
    
    /**
     * Register an event handler for a session.
     */
    public void registerHandler(String sessionId, Consumer<JSONObject> handler) {
        eventHandlers.put(sessionId, handler);
    }
    
    /**
     * Unregister an event handler.
     */
    public void unregisterHandler(String sessionId) {
        eventHandlers.remove(sessionId);
    }
    
    /**
     * Emit an event to a session.
     */
    public void emit(String sessionId, String eventType, JSONObject data) {
        Consumer<JSONObject> handler = eventHandlers.get(sessionId);
        if (handler != null) {
            JSONObject event = new JSONObject();
            event.put("type", eventType);
            event.put("data", data);
            event.put("session_id", sessionId);
            event.put("timestamp", System.currentTimeMillis());
            
            try {
                handler.accept(event);
            } catch (Exception e) {
                logger.error("Error emitting event to session {}", sessionId, e);
            }
        }
        
        // Also broadcast via WebSocket if session exists
        AcpSession session = sessionManager.getSession(sessionId);
        if (session != null) {
            session.broadcastEvent(eventType, data);
        }
    }
    
    /**
     * Create a tool progress callback for AIAgent.
     */
    public ToolProgressCallback createToolProgressCallback(String sessionId) {
        return new ToolProgressCallback(sessionId, this);
    }
    
    /**
     * Create a message callback for AIAgent.
     */
    public MessageCallback createMessageCallback(String sessionId) {
        return new MessageCallback(sessionId, this);
    }
    
    /**
     * Callback for tool progress events.
     */
    public static class ToolProgressCallback {
        private final String sessionId;
        private final AcpEvents events;
        
        public ToolProgressCallback(String sessionId, AcpEvents events) {
            this.sessionId = sessionId;
            this.events = events;
        }
        
        public void onToolStarted(String toolName, String preview, Map<String, Object> args) {
            JSONObject data = new JSONObject();
            data.put("event", "tool.started");
            data.put("tool_name", toolName);
            data.put("preview", preview);
            data.put("arguments", args);
            
            events.emit(sessionId, "tool_call_start", data);
        }
        
        public void onToolCompleted(String toolName, String result, long durationMs) {
            JSONObject data = new JSONObject();
            data.put("event", "tool.completed");
            data.put("tool_name", toolName);
            data.put("result", result);
            data.put("duration_ms", durationMs);
            
            events.emit(sessionId, "tool_call_complete", data);
        }
        
        public void onToolError(String toolName, String error) {
            JSONObject data = new JSONObject();
            data.put("event", "tool.error");
            data.put("tool_name", toolName);
            data.put("error", error);
            
            events.emit(sessionId, "tool_call_error", data);
        }
    }
    
    /**
     * Callback for message events.
     */
    public static class MessageCallback {
        private final String sessionId;
        private final AcpEvents events;
        
        public MessageCallback(String sessionId, AcpEvents events) {
            this.sessionId = sessionId;
            this.events = events;
        }
        
        public void onMessageStart() {
            JSONObject data = new JSONObject();
            data.put("event", "message.start");
            
            events.emit(sessionId, "message_start", data);
        }
        
        public void onMessageDelta(String content) {
            JSONObject data = new JSONObject();
            data.put("event", "message.delta");
            data.put("content", content);
            
            events.emit(sessionId, "message_delta", data);
        }
        
        public void onMessageComplete(String fullContent) {
            JSONObject data = new JSONObject();
            data.put("event", "message.complete");
            data.put("content", fullContent);
            
            events.emit(sessionId, "message_complete", data);
        }
    }
}
