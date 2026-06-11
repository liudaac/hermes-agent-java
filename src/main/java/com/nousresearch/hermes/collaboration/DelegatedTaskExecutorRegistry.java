package com.nousresearch.hermes.collaboration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Registry for safe delegated-task executor adapters. */
public class DelegatedTaskExecutorRegistry {
    private final Map<String, DelegatedTaskExecutor> executors = new LinkedHashMap<>();

    public DelegatedTaskExecutorRegistry() {
        register(new NoopDelegatedTaskExecutor());
        register(new MockDelegatedTaskExecutor());
        register(new LocalPatchExecutor());
    }

    public synchronized DelegatedTaskExecutorRegistry register(DelegatedTaskExecutor executor) {
        if (executor == null || executor.name() == null || executor.name().isBlank()) {
            throw new IllegalArgumentException("executor with name is required");
        }
        executors.put(normalize(executor.name()), executor);
        return this;
    }

    public synchronized Optional<DelegatedTaskExecutor> find(String name) {
        return Optional.ofNullable(executors.get(normalize(name)));
    }

    public synchronized Map<String, DelegatedTaskExecutor> executors() {
        return Map.copyOf(executors);
    }

    public static String normalize(String name) {
        return name == null || name.isBlank() ? NoopDelegatedTaskExecutor.NAME : name.trim().toLowerCase(Locale.ROOT);
    }
}
