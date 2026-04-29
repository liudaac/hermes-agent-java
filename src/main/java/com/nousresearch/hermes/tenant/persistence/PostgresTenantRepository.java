package com.nousresearch.hermes.tenant.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tenant.core.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PostgreSQL 租户状态仓库实现
 * 
 * 使用 PostgreSQL JSONB 类型存储租户状态和会话数据
 */
public class PostgresTenantRepository implements TenantStateRepository {

    private static final Logger logger = LoggerFactory.getLogger(PostgresTenantRepository.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final DataSource dataSource;
    private final ExecutorService executor;

    public PostgresTenantRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "postgres-repo");
            t.setDaemon(true);
            return t;
        });
        initializeSchema();
    }

    @Override
    public CompletableFuture<Void> saveState(String tenantId, TenantStateSnapshot state) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO tenant_states (tenant_id, state, created_at, last_activity, 
                    config, quota, security_policy, version, updated_at)
                VALUES (?, ?::tenant_state, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, NOW())
                ON CONFLICT (tenant_id) DO UPDATE SET
                    state = EXCLUDED.state,
                    last_activity = EXCLUDED.last_activity,
                    config = EXCLUDED.config,
                    quota = EXCLUDED.quota,
                    security_policy = EXCLUDED.security_policy,
                    version = EXCLUDED.version,
                    updated_at = NOW()
                WHERE tenant_states.version < EXCLUDED.version
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ps.setString(2, state.state().name());
                ps.setTimestamp(3, Timestamp.from(state.createdAt()));
                ps.setTimestamp(4, Timestamp.from(state.lastActivity()));
                ps.setString(5, toJson(state.config()));
                ps.setString(6, toJson(state.quota()));
                ps.setString(7, toJson(state.securityPolicy()));
                ps.setLong(8, state.version());
                
                ps.executeUpdate();
                logger.debug("Saved state for tenant: {}", tenantId);
                
            } catch (SQLException e) {
                logger.error("Failed to save state for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to save tenant state", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<TenantStateSnapshot>> loadState(String tenantId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM tenant_states WHERE tenant_id = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ResultSet rs = ps.executeQuery();
                
                if (rs.next()) {
                    return Optional.of(mapToSnapshot(rs));
                }
                return Optional.empty();
                
            } catch (SQLException e) {
                logger.error("Failed to load state for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to load tenant state", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteState(String tenantId) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM tenant_states WHERE tenant_id = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ps.executeUpdate();
                logger.debug("Deleted state for tenant: {}", tenantId);
                
            } catch (SQLException e) {
                logger.error("Failed to delete state for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to delete tenant state", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<String>> listTenants() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT tenant_id FROM tenant_states ORDER BY tenant_id";
            List<String> tenants = new ArrayList<>();
            
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    tenants.add(rs.getString("tenant_id"));
                }
                return tenants;
                
            } catch (SQLException e) {
                logger.error("Failed to list tenants", e);
                throw new RuntimeException("Failed to list tenants", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> exists(String tenantId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM tenant_states WHERE tenant_id = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ResultSet rs = ps.executeQuery();
                return rs.next();
                
            } catch (SQLException e) {
                logger.error("Failed to check existence for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to check tenant existence", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<Instant>> getLastUpdated(String tenantId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT updated_at FROM tenant_states WHERE tenant_id = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ResultSet rs = ps.executeQuery();
                
                if (rs.next()) {
                    return Optional.of(rs.getTimestamp("updated_at").toInstant());
                }
                return Optional.empty();
                
            } catch (SQLException e) {
                logger.error("Failed to get last updated for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to get last updated", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> saveSession(String tenantId, String sessionId, SessionState sessionState) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO tenant_sessions (tenant_id, session_id, created_at, last_activity, 
                    metadata, serialized_context, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, NOW())
                ON CONFLICT (tenant_id, session_id) DO UPDATE SET
                    last_activity = EXCLUDED.last_activity,
                    metadata = EXCLUDED.metadata,
                    serialized_context = EXCLUDED.serialized_context,
                    updated_at = NOW()
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ps.setString(2, sessionId);
                ps.setTimestamp(3, Timestamp.from(sessionState.createdAt()));
                ps.setTimestamp(4, Timestamp.from(sessionState.lastActivity()));
                ps.setString(5, toJson(sessionState.metadata()));
                ps.setBytes(6, sessionState.serializedContext());
                
                ps.executeUpdate();
                logger.debug("Saved session {} for tenant: {}", sessionId, tenantId);
                
            } catch (SQLException e) {
                logger.error("Failed to save session for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to save session", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<SessionState>> loadSession(String tenantId, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM tenant_sessions WHERE tenant_id = ? AND session_id = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ps.setString(2, sessionId);
                ResultSet rs = ps.executeQuery();
                
                if (rs.next()) {
                    return Optional.of(mapToSessionState(rs));
                }
                return Optional.empty();
                
            } catch (SQLException e) {
                logger.error("Failed to load session for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to load session", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<String>> listSessions(String tenantId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT session_id FROM tenant_sessions WHERE tenant_id = ? ORDER BY last_activity DESC";
            List<String> sessions = new ArrayList<>();
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ResultSet rs = ps.executeQuery();
                
                while (rs.next()) {
                    sessions.add(rs.getString("session_id"));
                }
                return sessions;
                
            } catch (SQLException e) {
                logger.error("Failed to list sessions for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to list sessions", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteSession(String tenantId, String sessionId) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM tenant_sessions WHERE tenant_id = ? AND session_id = ?";
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                
                ps.setString(1, tenantId);
                ps.setString(2, sessionId);
                ps.executeUpdate();
                logger.debug("Deleted session {} for tenant: {}", sessionId, tenantId);
                
            } catch (SQLException e) {
                logger.error("Failed to delete session for tenant: {}", tenantId, e);
                throw new RuntimeException("Failed to delete session", e);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ============ 辅助方法 ============

    private void initializeSchema() {
        String[] ddl = {
            """
            CREATE TYPE tenant_state AS ENUM ('INITIALIZING', 'ACTIVE', 'SUSPENDED', 'EXPIRED', 'CLEANING_UP', 'DESTROYED')
            """,
            """
            CREATE TABLE IF NOT EXISTS tenant_states (
                tenant_id VARCHAR(255) PRIMARY KEY,
                state tenant_state NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                last_activity TIMESTAMP WITH TIME ZONE NOT NULL,
                config JSONB NOT NULL DEFAULT '{}',
                quota JSONB NOT NULL DEFAULT '{}',
                security_policy JSONB NOT NULL DEFAULT '{}',
                version BIGINT NOT NULL DEFAULT 0,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_tenant_states_state ON tenant_states(state)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_tenant_states_updated ON tenant_states(updated_at)
            """,
            """
            CREATE TABLE IF NOT EXISTS tenant_sessions (
                tenant_id VARCHAR(255) NOT NULL,
                session_id VARCHAR(255) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                last_activity TIMESTAMP WITH TIME ZONE NOT NULL,
                metadata JSONB NOT NULL DEFAULT '{}',
                serialized_context BYTEA,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                PRIMARY KEY (tenant_id, session_id)
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_tenant_sessions_tenant ON tenant_sessions(tenant_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_tenant_sessions_activity ON tenant_sessions(last_activity)
            """
        };

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            for (String sql : ddl) {
                try {
                    stmt.execute(sql);
                } catch (SQLException e) {
                    // 忽略已存在的错误
                    if (!e.getMessage().contains("already exists")) {
                        throw e;
                    }
                }
            }
            logger.info("Initialized PostgreSQL schema");
            
        } catch (SQLException e) {
            logger.error("Failed to initialize schema", e);
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize to JSON", e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize JSON", e);
            return Map.of();
        }
    }

    private TenantStateSnapshot mapToSnapshot(ResultSet rs) throws SQLException {
        return new TenantStateSnapshot(
            rs.getString("tenant_id"),
            TenantContext.State.valueOf(rs.getString("state")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_activity").toInstant(),
            fromJson(rs.getString("config")),
            fromJson(rs.getString("quota")),
            fromJson(rs.getString("security_policy")),
            rs.getLong("version")
        );
    }

    private SessionState mapToSessionState(ResultSet rs) throws SQLException {
        return new SessionState(
            rs.getString("session_id"),
            rs.getString("tenant_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_activity").toInstant(),
            fromJson(rs.getString("metadata")),
            rs.getBytes("serialized_context")
        );
    }
}
