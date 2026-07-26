package com.nousresearch.hermes.config.versioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P3: Canary deployment manager - gradual config rollout.
 *
 * <p>Instead of applying config changes to all sessions immediately,
 * canary deployment rolls out changes gradually:</p>
 *
 * <pre>
 *   Step 1: 10% of new sessions use new config (canary)
 *   Step 2: 50% of new sessions use new config
 *   Step 3: 100% - config is fully rolled out
 * </pre>
 *
 * <p>If errors are detected in canary sessions, the rollout can be
 * paused or rolled back automatically.</p>
 *
 * <p>Session assignment is deterministic: same sessionId always gets
 * the same config (canary or stable), based on hash.</p>
 */
public class CanaryDeploymentManager {

    private static final Logger logger = LoggerFactory.getLogger(CanaryDeploymentManager.class);

    /**
     * Rollout strategy.
     */
    public enum Strategy {
        IMMEDIATE,   // 100% immediately (default, no canary)
        PERCENTAGE,  // hash-based percentage rollout
        EXPLICIT     // only explicitly listed sessions get canary
    }

    private record CanaryConfig(
            String tenantId,
            String versionId,
            Strategy strategy,
            int percentage,
            Set<String> explicitSessionIds,
            boolean autoRollbackOnError,
            int errorThreshold
    ) {}

    private final Map<String, CanaryConfig> activeCanaries = new ConcurrentHashMap<>();

    /**
     * Start a canary deployment for a tenant's config version.
     *
     * @param tenantId    tenant to roll out to
     * @param versionId   config version to deploy
     * @param strategy    rollout strategy
     * @param percentage  for PERCENTAGE: 0-100
     * @param autoRollback if true, auto-rollback when error threshold exceeded
     * @param errorThreshold max errors before auto-rollback
     */
    public void startCanary(String tenantId, String versionId,
                           Strategy strategy, int percentage,
                           boolean autoRollback, int errorThreshold) {
        CanaryConfig config = new CanaryConfig(
            tenantId, versionId, strategy,
            Math.max(0, Math.min(100, percentage)),
            new HashSet<>(), autoRollback, errorThreshold
        );
        activeCanaries.put(tenantId, config);
        logger.info("Canary started: tenant={} version={} strategy={} percentage={}%",
            tenantId, versionId, strategy, percentage);
    }

    /**
     * Update the canary percentage (promote to next stage).
     */
    public void promote(String tenantId, int newPercentage) {
        CanaryConfig current = activeCanaries.get(tenantId);
        if (current == null) {
            logger.warn("No active canary for tenant {}", tenantId);
            return;
        }
        CanaryConfig updated = new CanaryConfig(
            current.tenantId(), current.versionId(), current.strategy(),
            Math.max(0, Math.min(100, newPercentage)),
            current.explicitSessionIds(), current.autoRollbackOnError(),
            current.errorThreshold()
        );
        activeCanaries.put(tenantId, updated);
        logger.info("Canary promoted: tenant={} percentage={}%",
            tenantId, newPercentage);
    }

    /**
     * Complete the canary (100% rollout, remove canary).
     */
    public void complete(String tenantId) {
        activeCanaries.remove(tenantId);
        logger.info("Canary completed: tenant={}", tenantId);
    }

    /**
     * Abort the canary (rollback to stable).
     */
    public void abort(String tenantId, String reason) {
        activeCanaries.remove(tenantId);
        logger.warn("Canary aborted: tenant={} reason={}", tenantId, reason);
    }

    /**
     * Check if a session should use the canary config.
     * Deterministic: same sessionId always gets the same answer.
     */
    public boolean shouldUseCanary(String tenantId, String sessionId) {
        CanaryConfig config = activeCanaries.get(tenantId);
        if (config == null) return false; // no canary active

        return switch (config.strategy()) {
            case IMMEDIATE -> true;
            case PERCENTAGE -> hashPercentage(sessionId, config.versionId()) < config.percentage();
            case EXPLICIT -> config.explicitSessionIds().contains(sessionId);
        };
    }

    /**
     * Add a specific session to the canary (for EXPLICIT strategy).
     */
    public void addSession(String tenantId, String sessionId) {
        CanaryConfig current = activeCanaries.get(tenantId);
        if (current != null) {
            current.explicitSessionIds().add(sessionId);
        }
    }

    /**
     * Check if a canary is active for a tenant.
     */
    public boolean hasCanary(String tenantId) {
        return activeCanaries.containsKey(tenantId);
    }

    /**
     * Get canary status for a tenant.
     */
    public Map<String, Object> getStatus(String tenantId) {
        CanaryConfig config = activeCanaries.get(tenantId);
        if (config == null) {
            return Map.of("active", false);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", true);
        status.put("versionId", config.versionId());
        status.put("strategy", config.strategy().name());
        status.put("percentage", config.percentage());
        status.put("autoRollback", config.autoRollbackOnError());
        status.put("errorThreshold", config.errorThreshold());
        return status;
    }

    // ============ Internal ============

    /**
     * Deterministic hash: same sessionId + versionId -> same percentage bucket.
     * Ensures session stickiness during canary.
     */
    private static int hashPercentage(String sessionId, String versionId) {
        int hash = Math.abs((sessionId + ":" + versionId).hashCode());
        return hash % 100;
    }
}
