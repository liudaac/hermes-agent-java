package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for session-related API endpoints.
 * Uses SQLite for session persistence.
 */
public class SessionHandler {
    private static final Logger logger = LoggerFactory.getLogger(SessionHandler.class);

    private final java.nio.file.Path dbPath;
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    // Session message cache (in-memory for performance)
    private final Map<String, List<SessionMessage>> sessionMessages = new ConcurrentHashMap<>();

    public SessionHandler() {
        this.dbPath = java.nio.file.Path.of(System.getProperty("user.home"), ".hermes", "sessions.db");
        initializeDatabase();
    }

    /**
     * Initialize SQLite database for sessions.
     */
    private void initializeDatabase() {
        try {
            java.nio.file.Files.createDirectories(dbPath.getParent());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                // Create sessions table
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT PRIMARY KEY,
                        source TEXT,
                        model TEXT,
                        title TEXT,
                        started_at INTEGER,
                        ended_at INTEGER,
                        last_active INTEGER,
                        is_active INTEGER DEFAULT 1,
                        message_count INTEGER DEFAULT 0,
                        tool_call_count INTEGER DEFAULT 0,
                        input_tokens INTEGER DEFAULT 0,
                        output_tokens INTEGER DEFAULT 0,
                        preview TEXT
                    )
                """);

                // Create messages table
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS session_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT,
                        role TEXT,
                        content TEXT,
                        tool_calls TEXT,
                        tool_name TEXT,
                        tool_call_id TEXT,
                        timestamp INTEGER,
                        FOREIGN KEY (session_id) REFERENCES sessions(id)
                    )
                """);

                // Create FTS5 search index
                conn.createStatement().execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS session_search USING fts5(
                        session_id, content, role, tokenize='porter'
                    )
                """);

                logger.info("Session database initialized at {}", dbPath);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize session database: {}", e.getMessage(), e);
        }
    }

    /**
     * GET /api/sessions - Get list of sessions
     */
    public void getSessions(Context ctx) {
        try {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);

            List<SessionInfo> sessions = new ArrayList<>();
            int total = 0;

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                // Get total count
                ResultSet countRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM sessions");
                total = countRs.getInt(1);

                // Get sessions
                PreparedStatement stmt = conn.prepareStatement("""
                    SELECT * FROM sessions
                    ORDER BY last_active DESC
                    LIMIT ? OFFSET ?
                """);
                stmt.setInt(1, limit);
                stmt.setInt(2, offset);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    sessions.add(mapResultSetToSession(rs));
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("sessions", sessions);
            result.put("total", total);
            result.put("limit", limit);
            result.put("offset", offset);

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error getting sessions: {}", e.getMessage());
            ctx.status(500).result("Error getting sessions");
        }
    }

    /**
     * GET /api/sessions/search - Search sessions
     */
    public void searchSessions(Context ctx) {
        try {
            String query = ctx.queryParam("q");
            if (query == null) query = "";
            if (query.isEmpty()) {
                ctx.json(Map.of("results", new ArrayList<>()));
                return;
            }

            List<SessionSearchResult> results = new ArrayList<>();

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                // Use FTS5 for full-text search
                PreparedStatement stmt = conn.prepareStatement("""
                    SELECT DISTINCT s.session_id, sm.content, sm.role,
                           ses.source, ses.model, ses.started_at
                    FROM session_search s
                    JOIN session_messages sm ON s.session_id = sm.session_id
                    JOIN sessions ses ON s.session_id = ses.id
                    WHERE session_search MATCH ?
                    LIMIT 50
                """);
                stmt.setString(1, query);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    SessionSearchResult result = new SessionSearchResult();
                    result.sessionId = rs.getString("session_id");
                    result.snippet = rs.getString("content");
                    result.role = rs.getString("role");
                    result.source = rs.getString("source");
                    result.model = rs.getString("model");
                    result.sessionStarted = rs.getLong("started_at");
                    results.add(result);
                }
            }

            ctx.json(Map.of("results", results));
        } catch (Exception e) {
            logger.error("Error searching sessions: {}", e.getMessage());
            ctx.json(Map.of("results", new ArrayList<>()));
        }
    }

    /**
     * GET /api/sessions/{id}/messages - Get messages for a session
     */
    public void getSessionMessages(Context ctx) {
        try {
            String sessionId = ctx.pathParam("id");

            List<SessionMessage> messages = new ArrayList<>();

            // First check in-memory cache for active sessions
            if (sessionMessages.containsKey(sessionId)) {
                messages = sessionMessages.get(sessionId);
            } else {
                // Load from database
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                    PreparedStatement stmt = conn.prepareStatement("""
                        SELECT * FROM session_messages
                        WHERE session_id = ?
                        ORDER BY timestamp ASC
                    """);
                    stmt.setString(1, sessionId);

                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        messages.add(mapResultSetToMessage(rs));
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("session_id", sessionId);
            result.put("messages", messages);

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error getting session messages: {}", e.getMessage());
            ctx.status(500).result("Error getting messages");
        }
    }

    /**
     * DELETE /api/sessions/{id} - Delete a session
     */
    public void deleteSession(Context ctx) {
        try {
            String sessionId = ctx.pathParam("id");

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                // Delete messages first (foreign key constraint)
                PreparedStatement deleteMessages = conn.prepareStatement(
                    "DELETE FROM session_messages WHERE session_id = ?"
                );
                deleteMessages.setString(1, sessionId);
                deleteMessages.executeUpdate();

                // Delete from search index
                PreparedStatement deleteSearch = conn.prepareStatement(
                    "DELETE FROM session_search WHERE session_id = ?"
                );
                deleteSearch.setString(1, sessionId);
                deleteSearch.executeUpdate();

                // Delete session
                PreparedStatement deleteSession = conn.prepareStatement(
                    "DELETE FROM sessions WHERE id = ?"
                );
                deleteSession.setString(1, sessionId);
                int deleted = deleteSession.executeUpdate();

                if (deleted > 0) {
                    // Remove from in-memory cache
                    activeSessions.remove(sessionId);
                    sessionMessages.remove(sessionId);

                    ctx.json(Map.of("ok", true));
                } else {
                    ctx.status(404).result("Session not found");
                }
            }
        } catch (Exception e) {
            logger.error("Error deleting session: {}", e.getMessage());
            ctx.status(500).result("Error deleting session");
        }
    }

    /**
     * Create or update a session.
     */
    public void saveSession(SessionInfo session) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            PreparedStatement stmt = conn.prepareStatement("""
                INSERT OR REPLACE INTO sessions
                (id, source, model, title, started_at, ended_at, last_active, is_active,
                 message_count, tool_call_count, input_tokens, output_tokens, preview)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """);

            stmt.setString(1, session.id);
            stmt.setString(2, session.source);
            stmt.setString(3, session.model);
            stmt.setString(4, session.title);
            stmt.setLong(5, session.startedAt);
            stmt.setObject(6, session.endedAt);
            stmt.setLong(7, session.lastActive);
            stmt.setInt(8, session.isActive ? 1 : 0);
            stmt.setInt(9, session.messageCount);
            stmt.setInt(10, session.toolCallCount);
            stmt.setInt(11, session.inputTokens);
            stmt.setInt(12, session.outputTokens);
            stmt.setString(13, session.preview);

            stmt.executeUpdate();

            // Update in-memory cache for active sessions
            if (session.isActive) {
                activeSessions.put(session.id, session);
            }
        } catch (Exception e) {
            logger.error("Error saving session: {}", e.getMessage());
        }
    }

    /**
     * Add a message to a session.
     */
    public void addMessage(String sessionId, SessionMessage message) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Insert message
            PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO session_messages
                (session_id, role, content, tool_calls, tool_name, tool_call_id, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """);

            stmt.setString(1, sessionId);
            stmt.setString(2, message.role);
            stmt.setString(3, message.content);
            stmt.setString(4, message.toolCalls != null ? JSON.toJSONString(message.toolCalls) : null);
            stmt.setString(5, message.toolName);
            stmt.setString(6, message.toolCallId);
            stmt.setLong(7, message.timestamp);

            stmt.executeUpdate();

            // Update search index
            PreparedStatement searchStmt = conn.prepareStatement("""
                INSERT INTO session_search (session_id, content, role)
                VALUES (?, ?, ?)
            """);
            searchStmt.setString(1, sessionId);
            searchStmt.setString(2, message.content);
            searchStmt.setString(3, message.role);
            searchStmt.executeUpdate();

            // Update in-memory cache
            sessionMessages.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);

            // Update session message count and last active
            PreparedStatement updateStmt = conn.prepareStatement("""
                UPDATE sessions
                SET message_count = message_count + 1, last_active = ?
                WHERE id = ?
            """);
            updateStmt.setLong(1, System.currentTimeMillis());
            updateStmt.setString(2, sessionId);
            updateStmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Error adding message: {}", e.getMessage());
        }
    }

    /**
     * Get count of active sessions.
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    private SessionInfo mapResultSetToSession(ResultSet rs) throws SQLException {
        SessionInfo session = new SessionInfo();
        session.id = rs.getString("id");
        session.source = rs.getString("source");
        session.model = rs.getString("model");
        session.title = rs.getString("title");
        session.startedAt = rs.getLong("started_at");
        session.endedAt = rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null;
        session.lastActive = rs.getLong("last_active");
        session.isActive = rs.getInt("is_active") == 1;
        session.messageCount = rs.getInt("message_count");
        session.toolCallCount = rs.getInt("tool_call_count");
        session.inputTokens = rs.getInt("input_tokens");
        session.outputTokens = rs.getInt("output_tokens");
        session.preview = rs.getString("preview");
        return session;
    }

    private SessionMessage mapResultSetToMessage(ResultSet rs) throws SQLException {
        SessionMessage message = new SessionMessage();
        message.role = rs.getString("role");
        message.content = rs.getString("content");

        String toolCallsJson = rs.getString("tool_calls");
        if (toolCallsJson != null) {
            message.toolCalls = JSON.parseArray(toolCallsJson, ToolCall.class);
        }

        message.toolName = rs.getString("tool_name");
        message.toolCallId = rs.getString("tool_call_id");
        message.timestamp = rs.getLong("timestamp");
        return message;
    }

    // Data classes
    public static class SessionInfo {
        public String id;
        public String source;
        public String model;
        public String title;
        public long startedAt;
        public Long endedAt;
        public long lastActive;
        public boolean isActive;
        public int messageCount;
        public int toolCallCount;
        public int inputTokens;
        public int outputTokens;
        public String preview;
    }

    public static class SessionMessage {
        public String role;
        public String content;
        public List<ToolCall> toolCalls;
        public String toolName;
        public String toolCallId;
        public long timestamp;
    }

    public static class ToolCall {
        public String id;
        public FunctionCall function;
    }

    public static class FunctionCall {
        public String name;
        public String arguments;
    }

    public static class SessionSearchResult {
        public String sessionId;
        public String snippet;
        public String role;
        public String source;
        public String model;
        public Long sessionStarted;
    }
}
