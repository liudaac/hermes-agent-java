package com.nousresearch.hermes.billing.repository;

import com.nousresearch.hermes.billing.TenantUsageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * C6: MySQL-backed BillingRepository.
 *
 * <p>Reads/writes billing records from {@code billing_record} table.
 * Drop-in replacement for {@link JsonlBillingRepository} in CLUSTER mode.</p>
 */
public class MysqlBillingRepository implements BillingRepository {

    private static final Logger logger = LoggerFactory.getLogger(MysqlBillingRepository.class);

    private final DataSource dataSource;

    public MysqlBillingRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void append(TenantUsageRecord record) {
        if (record == null || record.tenantId() == null) return;
        String sql = """
            INSERT INTO billing_record (tenant_id, model, provider, input_tokens, output_tokens,
                total_tokens, estimated_cost_usd, session_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.tenantId());
            ps.setString(2, record.model());
            ps.setString(3, record.provider());
            ps.setLong(4, record.inputTokens());
            ps.setLong(5, record.outputTokens());
            ps.setLong(6, record.totalTokens());
            ps.setDouble(7, record.estimatedCostUsd());
            ps.setString(8, record.sessionId());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to append billing record for {}: {}", record.tenantId(), e.getMessage());
        }
    }

    @Override
    public List<TenantUsageRecord> findByTenantAndDate(String tenantId, LocalDate date) {
        String sql = """
            SELECT tenant_id, model, provider, input_tokens, output_tokens, total_tokens,
                   estimated_cost_usd, session_id, created_at
            FROM billing_record
            WHERE tenant_id = ? AND DATE(created_at) = ?
            ORDER BY created_at
            """;
        List<TenantUsageRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find billing records for {}/{}: {}", tenantId, date, e.getMessage());
        }
        return records;
    }

    @Override
    public List<TenantUsageRecord> findByTenantAndDateRange(String tenantId, LocalDate fromDate, LocalDate toDate) {
        String sql = """
            SELECT tenant_id, model, provider, input_tokens, output_tokens, total_tokens,
                   estimated_cost_usd, session_id, created_at
            FROM billing_record
            WHERE tenant_id = ? AND DATE(created_at) BETWEEN ? AND ?
            ORDER BY created_at
            """;
        List<TenantUsageRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(fromDate));
            ps.setDate(3, java.sql.Date.valueOf(toDate));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find billing records for {}/{}-{}: {}", tenantId, fromDate, toDate, e.getMessage());
        }
        return records;
    }

    @Override
    public TenantDailyTotals getDailyTotals(String tenantId, LocalDate date) {
        String sql = """
            SELECT COUNT(*) AS total_requests,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(estimated_cost_usd), 0) AS estimated_cost
            FROM billing_record
            WHERE tenant_id = ? AND DATE(created_at) = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TenantDailyTotals(
                        tenantId,
                        rs.getLong("total_requests"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getDouble("estimated_cost")
                    );
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get daily totals for {}/{}: {}", tenantId, date, e.getMessage());
        }
        return TenantDailyTotals.empty(tenantId);
    }

    @Override
    public TenantDailyTotals getRangeTotals(String tenantId, LocalDate fromDate, LocalDate toDate) {
        String sql = """
            SELECT COUNT(*) AS total_requests,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(estimated_cost_usd), 0) AS estimated_cost
            FROM billing_record
            WHERE tenant_id = ? AND DATE(created_at) BETWEEN ? AND ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setDate(2, java.sql.Date.valueOf(fromDate));
            ps.setDate(3, java.sql.Date.valueOf(toDate));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TenantDailyTotals(
                        tenantId,
                        rs.getLong("total_requests"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getDouble("estimated_cost")
                    );
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get range totals for {}/{}-{}: {}", tenantId, fromDate, toDate, e.getMessage());
        }
        return TenantDailyTotals.empty(tenantId);
    }

    // ============ Helper ============

    private TenantUsageRecord mapRow(ResultSet rs) throws SQLException {
        return new TenantUsageRecord(
            rs.getString("tenant_id"),
            rs.getDate("created_at").toLocalDate(),
            rs.getString("model"),
            rs.getString("provider"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("total_tokens"),
            rs.getDouble("estimated_cost_usd"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("session_id")
        );
    }
}
