package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of SignalStore for local / single-node mode.
 *
 * <p>Stores signals in a ConcurrentHashMap keyed by tenantId.
 * Sprint 6 will add RedisSignalStore and PostgresSignalStore.</p>
 */
public class LocalSignalStore implements SignalStore {

    private static final Logger logger = LoggerFactory.getLogger(LocalSignalStore.class);

    // tenantId -> signals
    private final Map<String, List<ImprovementSignal>> store = new ConcurrentHashMap<>();

    @Override
    public void save(ImprovementSignal signal) {
        store.computeIfAbsent(signal.tenantId(), k -> new CopyOnWriteArrayList<>())
             .add(signal);
        logger.debug("Saved signal: type={}, user={}, weight={}",
                     signal.type(), signal.userId(), signal.weight());
    }

    @Override
    public List<ImprovementSignal> queryByUser(String tenantId, String userId) {
        List<ImprovementSignal> signals = store.getOrDefault(tenantId, List.of());
        if (userId == null) {
            return new ArrayList<>(signals);
        }
        return signals.stream()
                .filter(s -> userId.equals(s.userId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ImprovementSignal> queryByType(String tenantId, String userId, SignalType type) {
        return queryByUser(tenantId, userId).stream()
                .filter(s -> s.type() == type)
                .collect(Collectors.toList());
    }

    @Override
    public List<ImprovementSignal> queryUnprocessed(String tenantId, String userId) {
        return queryByUser(tenantId, userId).stream()
                .filter(s -> !s.processed())
                .collect(Collectors.toList());
    }

    @Override
    public void markProcessed(String signalId) {
        for (List<ImprovementSignal> signals : store.values()) {
            for (int i = 0; i < signals.size(); i++) {
                if (signals.get(i).id().equals(signalId)) {
                    signals.set(i, signals.get(i).markProcessed());
                    return;
                }
            }
        }
    }

    @Override
    public int countByType(String tenantId, String userId, SignalType type) {
        return (int) queryByUser(tenantId, userId).stream()
                .filter(s -> s.type() == type)
                .count();
    }

    /**
     * Clears all signals for testing.
     */
    public void clear() {
        store.clear();
    }
}
