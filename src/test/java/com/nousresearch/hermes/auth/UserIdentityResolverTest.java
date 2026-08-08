package com.nousresearch.hermes.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link UserIdentityResolver}.
 *
 * <p>Covers: passthrough mode, DB-backed resolution, auto-creation,
 * caching, null handling, and multi-channel normalization.</p>
 */
class UserIdentityResolverTest {

    private UserRbacService rbacService;
    private UserIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        rbacService = mock(UserRbacService.class);
        resolver = new UserIdentityResolver(rbacService);
    }

    // ── Passthrough mode ──

    @Test
    void passthroughReturnsRawId() {
        UserIdentityResolver pt = UserIdentityResolver.passthrough();
        assertEquals("ou_abc123", pt.resolveUserId("feishu", "ou_abc123"));
        assertEquals("0F443F", pt.resolveUserId("qq", "0F443F"));
    }

    @Test
    void passthroughReturnsNullForNullInput() {
        UserIdentityResolver pt = UserIdentityResolver.passthrough();
        assertNull(pt.resolveUserId("feishu", null));
        assertNull(pt.resolveUserId("feishu", ""));
        assertNull(pt.resolveUserId("feishu", "  "));
    }

    // ── DB-backed resolution ──

    @Test
    void resolvesExistingUserBySsoSubject() {
        UserAccount user = new UserAccount("usr_abc", "test@feishu.local", "Test", "feishu:ou_123", true, java.time.Instant.now());
        when(rbacService.findBySsoSubject("feishu:ou_123")).thenReturn(user);

        String result = resolver.resolveUserId("feishu", "ou_123");
        assertEquals("usr_abc", result);
    }

    @Test
    void autoCreatesUserOnFirstLogin() {
        when(rbacService.findBySsoSubject("qq:0F443F")).thenReturn(null);
        UserAccount newUser = new UserAccount("usr_new", "0F443F@qq.local", "qq:0F443F", "qq:0F443F", true, java.time.Instant.now());
        when(rbacService.createUser(eq("0F443F@qq.local"), eq("qq:0F443F"), eq("qq:0F443F")))
            .thenReturn(newUser);

        String result = resolver.resolveUserId("qq", "0F443F");
        assertEquals("usr_new", result);
    }

    @Test
    void fallsBackToRawIdWhenCreateFails() {
        when(rbacService.findBySsoSubject("wecom:usr_xyz")).thenReturn(null);
        when(rbacService.createUser(any(), any(), any()))
            .thenThrow(new RuntimeException("DB unavailable"));

        String result = resolver.resolveUserId("wecom", "usr_xyz");
        assertEquals("usr_xyz", result);
    }

    // ── Caching ──

    @Test
    void cachesResolvedUserId() {
        UserAccount user = new UserAccount("usr_cached", "test@feishu.local", "Test", "feishu:ou_c1", true, java.time.Instant.now());
        when(rbacService.findBySsoSubject("feishu:ou_c1")).thenReturn(user);

        resolver.resolveUserId("feishu", "ou_c1");
        resolver.resolveUserId("feishu", "ou_c1");

        // Second call should use cache, not hit DB again
        verify(rbacService, times(1)).findBySsoSubject("feishu:ou_c1");
    }

    @Test
    void cachesAutoCreatedUser() {
        when(rbacService.findBySsoSubject("qq:0F999")).thenReturn(null);
        UserAccount newUser = new UserAccount("usr_c2", "0F999@qq.local", "qq:0F999", "qq:0F999", true, java.time.Instant.now());
        when(rbacService.createUser(any(), any(), any())).thenReturn(newUser);

        resolver.resolveUserId("qq", "0F999");
        resolver.resolveUserId("qq", "0F999");

        verify(rbacService, times(1)).findBySsoSubject("qq:0F999");
        verify(rbacService, times(1)).createUser(any(), any(), any());
    }

    @Test
    void invalidateCacheForcesReLookup() {
        UserAccount user = new UserAccount("usr_inv", "test@feishu.local", "Test", "feishu:ou_i1", true, java.time.Instant.now());
        when(rbacService.findBySsoSubject("feishu:ou_i1")).thenReturn(user);

        resolver.resolveUserId("feishu", "ou_i1");
        resolver.invalidateCache();
        resolver.resolveUserId("feishu", "ou_i1");

        verify(rbacService, times(2)).findBySsoSubject("feishu:ou_i1");
    }

    // ── Multi-channel normalization ──

    @Test
    void sameUserDifferentChannelsResolvesToDifferentUserIds() {
        // User has linked both feishu and qq accounts
        UserAccount feishuUser = new UserAccount("usr_001", "test@feishu.local", "Test", "feishu:ou_001", true, java.time.Instant.now());
        UserAccount qqUser = new UserAccount("usr_002", "test@qq.local", "Test", "qq:0F001", true, java.time.Instant.now());

        when(rbacService.findBySsoSubject("feishu:ou_001")).thenReturn(feishuUser);
        when(rbacService.findBySsoSubject("qq:0F001")).thenReturn(qqUser);

        // Different ssoSubjects -> different userIds (unless manually linked)
        assertNotEquals(
            resolver.resolveUserId("feishu", "ou_001"),
            resolver.resolveUserId("qq", "0F001")
        );
    }

    @Test
    void sameChannelSameUserResolvesConsistently() {
        UserAccount user = new UserAccount("usr_consistent", "test@feishu.local", "Test", "feishu:ou_same", true, java.time.Instant.now());
        when(rbacService.findBySsoSubject("feishu:ou_same")).thenReturn(user);

        String first = resolver.resolveUserId("feishu", "ou_same");
        String second = resolver.resolveUserId("feishu", "ou_same");
        assertEquals(first, second);
        assertEquals("usr_consistent", first);
    }

    // ── Null / edge cases ──

    @Test
    void nullChannelUserIdReturnsNull() {
        assertNull(resolver.resolveUserId("feishu", null));
        assertNull(resolver.resolveUserId(null, "ou_123"));
    }

    @Test
    void blankChannelUserIdReturnsNull() {
        assertNull(resolver.resolveUserId("feishu", ""));
        assertNull(resolver.resolveUserId("feishu", "   "));
    }

    @Test
    void resolveBySsoSubjectDirectly() {
        UserAccount user = new UserAccount("usr_direct", "test@feishu.local", "Test", "web:jwt_123", true, java.time.Instant.now());
        when(rbacService.findBySsoSubject("web:jwt_123")).thenReturn(user);

        assertEquals("usr_direct", resolver.resolveBySsoSubject("web:jwt_123"));
    }

    @Test
    void resolveBySsoSubjectReturnsNullWhenNotFound() {
        when(rbacService.findBySsoSubject("web:unknown")).thenReturn(null);
        assertNull(resolver.resolveBySsoSubject("web:unknown"));
    }

    @Test
    void resolveBySsoSubjectNullInput() {
        assertNull(resolver.resolveBySsoSubject(null));
    }

    // ── Mode detection ──

    @Test
    void isPassthroughTrueForPassthroughInstance() {
        assertTrue(UserIdentityResolver.passthrough().isPassthrough());
    }

    @Test
    void isPassthroughFalseForDbBackedInstance() {
        assertFalse(resolver.isPassthrough());
    }
}
