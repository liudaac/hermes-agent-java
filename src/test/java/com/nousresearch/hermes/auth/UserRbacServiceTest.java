package com.nousresearch.hermes.auth;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D5+D6: UserRbacService + JwtService tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserRbacServiceTest {

    private static javax.sql.DataSource h2DataSource;
    private UserRbacService rbac;
    private JwtService jwt;

    @BeforeAll
    static void initDB() throws Exception {
        h2DataSource = org.h2.jdbcx.JdbcConnectionPool.create(
            "jdbc:h2:mem:test-rbac;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var conn = h2DataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_account (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id VARCHAR(64) NOT NULL,
                    email VARCHAR(128) DEFAULT NULL,
                    display_name VARCHAR(128) DEFAULT NULL,
                    sso_subject VARCHAR(128) DEFAULT NULL,
                    is_active TINYINT(1) DEFAULT 1,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE (user_id),
                    UNIQUE (email)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS workspace_member (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    workspace_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    role VARCHAR(16) DEFAULT 'viewer',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE (workspace_id, user_id)
                )
                """);
        }
    }

    @BeforeEach
    void setUp() {
        rbac = new UserRbacService(h2DataSource);
        rbac.invalidateCache();
        jwt = new JwtService("test-secret-key", 3600);
    }

    // ============ D5: UserRbacService ============

    @Test
    @Order(1)
    @DisplayName("createUser + findByUserId round-trip")
    void createUser() {
        UserAccount user = rbac.createUser("alice@test.com", "Alice", "sso-alice-123");
        assertNotNull(user.userId());
        assertEquals("alice@test.com", user.email());

        UserAccount found = rbac.findByUserId(user.userId());
        assertNotNull(found);
        assertEquals("Alice", found.displayName());
    }

    @Test
    @Order(2)
    @DisplayName("findByEmail finds user")
    void findByEmail() {
        rbac.createUser("bob@test.com", "Bob", "sso-bob");
        UserAccount found = rbac.findByEmail("bob@test.com");
        assertNotNull(found);
        assertEquals("Bob", found.displayName());
    }

    @Test
    @Order(3)
    @DisplayName("findBySsoSubject finds user")
    void findBySsoSubject() {
        rbac.createUser("carol@test.com", "Carol", "sso-carol-xyz");
        UserAccount found = rbac.findBySsoSubject("sso-carol-xyz");
        assertNotNull(found);
        assertEquals("carol@test.com", found.email());
    }

    @Test
    @Order(4)
    @DisplayName("addMember + getRole")
    void addMemberGetRole() {
        UserAccount user = rbac.createUser("dave@test.com", "Dave", null);
        rbac.addMember("workspace-1", user.userId(), UserAccount.Role.ADMIN);

        UserAccount.Role role = rbac.getRole("workspace-1", user.userId());
        assertEquals(UserAccount.Role.ADMIN, role);
    }

    @Test
    @Order(5)
    @DisplayName("getRole returns VIEWER for non-member")
    void getRole_nonMember() {
        assertEquals(UserAccount.Role.VIEWER, rbac.getRole("ws-x", "nonexistent-user"));
    }

    @Test
    @Order(6)
    @DisplayName("removeMember removes membership")
    void removeMember() {
        UserAccount user = rbac.createUser("eve@test.com", "Eve", null);
        rbac.addMember("ws-1", user.userId(), UserAccount.Role.OPERATOR);
        rbac.removeMember("ws-1", user.userId());
        assertEquals(UserAccount.Role.VIEWER, rbac.getRole("ws-1", user.userId()));
    }

    @Test
    @Order(7)
    @DisplayName("listUserWorkspaces returns workspaces")
    void listUserWorkspaces() {
        UserAccount user = rbac.createUser("frank@test.com", "Frank", null);
        rbac.addMember("ws-a", user.userId(), UserAccount.Role.VIEWER);
        rbac.addMember("ws-b", user.userId(), UserAccount.Role.OPERATOR);

        var workspaces = rbac.listUserWorkspaces(user.userId());
        assertEquals(2, workspaces.size());
        assertTrue(workspaces.contains("ws-a"));
        assertTrue(workspaces.contains("ws-b"));
    }

    @Test
    @Order(8)
    @DisplayName("listWorkspaceMembers returns members")
    void listWorkspaceMembers() {
        UserAccount u1 = rbac.createUser("g1@test.com", "G1", null);
        UserAccount u2 = rbac.createUser("g2@test.com", "G2", null);
        rbac.addMember("ws-m", u1.userId(), UserAccount.Role.ADMIN);
        rbac.addMember("ws-m", u2.userId(), UserAccount.Role.VIEWER);

        var members = rbac.listWorkspaceMembers("ws-m");
        assertEquals(2, members.size());
    }

    // ============ D6: JwtService ============

    @Test
    @Order(9)
    @DisplayName("JWT issue + verify round-trip")
    void jwt_issueVerify() {
        UserAccount user = rbac.createUser("jwt@test.com", "JWT User", null);
        String token = jwt.issue(user);
        assertNotNull(token);
        assertTrue(token.startsWith("jwt_"));

        String userId = jwt.verify(token);
        assertEquals(user.userId(), userId);
    }

    @Test
    @Order(10)
    @DisplayName("JWT with custom TTL expires")
    void jwt_customTtl() throws InterruptedException {
        UserAccount user = rbac.createUser("ttl@test.com", "TTL User", null);
        // Use negative TTL to ensure already expired
        String token = jwt.issue(user, -5); // expired 5 seconds ago
        assertNull(jwt.verify(token), "JWT with negative TTL should be expired");
    }

    @Test
    @Order(11)
    @DisplayName("JWT expired token returns null")
    void jwt_expired() {
        UserAccount user = rbac.createUser("exp@test.com", "Exp User", null);
        String token = jwt.issue(user, -10); // expired 10 seconds ago
        assertNull(jwt.verify(token));
    }

    @Test
    @Order(12)
    @DisplayName("JWT invalid signature returns null")
    void jwt_invalidSig() {
        UserAccount user = rbac.createUser("sig@test.com", "Sig User", null);
        String token = jwt.issue(user);
        // Tamper with signature
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertNull(jwt.verify(tampered));
    }

    @Test
    @Order(13)
    @DisplayName("JWT wrong secret returns null")
    void jwt_wrongSecret() {
        UserAccount user = rbac.createUser("wrong@test.com", "Wrong User", null);
        String token = jwt.issue(user);

        // Verify with different secret
        JwtService otherJwt = new JwtService("different-secret", 3600);
        assertNull(otherJwt.verify(token));
    }

    @Test
    @Order(14)
    @DisplayName("JWT null/empty returns null")
    void jwt_nullEmpty() {
        assertNull(jwt.verify(null));
        assertNull(jwt.verify(""));
        assertNull(jwt.verify("invalid"));
        assertNull(jwt.verify("Bearer ak_xxx"));  // not a JWT
    }

    @Test
    @Order(15)
    @DisplayName("JWT decodeClaims returns claims")
    void jwt_decodeClaims() {
        UserAccount user = rbac.createUser("decode@test.com", "Decode User", null);
        String token = jwt.issue(user);
        var claims = jwt.decodeClaims(token);
        assertEquals(user.userId(), claims.get("sub"));
        assertEquals("decode@test.com", claims.get("email"));
    }

    // ============ Role enum ============

    @Test
    @Order(16)
    @DisplayName("Role permissions are correct")
    void rolePermissions() {
        assertTrue(UserAccount.Role.ADMIN.canAdmin());
        assertTrue(UserAccount.Role.ADMIN.canWrite());
        assertTrue(UserAccount.Role.ADMIN.canRead());

        assertTrue(UserAccount.Role.OPERATOR.canWrite());
        assertFalse(UserAccount.Role.OPERATOR.canAdmin());

        assertFalse(UserAccount.Role.VIEWER.canWrite());
        assertTrue(UserAccount.Role.VIEWER.canRead());

        assertFalse(UserAccount.Role.API_ONLY.canAccessPortal());
        assertTrue(UserAccount.Role.ADMIN.canAccessPortal());
    }

    @Test
    @Order(17)
    @DisplayName("Role.fromString parses correctly")
    void roleFromString() {
        assertEquals(UserAccount.Role.ADMIN, UserAccount.Role.fromString("ADMIN"));
        assertEquals(UserAccount.Role.OPERATOR, UserAccount.Role.fromString("operator"));
        assertEquals(UserAccount.Role.VIEWER, UserAccount.Role.fromString("viewer"));
        assertEquals(UserAccount.Role.API_ONLY, UserAccount.Role.fromString("api-only"));
        assertEquals(UserAccount.Role.VIEWER, UserAccount.Role.fromString(null));
        assertEquals(UserAccount.Role.VIEWER, UserAccount.Role.fromString("unknown"));
    }
}
