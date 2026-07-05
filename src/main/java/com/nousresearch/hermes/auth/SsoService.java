package com.nousresearch.hermes.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3-2: SSO 服务 — 处理 OIDC 授权码流程。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>{@link #startLogin} — 生成 state，返回授权 URL（前端重定向到 IdP）</li>
 *   <li>{@link #handleCallback} — IdP 回调，用 code 换 token，解析用户信息</li>
 *   <li>{@link #validateSession} — 验证本地 session</li>
 *   <li>{@link #logout} — 登出</li>
 * </ol>
 */
public class SsoService {
    private static final Logger logger = LoggerFactory.getLogger(SsoService.class);

    private final SsoConfig config;
    private final HttpClient httpClient;

    /** state → 创建时间（防 CSRF + 关联请求） */
    private final Map<String, Instant> pendingStates = new ConcurrentHashMap<>();

    /** sessionToken → SsoSession */
    private final Map<String, SsoSession> sessions = new ConcurrentHashMap<>();

    /** state 有效期 5 分钟 */
    private static final long STATE_TTL_SECONDS = 300;

    /** session 有效期 8 小时 */
    private static final long SESSION_TTL_SECONDS = 28800;

    public SsoService(SsoConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    }

    /**
     * 开始 SSO 登录 — 生成 state + 返回授权 URL。
     *
     * @return 授权 URL，或 null 如果 SSO 未启用
     */
    public String startLogin() {
        if (!config.isEnabled()) return null;

        String state = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(state, Instant.now());
        cleanupExpiredStates();

        String authUrl = config.buildAuthorizationUrl(state);
        logger.info("SSO login started: state={}, provider={}", state, config.getProvider());
        return authUrl;
    }

    /**
     * 处理 IdP 回调 — 用 code 换 token + 解析用户信息。
     *
     * @param code 授权码
     * @param state 状态参数（防 CSRF）
     * @return SSO session（含 sessionToken + 用户信息），或 null 如果失败
     */
    public SsoSession handleCallback(String code, String state) {
        if (!config.isEnabled()) return null;

        // 验证 state
        Instant stateCreated = pendingStates.remove(state);
        if (stateCreated == null) {
            logger.warn("SSO callback with unknown state: {}", state);
            return null;
        }
        if (Instant.now().isAfter(stateCreated.plusSeconds(STATE_TTL_SECONDS))) {
            logger.warn("SSO callback with expired state: {}", state);
            return null;
        }

        try {
            // 用 code 换 token
            TokenResponse tokenResponse = exchangeCodeForToken(code);
            if (tokenResponse == null) {
                logger.error("Failed to exchange code for token");
                return null;
            }

            // 获取用户信息
            UserInfo userInfo = fetchUserInfo(tokenResponse.accessToken());
            if (userInfo == null) {
                logger.error("Failed to fetch user info");
                return null;
            }

            // 创建本地 session
            String sessionToken = UUID.randomUUID().toString().replace("-", "");
            SsoSession session = new SsoSession(
                sessionToken,
                userInfo.subject(),
                userInfo.email(),
                userInfo.name(),
                userInfo.preferredUsername(),
                Instant.now(),
                Instant.now().plusSeconds(SESSION_TTL_SECONDS),
                config.getProvider(),
                tokenResponse.accessToken(),
                tokenResponse.refreshToken()
            );
            sessions.put(sessionToken, session);

            logger.info("SSO login success: user={}, provider={}",
                userInfo.subject(), config.getProvider());
            return session;

        } catch (Exception e) {
            logger.error("SSO callback failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 验证 session 是否有效。
     */
    public Optional<SsoSession> validateSession(String sessionToken) {
        if (sessionToken == null) return Optional.empty();
        SsoSession session = sessions.get(sessionToken);
        if (session == null) return Optional.empty();
        if (Instant.now().isAfter(session.expiresAt())) {
            sessions.remove(sessionToken);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * 登出。
     */
    public void logout(String sessionToken) {
        if (sessionToken != null) {
            sessions.remove(sessionToken);
            logger.info("SSO logout: session={}", sessionToken.substring(0, 8) + "...");
        }
    }

    /**
     * 用授权码换 token。
     */
    private TokenResponse exchangeCodeForToken(String code) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", config.getRedirectUri());
        params.put("client_id", config.getClientId());
        params.put("client_secret", config.getClientSecret());

        String formBody = params.entrySet().stream()
            .map(e -> {
                try {
                    return e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), "UTF-8");
                } catch (java.io.UnsupportedEncodingException ex) {
                    return e.getKey() + "=" + e.getValue();
                }
            })
            .reduce((a, b) -> a + "&" + b)
            .orElse("");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getTokenEndpoint()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("Token exchange failed: status={}, body={}", response.statusCode(), response.body());
            return null;
        }

        // 解析 JSON 响应
        com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(response.body());
        return new TokenResponse(
            json.getString("access_token"),
            json.getString("refresh_token"),
            json.getString("id_token"),
            json.getLongValue("expires_in", 3600)
        );
    }

    /**
     * 获取用户信息。
     */
    private UserInfo fetchUserInfo(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getUserinfoEndpoint()))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("Userinfo fetch failed: status={}", response.statusCode());
            return null;
        }

        com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(response.body());
        return new UserInfo(
            json.getString("sub"),
            json.getString("email"),
            json.getString("name"),
            json.getString("preferred_username")
        );
    }

    /** 清理过期 state */
    private void cleanupExpiredStates() {
        Instant cutoff = Instant.now().minusSeconds(STATE_TTL_SECONDS);
        pendingStates.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    public SsoConfig getConfig() { return config; }
    public int activeSessionCount() { return sessions.size(); }

    // ============ 内部数据类 ============

    public record TokenResponse(String accessToken, String refreshToken, String idToken, long expiresIn) {}

    public record UserInfo(String subject, String email, String name, String preferredUsername) {}

    public record SsoSession(
        String sessionToken,
        String subject,
        String email,
        String name,
        String preferredUsername,
        Instant issuedAt,
        Instant expiresAt,
        String provider,
        String accessToken,
        String refreshToken
    ) {
        public boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
