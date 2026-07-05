package com.nousresearch.hermes.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3-2: SSO / OIDC 测试
 */
class SsoTest {

    // ========================================================================
    // SsoConfig
    // ========================================================================

    @Nested
    @DisplayName("SsoConfig")
    class ConfigTest {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            SsoConfig cfg = new SsoConfig(true, "keycloak",
                "https://kc.example.com/realms/myrealm",
                "hermes-portal", "secret-123",
                "https://portal.example.com/api/auth/sso/callback",
                List.of("openid", "profile", "email"), true);

            assertTrue(cfg.isEnabled());
            assertEquals("keycloak", cfg.getProvider());
            assertEquals("https://kc.example.com/realms/myrealm", cfg.getIssuerUrl());
            assertEquals("hermes-portal", cfg.getClientId());
            assertEquals("secret-123", cfg.getClientSecret());
            assertEquals("https://portal.example.com/api/auth/sso/callback", cfg.getRedirectUri());
            assertTrue(cfg.isAutoRedirect());
            assertEquals(3, cfg.getScopes().size());
        }

        @Test
        @DisplayName("默认 scopes")
        void defaultScopes() {
            SsoConfig cfg = new SsoConfig(true, null, "url", "id", "secret", "uri", null, false);
            assertEquals(List.of("openid", "profile", "email"), cfg.getScopes());
        }

        @Test
        @DisplayName("默认 provider=generic")
        void defaultProvider() {
            SsoConfig cfg = new SsoConfig(true, null, "url", "id", "secret", "uri", null, false);
            assertEquals("generic", cfg.getProvider());
        }

        @Test
        @DisplayName("buildAuthorizationUrl 包含必要参数")
        void buildAuthorizationUrl() {
            SsoConfig cfg = new SsoConfig(true, "keycloak",
                "https://kc.example.com/realms/myrealm",
                "hermes-portal", "secret",
                "https://portal.example.com/callback",
                List.of("openid", "email"), false);

            String url = cfg.buildAuthorizationUrl("state-123");
            assertTrue(url.contains("response_type=code"));
            assertTrue(url.contains("client_id=hermes-portal"));
            assertTrue(url.contains("redirect_uri="));
            assertTrue(url.contains("state=state-123"));
            assertTrue(url.contains("scope=openid+email"));
            assertTrue(url.contains("/protocol/openid-connect/auth"));
        }

        @Test
        @DisplayName("getTokenEndpoint")
        void tokenEndpoint() {
            SsoConfig cfg = new SsoConfig(true, null, "https://kc.example.com/realms/r", "id", "s", "u", null, false);
            assertEquals("https://kc.example.com/realms/r/protocol/openid-connect/token", cfg.getTokenEndpoint());
        }

        @Test
        @DisplayName("getUserinfoEndpoint")
        void userinfoEndpoint() {
            SsoConfig cfg = new SsoConfig(true, null, "https://kc.example.com/realms/r", "id", "s", "u", null, false);
            assertEquals("https://kc.example.com/realms/r/protocol/openid-connect/userinfo", cfg.getUserinfoEndpoint());
        }

        @Test
        @DisplayName("fromMap 解析")
        void fromMap() {
            Map<String, Object> map = Map.of(
                "enabled", true,
                "provider", "keycloak",
                "issuer-url", "https://kc.example.com/realms/r",
                "client-id", "portal",
                "client-secret", "secret",
                "redirect-uri", "https://portal.example.com/callback",
                "auto-redirect", true
            );
            SsoConfig cfg = SsoConfig.fromMap(map);
            assertTrue(cfg.isEnabled());
            assertEquals("keycloak", cfg.getProvider());
            assertEquals("portal", cfg.getClientId());
            assertTrue(cfg.isAutoRedirect());
        }

        @Test
        @DisplayName("fromMap null → disabled")
        void fromMapNull() {
            SsoConfig cfg = SsoConfig.fromMap(null);
            assertFalse(cfg.isEnabled());
        }

        @Test
        @DisplayName("scopes 不可变")
        void scopesImmutable() {
            SsoConfig cfg = new SsoConfig(true, null, "url", "id", "s", "u", List.of("openid"), false);
            assertThrows(UnsupportedOperationException.class, () -> cfg.getScopes().add("email"));
        }
    }

    // ========================================================================
    // SsoService — state + session 管理（不测 HTTP 调用）
    // ========================================================================

    @Nested
    @DisplayName("SsoService")
    class ServiceTest {

        private SsoConfig config;
        private SsoService service;

        @BeforeEach
        void setUp() {
            config = new SsoConfig(true, "keycloak",
                "https://kc.example.com/realms/myrealm",
                "hermes-portal", "secret",
                "https://portal.example.com/callback",
                List.of("openid", "profile"), false);
            service = new SsoService(config);
        }

        @Test
        @DisplayName("startLogin 返回授权 URL")
        void startLogin() {
            String url = service.startLogin();
            assertNotNull(url);
            assertTrue(url.contains("/protocol/openid-connect/auth"));
            assertTrue(url.contains("client_id=hermes-portal"));
        }

        @Test
        @DisplayName("startLogin disabled → null")
        void startLoginDisabled() {
            SsoConfig disabled = new SsoConfig(false, null, null, null, null, null, null, false);
            SsoService svc = new SsoService(disabled);
            assertNull(svc.startLogin());
        }

        @Test
        @DisplayName("startLogin 每次生成不同 state")
        void uniqueState() {
            String url1 = service.startLogin();
            String url2 = service.startLogin();
            assertNotEquals(url1, url2); // state 不同
        }

        @Test
        @DisplayName("handleCallback 未知 state → null")
        void unknownState() {
            assertNull(service.handleCallback("code", "unknown-state"));
        }

        @Test
        @DisplayName("handleCallback disabled → null")
        void callbackDisabled() {
            SsoConfig disabled = new SsoConfig(false, null, null, null, null, null, null, false);
            SsoService svc = new SsoService(disabled);
            assertNull(svc.handleCallback("code", "state"));
        }

        @Test
        @DisplayName("validateSession 无效 token → empty")
        void validateInvalidSession() {
            assertTrue(service.validateSession("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("validateSession null → empty")
        void validateNullSession() {
            assertTrue(service.validateSession(null).isEmpty());
        }

        @Test
        @DisplayName("logout 不崩溃")
        void logoutSafe() {
            assertDoesNotThrow(() -> service.logout(null));
            assertDoesNotThrow(() -> service.logout("nonexistent"));
        }

        @Test
        @DisplayName("activeSessionCount 初始为 0")
        void initialSessionCount() {
            assertEquals(0, service.activeSessionCount());
        }

        @Test
        @DisplayName("getConfig 返回配置")
        void getConfig() {
            assertSame(config, service.getConfig());
        }
    }

    // ========================================================================
    // SsoSession
    // ========================================================================

    @Nested
    @DisplayName("SsoSession")
    class SessionTest {

        @Test
        @DisplayName("isValid 未来过期 → true")
        void validFuture() {
            SsoService.SsoSession session = new SsoService.SsoSession(
                "token", "sub-1", "user@example.com", "Test User", "testuser",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600),
                "keycloak", "access", "refresh"
            );
            assertTrue(session.isValid());
        }

        @Test
        @DisplayName("isValid 过去过期 → false")
        void validPast() {
            SsoService.SsoSession session = new SsoService.SsoSession(
                "token", "sub-1", "user@example.com", "Test User", "testuser",
                java.time.Instant.now().minusSeconds(7200),
                java.time.Instant.now().minusSeconds(3600),
                "keycloak", "access", "refresh"
            );
            assertFalse(session.isValid());
        }

        @Test
        @DisplayName("session 包含完整用户信息")
        void userInfo() {
            SsoService.SsoSession session = new SsoService.SsoSession(
                "tok", "sub-123", "user@example.com", "Alice", "alice",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600),
                "keycloak", "at", "rt"
            );
            assertEquals("sub-123", session.subject());
            assertEquals("user@example.com", session.email());
            assertEquals("Alice", session.name());
            assertEquals("alice", session.preferredUsername());
            assertEquals("keycloak", session.provider());
        }
    }
}
