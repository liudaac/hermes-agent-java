package com.nousresearch.hermes.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D6: JWT (JSON Web Token) issuer and verifier.
 *
 * <p>Issues short-lived JWTs for authenticated users. Stateless,
 * multi-instance friendly (no session store needed).</p>
 *
 * <p>Token format: header.payload.signature (HS256)</p>
 *
 * <p>Claims:</p>
 * <ul>
 *   <li>sub - user ID</li>
 *   <li>email - user email</li>
 *   <li>name - display name</li>
 *   <li>exp - expiration (epoch seconds)</li>
 *   <li>iat - issued at (epoch seconds)</li>
 * </ul>
 *
 * <p>Secret is configured via -Djwt.secret=xxx or JWT_SECRET env var.</p>
 */
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    private final byte[] secret;
    private final long defaultTtlSeconds;

    public JwtService() {
        this(getDefaultSecret(), 3600);  // 1 hour default
    }

    public JwtService(String secret, long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.defaultTtlSeconds = ttlSeconds;
    }

    /**
     * Issue a JWT for a user.
     */
    public String issue(UserAccount user) {
        return issue(user, defaultTtlSeconds);
    }

    /**
     * Issue a JWT with custom TTL.
     */
    public String issue(UserAccount user, long ttlSeconds) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.userId());
        claims.put("email", user.email());
        claims.put("name", user.displayName());
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        String header = base64Url(serialize(Map.of("alg", "HS256", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(serialize(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        String signature = base64Url(hmacSha256(signingInput));
        return "jwt_" + signingInput + "." + signature;
    }

    /**
     * Verify a JWT and return the user ID.
     * @return user ID, or null if invalid/expired
     */
    public String verify(String token) {
        if (token == null || !token.startsWith("jwt_")) return null;
        String jwt = token.substring(4);
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;

        String signingInput = parts[0] + "." + parts[1];
        String expectedSig = base64Url(hmacSha256(signingInput));
        if (!constantTimeEquals(expectedSig, parts[2])) {
            logger.debug("JWT signature mismatch");
            return null;
        }

        // Decode payload
        Map<String, Object> claims = deserialize(unbase64Url(parts[1]));
        if (claims == null) return null;

        // Check expiration
        Object expObj = claims.get("exp");
        if (expObj instanceof Number n) {
            long exp = n.longValue();
            if (System.currentTimeMillis() / 1000 > exp) {
                logger.debug("JWT expired for {}", claims.get("sub"));
                return null;
            }
        }
        Object sub = claims.get("sub");
        return sub != null ? sub.toString() : null;
    }

    /**
     * Extract claims from a JWT without verification (for debugging).
     */
    public Map<String, Object> decodeClaims(String token) {
        if (token == null || !token.startsWith("jwt_")) return Map.of();
        String jwt = token.substring(4);
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return Map.of();
        return deserialize(unbase64Url(parts[1]));
    }

    // ============ Internal ============

    private static String getDefaultSecret() {
        String s = System.getProperty("jwt.secret");
        if (s != null && !s.isBlank()) return s;
        s = System.getenv("JWT_SECRET");
        if (s != null && !s.isBlank()) return s;
        // Default secret for LOCAL mode (insecure, but allows single-instance dev)
        return "hermes-local-dev-secret-do-not-use-in-production";
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] unbase64Url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static String serialize(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v instanceof String) sb.append('"').append(escape((String) v)).append('"');
            else if (v instanceof Number) sb.append(v);
            else sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deserialize(byte[] data) {
        // Minimal JSON parser (avoid Jackson dependency)
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            return parseJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> parseJson(String json) {
        // Use project's existing JSON library
        return com.alibaba.fastjson2.JSON.parseObject(json);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
