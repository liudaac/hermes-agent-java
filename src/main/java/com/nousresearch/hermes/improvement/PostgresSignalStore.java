package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Postgres-backed {@link SignalStore} for multi-instance deployments.
 *
 * <p>Schema is auto-created on construction. In LOCAL mode, use
 * {@link LocalSignalStore} instead.</p>
 */
public class PostgresSignalStore implements SignalStore {

    private static final Logger logger = LoggerFactory.getLogger(PostgresSignalStore.class);

    private final DataSource dataSource;

    public PostgresSignalStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS improvement_signal (
                    id          TEXT PRIMARY KEY,
                    tenant_id   VARCHAR(64) NOT NULL,
                    user_id     VARCHAR(64),
                    signal_type VARCHAR(32) NOT NULL,
                    session_id  VARCHAR(64),
                    content     TEXT,
                    weight      DOUBLE PRECISION DEFAULT 0.5,
                    timestamp   BIGINT NOT NULL,
                    processed   BOOLEAN DEFAULT FALSE
                )
                """);
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_is_tenant_user ON improvement_signal(tenant_id, user_id)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_is_type ON improvement_signal(tenant_id, user_id, signal_type)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_is_unprocessed ON improvement_signal(tenant_id, user_id) WHERE processed = FALSE"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            logger.error("Failed to init improvement_signal schema: {}", e.getMessage());
        }
    }

    @Override
    public void save(ImprovementSignal signal) {
        String sql = """
            INSERT INTO improvement_signal (id, tenant_id, user_id, signal_type, session_id, content, weight, timestamp, processed, scope)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, signal.id());
            ps.setString(2, signal.tenantId());
            ps.setString(3, signal.userId());
            ps.setString(4, signal.type().name());
            ps.setString(5, signal.sessionId());
            ps.setString(6, signal.content());
            ps.setDouble(7, signal.weight());
            ps.setLong(8, signal.timestamp());
            ps.setBoolean(9, signal.processed());
            ps.setString(10, signal.scope() != null ? signal.scope().name() : SignalScope.USER.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save signal: {}", e.getMessage());
        }
    }

    @Override
    public List<ImprovementSignal> queryByUser(String tenantId, String userId) {
        String sql = "SELECT * FROM improvement_signal WHERE tenant_id = ? AND (user_id = ? OR (? IS NULL)) ORDER BY timestamp DESC";
        List<ImprovementSignal> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, userId);
            ps.setString(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query signals: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public List<ImprovementSignal> queryByType(String tenantId, String userId, SignalType type) {
        return queryByUser(tenantId, userId).stream()
                .filter(s -> s.type() == type)
                .collect(Collectors.toList());
    }

    @Override
    public List<ImprovementSignal> queryUnprocessed(String tenantId, String userId) {
        return queryByUser(tenantId, userId).stream()
                .filter(s -> !s.processed())
                .collect(Collectors.toList());
    }

    @Override
    public void markProcessed(String signalId) {
        String sql = "UPDATE improvement_signal SET processed = TRUE WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, signalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to mark signal processed: {}", e.getMessage());
        }
    }

    @Override
    public int countByType(String tenantId, String userId, SignalType type) {
        String sql = "SELECT COUNT(*) FROM improvement_signal WHERE tenant_id = ? AND user_id = ? AND signal_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, userId);
            ps.setString(3, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to count signals: {}", e.getMessage());
        }
        return 0;
    }

    private ImprovementSignal mapRow(ResultSet rs) throws SQLException {
        String scopeStr = rs.getString("scope");
        SignalScope scope = scopeStr != null && !scopeStr.isEmpty()
            ? SignalScope.valueOf(scopeStr) : SignalScope.USER;
        return new ImprovementSignal(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                SignalType.valueOf(rs.getString("signal_type")),
                scope,
                rs.getString("session_id"),
                rs.getString("content"),
                rs.getDouble("weight"),
                rs.getLong("timestamp"),
                rs.getBoolean("processed"),
                java.util.Map.of()
        );
    }
}
