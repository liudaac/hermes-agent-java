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


    // ============ D6: JwtService ============

    // ============ Role enum ============

}
