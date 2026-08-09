package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.common.RedisOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cross-instance signal broadcaster using Redis Pub/Sub.
 *
 * <p>When a signal is emitted on instance A, it's published to a Redis channel.
 * All instances (including A) receive the message and update their local caches.
 * This ensures that preference learning and pattern detection have access to
 * signals from all instances, not just local ones.</p>
 *
 * <p>Channel naming: {@code improvement:signals:{tenantId}}</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * RedisOps redis = ...;
 * SignalBroadcaster broadcaster = new SignalBroadcaster(redis);
 * broadcaster.subscribe("tenant-1", signal -> {
 *     // handle signal from any instance
 * });
 *
 * // When a signal is emitted:
 * broadcaster.broadcast(signal);
 * }</pre>
 */
public class SignalBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
    private static final String CHANNEL_PREFIX = "improvement:signals:";

    private final RedisOps redisOps;
    private final Set<String> subscribedPatterns = ConcurrentHashMap.newKeySet();

    public SignalBroadcaster(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    /**
     * Broadcast a signal to all instances.
     */
    public void broadcast(ImprovementSignal signal) {
        try {
            String channel = CHANNEL_PREFIX + signal.tenantId();
            String message = serialize(signal);
            redisOps.publish(channel, message);
            logger.debug("Broadcasted signal {} to channel {}", signal.id(), channel);
        } catch (Exception e) {
            logger.warn("Failed to broadcast signal: {}", e.getMessage());
        }
    }

    /**
     * Subscribe to signals for a specific tenant.
     *
     * @param tenantId tenant to subscribe to
     * @param listener callback for received signals
     */
    public void subscribe(String tenantId, SignalListener listener) {
        String pattern = CHANNEL_PREFIX + tenantId + ":*";
        // Redis pattern matching: improvement:signals:tenant-1:*
        // But we publish to improvement:signals:tenant-1 (no suffix)
        // So use exact channel subscription via pattern
        String channel = CHANNEL_PREFIX + tenantId;

        if (subscribedPatterns.add(channel)) {
            try {
                redisOps.subscribePattern(channel + "*", (ch, msg) -> {
                    ImprovementSignal signal = deserialize(msg);
                    if (signal != null) {
                        listener.onSignal(signal);
                    }
                });
                logger.info("Subscribed to signal channel: {}", channel);
            } catch (Exception e) {
                logger.warn("Failed to subscribe to {}: {}", channel, e.getMessage());
            }
        }
    }

    /**
     * Broadcast a preference update to all instances.
     * Channel: {@code improvement:preferences:{tenantId}:{userId}}
     */
    public void broadcastPreference(String tenantId, String userId, PreferenceUpdate pref) {
        try {
            String channel = "improvement:preferences:" + tenantId + ":" + userId;
            String message = pref.key() + "=" + pref.newValue() + "|" + pref.confidence();
            redisOps.publish(channel, message);
            logger.debug("Broadcasted preference update: {} = {}", pref.key(), pref.newValue());
        } catch (Exception e) {
            logger.warn("Failed to broadcast preference: {}", e.getMessage());
        }
    }

    // ── Serialization ────────────────────────────────────────

    private String serialize(ImprovementSignal signal) {
        // Simple pipe-delimited format (avoid JSON dependency)
        return String.join("|",
                signal.id(),
                signal.tenantId(),
                signal.userId() != null ? signal.userId() : "",
                signal.type().name(),
                signal.sessionId() != null ? signal.sessionId() : "",
                signal.content() != null ? signal.content().replace("|", "/") : "",
                String.valueOf(signal.weight()),
                String.valueOf(signal.timestamp()),
                String.valueOf(signal.processed()),
                signal.scope() != null ? signal.scope().name() : SignalScope.USER.name()
        );
    }

    private ImprovementSignal deserialize(String message) {
        try {
            String[] parts = message.split("\\|", -1);
            if (parts.length < 9) return null;
            SignalScope scope = parts.length > 9 && !parts[9].isEmpty()
                ? SignalScope.valueOf(parts[9]) : SignalScope.USER;
            return new ImprovementSignal(
                    parts[0],                         // id
                    parts[1],                         // tenantId
                    parts[2].isEmpty() ? null : parts[2],  // userId
                    SignalType.valueOf(parts[3]),     // type
                    scope,                            // scope
                    parts[4].isEmpty() ? null : parts[4],  // sessionId
                    parts[5],                         // content
                    Double.parseDouble(parts[6]),     // weight
                    Long.parseLong(parts[7]),         // timestamp
                    Boolean.parseBoolean(parts[8]),   // processed
                    java.util.Map.of()                // metadata (not serialized in broadcast)
            );
        } catch (Exception e) {
            logger.warn("Failed to deserialize signal: {}", e.getMessage());
            return null;
        }
    }

    /** Listener for broadcasted signals. */
    @FunctionalInterface
    public interface SignalListener {
        void onSignal(ImprovementSignal signal);
    }
}
