package com.nousresearch.hermes.auth;

import java.util.Objects;

/**
 * S3-2: SSO / OIDC 配置。
 *
 * <p>复用 AgentIdentityManager 的 OidcConfig 结构，扩展为 Portal 登录用。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * auth:
 *   sso:
 *     enabled: true
 *     provider: keycloak        # 或 azure-ad / okta / generic
 *     issuer-url: https://keycloak.example.com/realms/myrealm
 *     client-id: hermes-portal
 *     client-secret: ${SSO_CLIENT_SECRET}
 *     redirect-uri: https://portal.example.com/api/auth/sso/callback
 *     scopes: ["openid", "profile", "email"]
 *     auto-redirect: true       # 访问 login 页时自动跳转 SSO
 * }</pre>
 */
public class SsoConfig {

    private final boolean enabled;
    private final String provider;
    private final String issuerUrl;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final java.util.List<String> scopes;
    private final boolean autoRedirect;

    public SsoConfig(boolean enabled, String provider, String issuerUrl,
                     String clientId, String clientSecret, String redirectUri,
                     java.util.List<String> scopes, boolean autoRedirect) {
        this.enabled = enabled;
        this.provider = provider != null ? provider : "generic";
        this.issuerUrl = issuerUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.scopes = scopes != null ? java.util.List.copyOf(scopes) : java.util.List.of("openid", "profile", "email");
        this.autoRedirect = autoRedirect;
    }

    public boolean isEnabled() { return enabled; }
    public String getProvider() { return provider; }
    public String getIssuerUrl() { return issuerUrl; }
    public String getClientId() { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public String getRedirectUri() { return redirectUri; }
    public java.util.List<String> getScopes() { return scopes; }
    public boolean isAutoRedirect() { return autoRedirect; }

    /**
     * 构建授权 URL（重定向到 IdP 的登录页）。
     */
    public String buildAuthorizationUrl(String state) {
        Objects.requireNonNull(issuerUrl, "issuerUrl cannot be null");
        Objects.requireNonNull(clientId, "clientId cannot be null");

        String scope = String.join(" ", scopes);
        return issuerUrl + "/protocol/openid-connect/auth" +
            "?response_type=code" +
            "&client_id=" + urlEncode(clientId) +
            "&redirect_uri=" + urlEncode(redirectUri) +
            "&scope=" + urlEncode(scope) +
            "&state=" + urlEncode(state);
    }

    /**
     * 构建 token endpoint URL。
     */
    public String getTokenEndpoint() {
        return issuerUrl + "/protocol/openid-connect/token";
    }

    /**
     * 构建 userinfo endpoint URL。
     */
    public String getUserinfoEndpoint() {
        return issuerUrl + "/protocol/openid-connect/userinfo";
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 从配置 map 创建 SsoConfig。
     */
    @SuppressWarnings("unchecked")
    public static SsoConfig fromMap(java.util.Map<String, Object> map) {
        if (map == null) return new SsoConfig(false, null, null, null, null, null, null, false);

        return new SsoConfig(
            Boolean.TRUE.equals(map.get("enabled")),
            (String) map.get("provider"),
            (String) map.get("issuer-url"),
            (String) map.get("client-id"),
            (String) map.get("client-secret"),
            (String) map.get("redirect-uri"),
            map.get("scopes") instanceof java.util.List<?> list
                ? list.stream().map(Object::toString).toList()
                : null,
            Boolean.TRUE.equals(map.get("auto-redirect"))
        );
    }
}
