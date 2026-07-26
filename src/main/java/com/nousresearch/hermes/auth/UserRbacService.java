package com.nousresearch.hermes.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * D5: User + RBAC repository (MySQL-backed).
 *
 * <p>Manages user accounts, workspace memberships, and role assignments.</p>
 *
 * <p>Uses MySQL for persistence. Falls back gracefully when DB not configured
 * (returns empty results, allows all access in LOCAL mode).</p>
 */
public class UserRbacService {

    private static final Logger logger = LoggerFactory.getLogger(UserRbacService.class);

    private final DataSource dataSource;
    // Cache: userId -> UserAccount
    private final Map<String, UserAccount> userCache = new java.util.concurrent.ConcurrentHashMap<>();
    // Cache: (workspaceId + userId) -> Role
    private final Map<String, UserAccount.Role> roleCache = new java.util.concurrent.ConcurrentHashMap<>();

    public UserRbacService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ============ User CRUD ============

    public UserAccount createUser(String email, String displayName, String ssoSubject) {
        String userId = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String sql = """
            INSERT INTO user_account (user_id, email, display_name, sso_subject)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), sso_subject = VALUES(sso_subject)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, email);
            ps.setString(3, displayName);
            ps.setString(4, ssoSubject);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Email unique constraint -> load existing
            if (email != null && e.getMessage().contains("Duplicate")) {
                return findByEmail(email);
            }
            logger.error("Failed to create user: {}", e.getMessage());
            throw new RuntimeException("Failed to create user", e);
        }
        UserAccount user = new UserAccount(userId, email, displayName, ssoSubject, true, Instant.now());
        userCache.put(userId, user);
        logger.info("Created user: {} ({})", userId, email);
        return user;
    }

    public UserAccount findByUserId(String userId) {
        if (userId == null) return null;
        UserAccount cached = userCache.get(userId);
        if (cached != null) return cached;

        String sql = "SELECT * FROM user_account WHERE user_id = ? AND is_active = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UserAccount user = mapRow(rs);
                    userCache.put(userId, user);
                    return user;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find user {}: {}", userId, e.getMessage());
        }
        return null;
    }

    public UserAccount findByEmail(String email) {
        if (email == null) return null;
        String sql = "SELECT * FROM user_account WHERE email = ? AND is_active = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to find user by email {}: {}", email, e.getMessage());
        }
        return null;
    }

    public UserAccount findBySsoSubject(String ssoSubject) {
        if (ssoSubject == null) return null;
        String sql = "SELECT * FROM user_account WHERE sso_subject = ? AND is_active = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ssoSubject);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to find user by SSO subject: {}", e.getMessage());
        }
        return null;
    }

    // ============ Workspace Membership ============

    public void addMember(String workspaceId, String userId, UserAccount.Role role) {
        String sql = """
            INSERT INTO workspace_member (workspace_id, user_id, role)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE role = VALUES(role)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, userId);
            ps.setString(3, role.name());
            ps.executeUpdate();
            roleCache.remove(workspaceId + ":" + userId);
        } catch (SQLException e) {
            logger.error("Failed to add member: {}", e.getMessage());
        }
    }

    public void removeMember(String workspaceId, String userId) {
        String sql = "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, userId);
            ps.executeUpdate();
            roleCache.remove(workspaceId + ":" + userId);
        } catch (SQLException e) {
            logger.error("Failed to remove member: {}", e.getMessage());
        }
    }

    public UserAccount.Role getRole(String workspaceId, String userId) {
        if (workspaceId == null || userId == null) return UserAccount.Role.VIEWER;
        String cacheKey = workspaceId + ":" + userId;
        UserAccount.Role cached = roleCache.get(cacheKey);
        if (cached != null) return cached;

        String sql = "SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UserAccount.Role role = UserAccount.Role.fromString(rs.getString("role"));
                    roleCache.put(cacheKey, role);
                    return role;
                }
            }
        } catch (SQLException e) {
            logger.debug("Failed to get role for {}/{}: {}", workspaceId, userId, e.getMessage());
        }
        return UserAccount.Role.VIEWER;
    }

    public List<String> listUserWorkspaces(String userId) {
        String sql = "SELECT workspace_id FROM workspace_member WHERE user_id = ?";
        List<String> workspaces = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) workspaces.add(rs.getString("workspace_id"));
            }
        } catch (SQLException e) {
            logger.error("Failed to list workspaces for {}: {}", userId, e.getMessage());
        }
        return workspaces;
    }

    public List<Map<String, Object>> listWorkspaceMembers(String workspaceId) {
        String sql = """
            SELECT wm.user_id, wm.role, wm.created_at, u.email, u.display_name
            FROM workspace_member wm
            LEFT JOIN user_account u ON u.user_id = wm.user_id
            WHERE wm.workspace_id = ?
            ORDER BY wm.created_at
            """;
        List<Map<String, Object>> members = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getString("user_id"));
                    m.put("role", rs.getString("role"));
                    m.put("email", rs.getString("email"));
                    m.put("displayName", rs.getString("display_name"));
                    members.add(m);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list members for {}: {}", workspaceId, e.getMessage());
        }
        return members;
    }

    public void invalidateCache() {
        userCache.clear();
        roleCache.clear();
    }

    // ============ Internal ============

    private UserAccount mapRow(ResultSet rs) throws SQLException {
        return new UserAccount(
            rs.getString("user_id"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("sso_subject"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant() : Instant.now()
        );
    }
}
