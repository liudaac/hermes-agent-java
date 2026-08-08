package com.nousresearch.hermes.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves channel-specific user IDs (e.g. Feishu open_id, QQ user_openid)
 * to a unified internal userId via {@link UserRbacService}.
 *
 * <p>Each IM channel produces a different raw user ID. This resolver
 * normalizes them through the ssoSubject mechanism:</p>
 *
 * <pre>
 *   channel:channelUserId  ->  ssoSubject  ->  UserAccount.userId
 *   "feishu:ou_abc123"     ->  findBySsoSubject  ->  "usr_xxx"
 *   "qq:0F443F..."         ->  findBySsoSubject  ->  "usr_yyy"
 * </pre>
 *
 * <p>When no DataSource is configured (LOCAL mode), the resolver operates
 * in <b>passthrough</b> mode: it returns the raw channelUserId directly.
 * This preserves backward compatibility with Sprint 1 behavior.</p>
 */
public class UserIdentityResolver {

    private static final Logger logger = LoggerFactory.getLogger(UserIdentityResolver.class);

    private final UserRbacService rbacService;
    private final boolean passthrough;

    /**
     * Cache: ssoSubject -> userId (avoids repeated DB lookups).
     */
    private final Map<String, String> ssoCache = new ConcurrentHashMap<>();

    /**
     * Creates a resolver backed by UserRbacService (DB mode).
     */
    public UserIdentityResolver(UserRbacService rbacService) {
        this.rbacService = rbacService;
        this.passthrough = (rbacService == null);
    }

    /**
     * Creates a passthrough resolver (LOCAL mode, no DB).
     * Returns the raw channelUserId as-is.
     */
    public static UserIdentityResolver passthrough() {
        return new UserIdentityResolver(null);
    }

    /**
     * Resolves a channel-specific user ID to the unified internal userId.
     *
     * @param channel       channel name: "feishu", "qq", "wecom", "web"
     * @param channelUserId raw user ID from the IM platform
     * @return unified userId, or the raw channelUserId in passthrough mode
     */
    public String resolveUserId(String channel, String channelUserId) {
        if (channelUserId == null || channelUserId.isBlank()) {
            return null;
        }
        if (channel == null || channel.isBlank()) {
            return null;
        }

        if (passthrough) {
            return channelUserId;
        }

        String ssoSubject = channel + ":" + channelUserId;

        // Check cache first
        String cached = ssoCache.get(ssoSubject);
        if (cached != null) {
            return cached;
        }

        // Look up existing user
        UserAccount user = rbacService.findBySsoSubject(ssoSubject);
        if (user != null) {
            ssoCache.put(ssoSubject, user.userId());
            return user.userId();
        }

        // First login: auto-create a UserAccount
        try {
            String email = channelUserId + "@" + channel + ".local";
            String displayName = channel + ":" + channelUserId.substring(0, Math.min(8, channelUserId.length()));
            UserAccount newUser = rbacService.createUser(email, displayName, ssoSubject);
            ssoCache.put(ssoSubject, newUser.userId());
            logger.info("Auto-created user for channel {}: {} -> {}", channel, channelUserId, newUser.userId());
            return newUser.userId();
        } catch (Exception e) {
            logger.warn("Failed to auto-create user for {}/{}: {}, falling back to raw ID",
                        channel, channelUserId, e.getMessage());
            return channelUserId;
        }
    }

    /**
     * Convenience: resolves using a ssoSubject directly (for testing).
     */
    public String resolveBySsoSubject(String ssoSubject) {
        if (ssoSubject == null) return null;
        if (passthrough) return ssoSubject;

        String cached = ssoCache.get(ssoSubject);
        if (cached != null) return cached;

        UserAccount user = rbacService.findBySsoSubject(ssoSubject);
        if (user != null) {
            ssoCache.put(ssoSubject, user.userId());
            return user.userId();
        }
        return null;
    }

    /**
     * Clears the resolution cache.
     */
    public void invalidateCache() {
        ssoCache.clear();
    }

    /**
     * @return true if this resolver is in passthrough (no-DB) mode
     */
    public boolean isPassthrough() {
        return passthrough;
    }
}
