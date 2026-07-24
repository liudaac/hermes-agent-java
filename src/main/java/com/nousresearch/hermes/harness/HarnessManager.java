package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant harness pool manager.
 *
 * <p>Replaces {@code TenantContext.activeAgents} as the single entry point
 * for creating, tracking, and evicting agent harnesses.</p>
 *
 * <p>Each harness wraps a {@link TenantAIAgent} delegate and holds its own
 * {@link AgentContext}, {@link LoopState}, and {@link EventEmitter}.</p>
 */
public class HarnessManager {
    private static final Logger logger = LoggerFactory.getLogger(HarnessManager.class);

    private final TenantContext tenantCtx;
    private final ConcurrentHashMap<String, AgentHarness> active = new ConcurrentHashMap<>();
    private final int maxConcurrent;

    public HarnessManager(TenantContext tenantCtx) {
        this.tenantCtx = tenantCtx;
        int max = 10;
        try {
            if (tenantCtx.getQuotaManager() != null && tenantCtx.getQuotaManager().getQuota() != null) {
                max = tenantCtx.getQuotaManager().getQuota().getMaxConcurrentAgents();
            }
        } catch (Exception ignored) {}
        this.maxConcurrent = max;
    }

    /** Get or create a harness for the given session. */
    public AgentHarness getOrCreate(String sessionId, HermesConfig config) {
        return active.computeIfAbsent(sessionId, sid -> {
            if (active.size() >= maxConcurrent) {
                throw new IllegalStateException(
                    "Max concurrent agents reached: " + maxConcurrent);
            }
            logger.debug("Creating harness for session: {} (active: {})", sid, active.size() + 1);
            return new AgentHarness(tenantCtx, sid, config);
        });
    }

    /** Get an existing harness without creating. */
    public AgentHarness get(String sessionId) {
        return active.get(sessionId);
    }

    /** Remove a harness (session ended). */
    public void remove(String sessionId) {
        AgentHarness h = active.remove(sessionId);
        if (h != null) {
            h.stop();
            logger.debug("Removed harness for session: {} (active: {})", sessionId, active.size());
        }
    }

    /** Number of active harnesses. */
    public int activeCount() {
        return active.size();
    }

    /** List all active harness snapshots. */
    public List<HarnessSnapshot> snapshots() {
        return active.values().stream()
            .map(AgentHarness::snapshot)
            .toList();
    }

    /** Evict harnesses that have been idle for longer than the threshold. */
    public int evictIdle(Duration threshold) {
        long cutoff = System.currentTimeMillis() - threshold.toMillis();
        int evicted = 0;
        var it = active.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            AgentHarness h = entry.getValue();
            if (h.lastActivityMs() < cutoff && h.state().isPaused() == false
                    && h.state().lifecycle() == LoopState.Lifecycle.IDLE) {
                it.remove();
                h.stop();
                evicted++;
                logger.debug("Evicted idle harness: {}", entry.getKey());
            }
        }
        return evicted;
    }

    /** Stop all harnesses (tenant shutdown). */
    public void stopAll() {
        for (var h : active.values()) {
            try { h.stop(); } catch (Exception e) {
                logger.warn("Error stopping harness: {}", e.getMessage());
            }
        }
        active.clear();
    }
}
