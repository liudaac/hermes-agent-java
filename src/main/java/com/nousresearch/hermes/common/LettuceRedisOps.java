package com.nousresearch.hermes.common;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * Lettuce-based {@link RedisOps} implementation.
 *
 * <p>Created during Sprint B to wire real Redis into the Hermes store layer.
 * Pass to {@link HermesProfile#setRedisOps} during application bootstrap:</p>
 *
 * <pre>{@code
 * HermesProfile profile = HermesProfile.current();
 * if (profile.isCluster()) {
 *     try (LettuceRedisOps redis = new LettuceRedisOps(
 *              profile.redisUrl(), profile.redisPassword())) {
 *         profile.setRedisOps(redis);
 *     }
 * }
 * }</pre>
 */
public class LettuceRedisOps implements RedisOps, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LettuceRedisOps.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;
    private final List<StatefulRedisPubSubConnection<String, String>> pubSubConnections = new ArrayList<>();

    public LettuceRedisOps(String redisUrl, String password) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
            .withTimeout(Duration.ofSeconds(5));

        if (redisUrl != null && !redisUrl.isBlank()) {
            // Parse redis://host:port or host:port
            if (redisUrl.startsWith("redis://")) {
                uriBuilder = RedisURI.builder(RedisURI.create(redisUrl));
            } else {
                String[] parts = redisUrl.split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;
                uriBuilder.withHost(host).withPort(port);
            }
        } else {
            uriBuilder.withHost("localhost").withPort(6379);
        }

        if (password != null && !password.isBlank()) {
            uriBuilder.withPassword(password.toCharArray());
        }

        this.client = RedisClient.create(uriBuilder.build());
        this.connection = client.connect();
        this.sync = connection.sync();
        logger.info("LettuceRedisOps connected to Redis");
    }

    // ── String operations ───────────────────────────────────

    @Override public void set(String key, String value) { sync.set(key, value); }
    @Override public String get(String key) { return sync.get(key); }
    @Override public boolean exists(String key) { return sync.exists(key) > 0; }
    @Override public void del(String key) { sync.del(key); }
    @Override public void expire(String key, int seconds) { sync.expire(key, seconds); }

    // ── Hash operations ─────────────────────────────────────

    @Override public void hset(String key, String field, String value) { sync.hset(key, field, value); }
    @Override public String hget(String key, String field) { return sync.hget(key, field); }
    @Override public Map<String, String> hgetAll(String key) { return sync.hgetall(key); }
    @Override public void hdel(String key, String... fields) { sync.hdel(key, fields); }

    // ── Counter operations ──────────────────────────────────

    @Override public long incr(String key) { return sync.incr(key); }
    @Override public long decr(String key) { return sync.decr(key); }

    // ── Sorted Set operations ───────────────────────────────

    @Override
    public void zadd(String key, double score, String member) {
        sync.zadd(key, score, member);
    }

    @Override
    public List<String> zpoprangebyscore(String key, double min, double max) {
        // First get the members, then remove them
        Range<Double> range = Range.create(min, max);
        List<String> members = sync.zrangebyscore(key, range);
        if (!members.isEmpty()) {
            sync.zrem(key, members.toArray(new String[0]));
        }
        return members;
    }

    @Override
    public List<String> zrangebyscore(String key, double min, double max) {
        Range<Double> range = Range.create(min, max);
        return sync.zrangebyscore(key, range);
    }

    @Override
    public long zcard(String key) { return sync.zcard(key); }

    @Override
    public void zrem(String key, String member) { sync.zrem(key, member); }

    // ── Lua script ──────────────────────────────────────────

    @Override
    public Long eval(String script, List<String> keys, List<String> args) {
        return sync.eval(script, ScriptOutputType.INTEGER,
            keys.toArray(new String[0]),
            args.toArray(new String[0]));
    }

    // ── Pub/Sub ─────────────────────────────────────────────

    @Override
    public void subscribePattern(String pattern, PubSubListener listener) {
        StatefulRedisPubSubConnection<String, String> pubSubConn = client.connectPubSub();
        pubSubConn.addListener(new RedisPubSubListener<>() {
            @Override
            public void message(String channel, String message) {
                listener.onMessage(channel, message);
            }

            @Override
            public void message(String pattern, String channel, String message) {
                listener.onMessage(channel, message);
            }

            @Override public void subscribed(String channel, long count) {}
            @Override public void psubscribed(String pattern, long count) {}
            @Override public void unsubscribed(String channel, long count) {}
            @Override public void punsubscribed(String pattern, long count) {}
        });
        pubSubConn.sync().psubscribe(pattern);
        pubSubConnections.add(pubSubConn);
        logger.info("Subscribed to Redis pattern: {}", pattern);
    }

    @Override
    public void publish(String channel, String message) {
        sync.publish(channel, message);
    }

    // ── Lifecycle ───────────────────────────────────────────

    @Override
    public void close() {
        for (var conn : pubSubConnections) {
            try { conn.close(); } catch (Exception e) { logger.warn("Failed to close pubsub connection", e); }
        }
        pubSubConnections.clear();
        try { connection.close(); } catch (Exception e) { logger.warn("Failed to close connection", e); }
        try { client.shutdown(); } catch (Exception e) { logger.warn("Failed to shutdown client", e); }
        logger.info("LettuceRedisOps closed");
    }
}
