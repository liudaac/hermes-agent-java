package com.nousresearch.hermes.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Deployment profile configuration for Hermes.
 *
 * <p>Controls which implementation of each pluggable store is used:</p>
 * <ul>
 *   <li>{@code local} (default) - all in-process: LocalQuotaStore,
 *       LocalApprovalStore, in-memory RateLimiter, file-based repositories.</li>
 *   <li>{@code cluster} - Redis-backed: RedisQuotaStore, RedisApprovalStore,
 *       RedisRateLimiter, Postgres repositories (when available).</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Set via system property or env var:</p>
 * <pre>
 *   -Dhermes.profile=cluster
 *   HERMES_PROFILE=cluster
 * </pre>
 *
 * <p>Redis connection (cluster profile only):</p>
 * <pre>
 *   -Dhermes.redis.url=redis://localhost:6379
 *   -Dhermes.redis.password=secret
 *   HERMES_REDIS_URL=redis://localhost:6379
 * </pre>
 *
 * <p>Postgres connection (cluster profile only, for future PostgresRepository):</p>
 * <pre>
 *   -Dhermes.postgres.url=jdbc:postgresql://localhost:5432/hermes
 *   -Dhermes.postgres.user=hermes
 *   -Dhermes.postgres.password=secret
 * </pre>
 *
 * <h2>Node identity</h2>
 * <p>In cluster mode, each JVM instance needs a unique node ID for sticky
 * routing and approval callbacks:</p>
 * <pre>
 *   -Dhermes.node.id=node-1
 *   HERMES_NODE_ID=node-1
 * </pre>
 *
 * <h2>Extension points</h2>
 * <p>When adding a new Redis-backed store:</p>
 * <ol>
 *   <li>Implement the store interface using {@link RedisOps}</li>
 *   <li>Add a factory method or conditional in BusinessServices.build()</li>
 *   <li>Use {@code profile.isCluster()} to choose between local and Redis impl</li>
 * </ol>
 */
public final class HermesProfile {
    private static final Logger logger = LoggerFactory.getLogger(HermesProfile.class);

    public enum Mode { LOCAL, CLUSTER }

    private final Mode mode;
    private final String nodeId;
    private final String redisUrl;
    private final String redisPassword;
    private final String postgresUrl;
    private final String postgresUser;
    private final String postgresPassword;

    /** Lazily-initialized RedisOps (null in local mode or when no client is available). */
    private volatile RedisOps redisOps;

    public HermesProfile() {
        this(resolveMode(), resolveNodeId(), resolveRedisUrl(), resolveRedisPassword(),
             resolvePostgresUrl(), resolvePostgresUser(), resolvePostgresPassword());
    }

    public HermesProfile(Mode mode, String nodeId, String redisUrl, String redisPassword,
                         String postgresUrl, String postgresUser, String postgresPassword) {
        this.mode = mode;
        this.nodeId = nodeId;
        this.redisUrl = redisUrl;
        this.redisPassword = redisPassword;
        this.postgresUrl = postgresUrl;
        this.postgresUser = postgresUser;
        this.postgresPassword = postgresPassword;
        logger.info("Hermes profile: mode={}, node={}, redis={}, postgres={}",
            mode, nodeId, redisUrl != null ? "configured" : "n/a", postgresUrl != null ? "configured" : "n/a");
    }

    public Mode mode() { return mode; }
    public boolean isLocal() { return mode == Mode.LOCAL; }
    public boolean isCluster() { return mode == Mode.CLUSTER; }
    public String nodeId() { return nodeId; }
    public String redisUrl() { return redisUrl; }
    public String postgresUrl() { return postgresUrl; }

    /**
     * Get the shared RedisOps instance. Returns null in local mode or when
     * no Redis client implementation is on the classpath.
     *
     * <p>To wire a real Redis client, set this field via {@link #setRedisOps}
     * during application bootstrap (e.g. in main() or a Spring @Configuration).</p>
     */
    public RedisOps redisOps() {
        return redisOps;
    }

    /**
     * Inject a RedisOps implementation (e.g. LettuceRedisOps).
     * Once set, all Redis-backed stores will use this instance.
     */
    public void setRedisOps(RedisOps ops) {
        this.redisOps = Objects.requireNonNull(ops);
        logger.info("RedisOps implementation set: {}", ops.getClass().getSimpleName());
    }

    /** Check if Redis is available (cluster mode + redisOps configured). */
    public boolean hasRedis() {
        return mode == Mode.CLUSTER && redisOps != null;
    }

    // ── Config resolvers ────────────────────────────────────

    private static Mode resolveMode() {
        String raw = System.getProperty("hermes.profile",
            System.getenv().getOrDefault("HERMES_PROFILE", "local"));
        return "cluster".equalsIgnoreCase(raw) ? Mode.CLUSTER : Mode.LOCAL;
    }

    private static String resolveNodeId() {
        String id = System.getProperty("hermes.node.id",
            System.getenv().getOrDefault("HERMES_NODE_ID", ""));
        if (id.isBlank()) {
            id = "node-" + Long.toString(ProcessHandle.current().pid(), 36);
            logger.info("Auto-generated node ID: {}", id);
        }
        return id;
    }

    private static String resolveRedisUrl() {
        return System.getProperty("hermes.redis.url",
            System.getenv().getOrDefault("HERMES_REDIS_URL", ""));
    }

    private static String resolveRedisPassword() {
        return System.getProperty("hermes.redis.password",
            System.getenv().getOrDefault("HERMES_REDIS_PASSWORD", ""));
    }

    private static String resolvePostgresUrl() {
        return System.getProperty("hermes.postgres.url",
            System.getenv().getOrDefault("HERMES_POSTGRES_URL", ""));
    }

    private static String resolvePostgresUser() {
        return System.getProperty("hermes.postgres.user",
            System.getenv().getOrDefault("HERMES_POSTGRES_USER", "hermes"));
    }

    private static String resolvePostgresPassword() {
        return System.getProperty("hermes.postgres.password",
            System.getenv().getOrDefault("HERMES_POSTGRES_PASSWORD", ""));
    }
}
