package com.nousresearch.hermes.common;

/**
 * Unified Redis operations interface for multi-instance Hermes deployments.
 *
 * <p>All Redis-backed stores ({@link com.nousresearch.hermes.tenant.quota.RedisQuotaStore},
 * {@link com.nousresearch.hermes.business.approval.RedisApprovalStore}, future
 * RedisRateLimiter, RedisSessionStore) use this single interface instead of
 * defining their own nested functional interfaces.</p>
 *
 * <h2>Implementation guide</h2>
 * <p>To wire a real Redis client (Lettuce or Jedis), create a single adapter
 * class:</p>
 * <pre>{@code
 * public class LettuceRedisOps implements RedisOps {
 *     private final RedisCommands<String, String> commands;
 *
 *     public LettuceRedisOps(RedisCommands<String, String> commands) {
 *         this.commands = commands;
 *     }
 *
 *     @Override public void set(String key, String value) { commands.set(key, value); }
 *     @Override public String get(String key) { return commands.get(key); }
 *     // ... etc
 * }
 * }</pre>
 *
 * <p>Then pass it to QuotaStoreFactory, RedisApprovalStore, etc. via the
 * {@link com.nousresearch.hermes.common.HermesProfile} configuration.</p>
 *
 * <h2>Phase 4b status</h2>
 * <p>Interface is final; no Redis client dependency is on the classpath yet.
 * When a Redis client is added (lettuce-core or jedis), implement this
 * interface and wire it through HermesProfile. The Local* implementations
 * remain the default for single-instance deployments.</p>
 */
public interface RedisOps {

    // ── String operations ───────────────────────────────────
    void set(String key, String value);
    String get(String key);
    boolean exists(String key);
    void del(String key);
    void expire(String key, int seconds);

    // ── Hash operations ─────────────────────────────────────
    void hset(String key, String field, String value);
    String hget(String key, String field);
    java.util.Map<String, String> hgetAll(String key);
    void hdel(String key, String... fields);

    // ── Counter operations ──────────────────────────────────
    long incr(String key);
    long decr(String key);

    // ── Sorted Set operations ───────────────────────────────
    /**
     * Add a member to a sorted set with the given score.
     */
    void zadd(String key, double score, String member);

    /**
     * Remove and return members from a sorted set with scores in [min, max].
     *
     * @return list of removed members (empty if none)
     */
    java.util.List<String> zpoprangebyscore(String key, double min, double max);

    /**
     * Return members from a sorted set with scores in [min, max].
     * Non-removing (read-only).
     */
    java.util.List<String> zrangebyscore(String key, double min, double max);

    /**
     * Count members in a sorted set.
     */
    long zcard(String key);

    /**
     * Remove a member from a sorted set.
     */
    void zrem(String key, String member);

    // ── Lua script ──────────────────────────────────────────
    /**
     * Execute a Lua script.
     *
     * @param script Lua source
     * @param keys   keys referenced in the script
     * @param args   arguments passed to the script
     * @return script return value as Long (or null)
     */
    Long eval(String script, java.util.List<String> keys, java.util.List<String> args);

    // ── Pub/Sub ─────────────────────────────────────────────
    /**
     * Subscribe to a pattern (e.g. "approval:results:*").
     *
     * @param pattern  Redis key pattern
     * @param listener callback receiving (channel, message)
     */
    void subscribePattern(String pattern, PubSubListener listener);

    /**
     * Publish a message to a channel.
     */
    void publish(String channel, String message);

    // ── Lifecycle ───────────────────────────────────────────
    /** Close the connection pool. */
    void close();

    /** Listener for Pub/Sub messages. */
    @FunctionalInterface
    interface PubSubListener {
        void onMessage(String channel, String message);
    }
}
