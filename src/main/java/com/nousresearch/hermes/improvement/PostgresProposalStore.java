package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Postgres-backed {@link ProposalStore} for multi-instance deployments.
 */
public class PostgresProposalStore implements ProposalStore {

    private static final Logger logger = LoggerFactory.getLogger(PostgresProposalStore.class);

    private final DataSource dataSource;

    public PostgresProposalStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS improvement_proposal (
                    id              TEXT PRIMARY KEY,
                    tenant_id       VARCHAR(64) NOT NULL,
                    user_id         VARCHAR(64),
                    title           TEXT,
                    finding         TEXT,
                    proposed_change TEXT,
                    expected_benefit TEXT,
                    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    evidence        TEXT,
                    confidence      DOUBLE PRECISION DEFAULT 0.5,
                    created_at      BIGINT NOT NULL,
                    resolved_at     BIGINT
                )
                """);
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_ip_tenant_user ON improvement_proposal(tenant_id, user_id)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_ip_pending ON improvement_proposal(tenant_id, user_id) WHERE status IN ('PENDING', 'REQUIRE_CONFIRM')"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            logger.error("Failed to init improvement_proposal schema: {}", e.getMessage());
        }
    }

    @Override
    public void save(ImprovementProposal proposal) {
        String sql = """
            INSERT INTO improvement_proposal (id, tenant_id, user_id, title, finding, proposed_change,
                expected_benefit, status, evidence, confidence, created_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                resolved_at = EXCLUDED.resolved_at,
                title = EXCLUDED.title
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposal.id());
            ps.setString(2, proposal.tenantId());
            ps.setString(3, proposal.userId());
            ps.setString(4, proposal.title());
            ps.setString(5, proposal.finding());
            ps.setString(6, proposal.proposedChange());
            ps.setString(7, proposal.expectedBenefit());
            ps.setString(8, proposal.status().name());
            ps.setString(9, proposal.evidence());
            ps.setDouble(10, proposal.confidence());
            ps.setLong(11, proposal.createdAt());
            if (proposal.resolvedAt() != null) ps.setLong(12, proposal.resolvedAt());
            else ps.setNull(12, Types.BIGINT);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save proposal: {}", e.getMessage());
        }
    }

    @Override
    public ImprovementProposal findById(String tenantId, String proposalId) {
        String sql = "SELECT * FROM improvement_proposal WHERE tenant_id = ? AND id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, proposalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to find proposal: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<ImprovementProposal> queryPending(String tenantId, String userId) {
        String sql = """
            SELECT * FROM improvement_proposal
            WHERE tenant_id = ? AND (user_id = ? OR ? IS NULL)
              AND status IN ('PENDING', 'REQUIRE_CONFIRM')
            ORDER BY created_at DESC
            """;
        List<ImprovementProposal> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, userId);
            ps.setString(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query pending proposals: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public List<ImprovementProposal> queryByUser(String tenantId, String userId) {
        String sql = """
            SELECT * FROM improvement_proposal
            WHERE tenant_id = ? AND (user_id = ? OR ? IS NULL)
            ORDER BY created_at DESC
            """;
        List<ImprovementProposal> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, userId);
            ps.setString(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query proposals: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public void update(ImprovementProposal proposal) {
        save(proposal); // ON CONFLICT DO UPDATE handles this
    }

    @Override
    public List<ImprovementProposal> queryAll(String tenantId) {
        String sql = "SELECT * FROM improvement_proposal WHERE tenant_id = ? ORDER BY created_at DESC";
        List<ImprovementProposal> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query all proposals: {}", e.getMessage());
        }
        return results;
    }

    private ImprovementProposal mapRow(ResultSet rs) throws SQLException {
        long resolvedAt = rs.getLong("resolved_at");
        return new ImprovementProposal(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("title"),
                rs.getString("finding"),
                rs.getString("proposed_change"),
                rs.getString("expected_benefit"),
                ProposalStatus.valueOf(rs.getString("status")),
                rs.getString("evidence"),
                rs.getDouble("confidence"),
                rs.getLong("created_at"),
                rs.wasNull() ? null : resolvedAt
        );
    }
}
