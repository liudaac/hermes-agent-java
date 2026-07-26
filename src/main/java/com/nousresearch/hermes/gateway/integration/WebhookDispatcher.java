package com.nousresearch.hermes.gateway.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * D4: Webhook dispatcher - pushes events to registered business system URLs.
 *
 * <p>Business systems register webhook subscriptions specifying which event types
 * they want to receive. When an event occurs, Hermes POSTs a signed payload
 * to each subscriber.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>HMAC-SHA256 signature (X-Hermes-Signature header)</li>
 *   <li>Retry with exponential backoff (3 attempts: 1s, 5s, 30s)</li>
 *   <li>Failure tracking (disable subscription after 10 consecutive failures)</li>
 *   <li>Async (non-blocking, uses cached HttpClient)</li>
 * </ul>
 */
public class WebhookDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final DataSource dataSource;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    // Retry delays: 1s, 5s, 30s
    private static final long[] RETRY_DELAYS_MS = {1000, 5000, 30000};
    private static final int MAX_FAILURES = 10;

    public WebhookDispatcher(DataSource dataSource) {
        this.dataSource = dataSource;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hermes-webhook-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Register a webhook subscription.
     */
    public void subscribe(String tenantId, String systemId, String url,
                          List<String> events, String secret) {
        String sql = """
            INSERT INTO webhook_subscription (tenant_id, system_id, url, events, secret)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, systemId);
            ps.setString(3, url);
            ps.setString(4, String.join(",", events));
            ps.setString(5, secret);
            ps.executeUpdate();
            logger.info("Webhook subscribed: tenant={} system={} url={}", tenantId, systemId, url);
        } catch (SQLException e) {
            logger.error("Failed to subscribe webhook: {}", e.getMessage());
            throw new RuntimeException("Failed to subscribe webhook", e);
        }
    }

    /**
     * Dispatch an event to all matching subscribers.
     * Non-blocking (runs in background thread).
     *
     * @param tenantId  the tenant context
     * @param eventType event type (e.g. "task.completed", "approval.requested")
     * @param payload   JSON string payload
     */
    public void dispatch(String tenantId, String eventType, String payload) {
        executor.submit(() -> {
            List<Subscription> subs = findSubscribers(tenantId, eventType);
            for (Subscription sub : subs) {
                sendWithRetry(sub, eventType, payload);
            }
        });
    }

    /**
     * List all subscriptions for a tenant.
     */
    public List<Map<String, Object>> listSubscriptions(String tenantId) {
        String sql = "SELECT * FROM webhook_subscription WHERE tenant_id = ? ORDER BY created_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("systemId", rs.getString("system_id"));
                    m.put("url", rs.getString("url"));
                    m.put("events", rs.getString("events"));
                    m.put("isActive", rs.getBoolean("is_active"));
                    m.put("failureCount", rs.getInt("failure_count"));
                    m.put("createdAt", rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant().toString() : null);
                    result.add(m);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list webhook subscriptions: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Unsubscribe by ID.
     */
    public boolean unsubscribe(long id) {
        String sql = "DELETE FROM webhook_subscription WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to unsubscribe webhook {}: {}", id, e.getMessage());
        }
        return false;
    }

    // ============ Internal ============

    private List<Subscription> findSubscribers(String tenantId, String eventType) {
        String sql = "SELECT * FROM webhook_subscription WHERE tenant_id = ? AND is_active = 1";
        List<Subscription> subs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String eventsStr = rs.getString("events");
                    if (eventsStr != null && Arrays.asList(eventsStr.split(",")).contains(eventType)) {
                        subs.add(new Subscription(
                            rs.getLong("id"),
                            rs.getString("system_id"),
                            rs.getString("url"),
                            rs.getString("secret"),
                            rs.getInt("failure_count")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find subscribers: {}", e.getMessage());
        }
        return subs;
    }

    private void sendWithRetry(Subscription sub, String eventType, String payload) {
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt - 1]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                String signature = hmacSha256(sub.secret(), payload);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sub.url()))
                    .header("Content-Type", "application/json")
                    .header("X-Hermes-Signature", "sha256=" + signature)
                    .header("X-Hermes-Event", eventType)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    markSuccess(sub.id());
                    logger.debug("Webhook delivered: {} -> {} (status={})",
                        eventType, sub.url(), response.statusCode());
                    return;
                }
                logger.warn("Webhook returned {}: {} -> {}",
                    response.statusCode(), eventType, sub.url());
            } catch (Exception e) {
                logger.warn("Webhook attempt {} failed: {} -> {} - {}",
                    attempt + 1, eventType, sub.url(), e.getMessage());
            }
        }
        // All retries exhausted
        markFailure(sub.id());
        logger.error("Webhook delivery exhausted retries: {} -> {}", eventType, sub.url());
    }

    private void markSuccess(long id) {
        String sql = "UPDATE webhook_subscription SET failure_count = 0, last_success = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private void markFailure(long id) {
        String sql = """
            UPDATE webhook_subscription
            SET failure_count = failure_count + 1,
                last_failure = NOW(),
                is_active = IF(failure_count + 1 >= ?, 0, is_active)
            WHERE id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MAX_FAILURES);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private record Subscription(long id, String systemId, String url, String secret, int failureCount) {}
}
