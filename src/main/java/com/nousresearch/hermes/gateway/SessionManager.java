package com.nousresearch.hermes.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session manager for persistent conversation state.
 * Aligned with Python Hermes gateway/session.py
 * 
 * Features:
 * - PII redaction (hashed IDs)
 * - Session persistence to disk
 * - Automatic session cleanup
 */
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Path sessionsDir;
    private final Map<String, Session> activeSessions;
    
    public SessionManager(Path dataDir) {
        this.sessionsDir = dataDir.resolve("memory").resolve("sessions");
        this.activeSessions = new ConcurrentHashMap<>();
        
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            logger.error("Failed to create sessions directory: {}", e.getMessage());
        }
    }
    
    /**
     * Get or create a session.
     */
    public Session getSession(String sessionId) {
        return activeSessions.computeIfAbsent(sessionId, id -> {
            try {
                return loadSession(id);
            } catch (IOException e) {
                return new Session(id);
            }
        });
    }
    
    /**
     * Get session by channel (for gateway integration).
     */
    public Session getSessionByChannel(String platform, String channelId) {
        String sessionId = platform + ":" + hashChatId(channelId);
        return getSession(sessionId);
    }
    
    /**
     * Save session to disk.
     */
    public void saveSession(Session session) throws IOException {
        Files.createDirectories(sessionsDir);
        Path sessionFile = sessionsDir.resolve(session.id + ".json");
        mapper.writeValue(sessionFile.toFile(), session.toJson());
        logger.info("Session saved: {} ({} messages)", session.id, session.messages.size());
    }
    
    /**
     * Load session from disk.
     */
    private Session loadSession(String sessionId) throws IOException {
        Path sessionFile = sessionsDir.resolve(sessionId + ".json");
        
        if (Files.exists(sessionFile)) {
            ObjectNode json = (ObjectNode) mapper.readTree(sessionFile.toFile());
            Session session = Session.fromJson(sessionId, json);
            logger.debug("Session loaded: {}", sessionId);
            return session;
        }
        
        return new Session(sessionId);
    }
    
    /**
     * Delete a session.
     */
    public void deleteSession(String sessionId) throws IOException {
        activeSessions.remove(sessionId);
        Path sessionFile = sessionsDir.resolve(sessionId + ".json");
        Files.deleteIfExists(sessionFile);
        logger.info("Session deleted: {}", sessionId);
    }
    
    /**
     * List all sessions.
     */
    public List<String> listSessions() throws IOException {
        List<String> sessions = new ArrayList<>();
        
        if (Files.exists(sessionsDir)) {
            try (var stream = Files.list(sessionsDir)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> sessions.add(p.getFileName().toString().replace(".json", "")));
            }
        }
        
        return sessions;
    }
    
    /**
     * Clean up old sessions.
     */
    public int cleanupOldSessions(int maxAgeDays) throws IOException {
        int cleaned = 0;
        long cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L);
        
        try (var stream = Files.list(sessionsDir)) {
            for (Path file : stream.toList()) {
                if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    Files.delete(file);
                    cleaned++;
                }
            }
        }
        
        logger.info("Cleaned up {} old sessions", cleaned);
        return cleaned;
    }
    
    /**
     * Persist all active sessions.
     */
    public void persistAll() {
        for (Session session : activeSessions.values()) {
            try {
                saveSession(session);
            } catch (IOException e) {
                logger.error("Failed to save session {}: {}", session.id, e.getMessage());
            }
        }
    }
    
    // PII redaction helpers - aligned with Python version
    
    /**
     * Hash an identifier to 12-char hex.
     */
    private String hashId(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return String.format("%012x", value.hashCode());
        }
    }
    
    /**
     * Hash a sender ID to user_<12hex>.
     */
    private String hashSenderId(String value) {
        return "user_" + hashId(value);
    }
    
    /**
     * Hash the numeric portion of a chat ID, preserving platform prefix.
     * e.g., "telegram:12345" -> "telegram:<hash>"
     */
    private String hashChatId(String value) {
        int colon = value.indexOf(':');
        if (colon > 0) {
            String prefix = value.substring(0, colon);
            return prefix + ":" + hashId(value.substring(colon + 1));
        }
        return hashId(value);
    }
    
    // ==================== Session Class ====================
    
    public static class Session {
        public final String id;
        public final List<Message> messages;
        public final Map<String, Object> metadata;
        public long lastActivity;
        public long promptTokens;
        public long completionTokens;
        public long cachedPromptTokens;
        public long reasoningTokens;
        public long totalTokens;
        public String lastModel;
        public final List<ToolCallRecord> toolCalls = new ArrayList<>();
        
        // Session source info (for context)
        public String platform;
        public String chatId;
        public String chatName;
        public String chatType; // "dm", "group", "channel", "thread"
        public String userId;
        public String userName;
        public String threadId;
        
        public Session(String id) {
            this.id = id;
            this.messages = new ArrayList<>();
            this.metadata = new HashMap<>();
            this.lastActivity = System.currentTimeMillis();
            this.chatType = "dm";
        }
        
        public void addMessage(String role, String content) {
            messages.add(new Message(role, content, System.currentTimeMillis()));
            lastActivity = System.currentTimeMillis();
            
            // Keep only last 100 messages
            if (messages.size() > 100) {
                messages.remove(0);
            }
        }
        
        public void setMetadata(String key, Object value) {
            metadata.put(key, value);
        }
        
        public Object getMetadata(String key) {
            return metadata.get(key);
        }

        public void recordUsage(String model, long prompt, long completion, long cached, long reasoning, long total) {
            if (model != null && !model.isBlank()) {
                this.lastModel = model;
            }
            this.promptTokens += Math.max(0, prompt);
            this.completionTokens += Math.max(0, completion);
            this.cachedPromptTokens += Math.max(0, cached);
            this.reasoningTokens += Math.max(0, reasoning);
            this.totalTokens += Math.max(0, total);
            this.lastActivity = System.currentTimeMillis();
        }

        public void recordToolCall(String tool, boolean ok, long durationMs) {
            toolCalls.add(new ToolCallRecord(tool, ok, durationMs, System.currentTimeMillis()));
            if (toolCalls.size() > 200) {
                toolCalls.remove(0);
            }
            this.lastActivity = System.currentTimeMillis();
        }
        
        public ObjectNode toJson() {
            ObjectNode json = mapper.createObjectNode();
            json.put("id", id);
            json.put("lastActivity", lastActivity);
            json.put("promptTokens", promptTokens);
            json.put("completionTokens", completionTokens);
            json.put("cachedPromptTokens", cachedPromptTokens);
            json.put("reasoningTokens", reasoningTokens);
            json.put("totalTokens", totalTokens);
            if (lastModel != null) {
                json.put("lastModel", lastModel);
            }
            var toolCallsArray = json.putArray("toolCalls");
            for (ToolCallRecord tc : toolCalls) {
                ObjectNode tcJson = toolCallsArray.addObject();
                tcJson.put("tool", tc.tool);
                tcJson.put("ok", tc.ok);
                tcJson.put("durationMs", tc.durationMs);
                tcJson.put("timestamp", tc.timestamp);
            }
            
            // Source info
            if (platform != null) json.put("platform", platform);
            if (chatId != null) json.put("chat_id", chatId);
            if (chatName != null) json.put("chat_name", chatName);
            if (chatType != null) json.put("chat_type", chatType);
            if (userId != null) json.put("user_id", userId);
            if (userName != null) json.put("user_name", userName);
            if (threadId != null) json.put("thread_id", threadId);
            
            var messagesArray = json.putArray("messages");
            for (Message msg : messages) {
                ObjectNode msgJson = messagesArray.addObject();
                msgJson.put("role", msg.role);
                msgJson.put("content", msg.content);
                msgJson.put("timestamp", msg.timestamp);
            }
            
            json.set("metadata", mapper.valueToTree(metadata));
            return json;
        }
        
        public static Session fromJson(String id, ObjectNode json) {
            Session session = new Session(id);
            session.lastActivity = json.path("lastActivity").asLong();
            session.promptTokens = json.path("promptTokens").asLong();
            session.completionTokens = json.path("completionTokens").asLong();
            session.cachedPromptTokens = json.path("cachedPromptTokens").asLong();
            session.reasoningTokens = json.path("reasoningTokens").asLong();
            session.totalTokens = json.path("totalTokens").asLong();
            session.lastModel = json.path("lastModel").asText(null);
            var toolCallsNode = json.path("toolCalls");
            for (var tcNode : toolCallsNode) {
                session.toolCalls.add(new ToolCallRecord(
                    tcNode.path("tool").asText(),
                    tcNode.path("ok").asBoolean(true),
                    tcNode.path("durationMs").asLong(),
                    tcNode.path("timestamp").asLong()
                ));
            }
            
            // Source info
            session.platform = json.path("platform").asText(null);
            session.chatId = json.path("chat_id").asText(null);
            session.chatName = json.path("chat_name").asText(null);
            session.chatType = json.path("chat_type").asText("dm");
            session.userId = json.path("user_id").asText(null);
            session.userName = json.path("user_name").asText(null);
            session.threadId = json.path("thread_id").asText(null);
            
            var messagesNode = json.path("messages");
            for (var msgNode : messagesNode) {
                session.messages.add(new Message(
                    msgNode.path("role").asText(),
                    msgNode.path("content").asText(),
                    msgNode.path("timestamp").asLong()
                ));
            }
            
            var metadataNode = json.path("metadata");
            metadataNode.fields().forEachRemaining(entry -> {
                session.metadata.put(entry.getKey(), entry.getValue());
            });
            
            return session;
        }
    }
    
    public record Message(String role, String content, long timestamp) {}

    public record ToolCallRecord(String tool, boolean ok, long durationMs, long timestamp) {}
}