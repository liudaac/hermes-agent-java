package com.nousresearch.hermes.plugin.registry;

import java.util.List;
import java.util.Optional;

/**
 * Generic registry interface for plugin-discoverable components.
 */
public interface Registry<K, V> {
    void register(K key, V entry);
    void unregister(K key);
    Optional<V> get(K key);
    List<V> listAll();
    boolean isRegistered(K key);
}
