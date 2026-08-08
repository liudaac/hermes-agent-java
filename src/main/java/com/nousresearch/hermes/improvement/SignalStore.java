package com.nousresearch.hermes.improvement;

import java.util.List;

/**
 * Storage interface for improvement signals.
 *
 * <p>Implementations: LocalSignalStore (memory), RedisSignalStore (Sprint 6),
 * PostgresSignalStore (Sprint 6).</p>
 */
public interface SignalStore {

    /**
     * Save a signal.
     */
    void save(ImprovementSignal signal);

    /**
     * Query all signals for a user in a tenant.
     */
    List<ImprovementSignal> queryByUser(String tenantId, String userId);

    /**
     * Query signals by type for a user in a tenant.
     */
    List<ImprovementSignal> queryByType(String tenantId, String userId, SignalType type);

    /**
     * Query unprocessed signals for a user in a tenant.
     */
    List<ImprovementSignal> queryUnprocessed(String tenantId, String userId);

    /**
     * Mark a signal as processed.
     */
    void markProcessed(String signalId);

    /**
     * Count signals by type for a user.
     */
    int countByType(String tenantId, String userId, SignalType type);
}
