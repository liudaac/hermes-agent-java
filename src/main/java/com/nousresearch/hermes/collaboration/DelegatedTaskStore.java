package com.nousresearch.hermes.collaboration;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory store for simulated delegated-task lifecycle state.
 * Intended as a future orchestrator API surface, not persistent execution infra.
 */
public class DelegatedTaskStore {
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<String, DelegatedTask> tasks = new ConcurrentHashMap<>();

    public DelegatedTask createPending(DelegatedTaskEnvelope envelope) {
        return createPending(envelope, ParentVerificationPolicy.strict());
    }

    public DelegatedTask createPending(DelegatedTaskEnvelope envelope, ParentVerificationPolicy policy) {
        String id = "delegated_" + ids.incrementAndGet();
        DelegatedTask task = new DelegatedTask(id, envelope, policy != null ? policy : ParentVerificationPolicy.strict());
        tasks.put(id, task);
        return task;
    }

    public DelegatedTask get(String taskId) {
        return tasks.get(taskId);
    }

    public List<DelegatedTask> list() {
        return tasks.values().stream()
            .sorted(Comparator.comparing(DelegatedTask::createdAt).reversed())
            .toList();
    }

    public ParentVerificationResult submitResult(String taskId, DelegatedTaskResult result) {
        DelegatedTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown delegated task: " + taskId);
        return task.submitResult(result);
    }

    public ParentVerificationResult verify(String taskId, ParentVerificationPolicy policy) {
        DelegatedTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown delegated task: " + taskId);
        return task.verifyWithPolicy(policy);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tasks", list().stream().map(DelegatedTask::toMap).toList());
        m.put("count", tasks.size());
        return m;
    }
}
