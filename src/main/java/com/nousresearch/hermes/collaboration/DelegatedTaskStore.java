package com.nousresearch.hermes.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Store for simulated delegated-task lifecycle state.
 * Intended as an orchestrator API surface and persisted tenant state, not
 * external execution infrastructure.
 */
public class DelegatedTaskStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<String, DelegatedTask> tasks = new ConcurrentHashMap<>();
    private final Path storePath;
    private final DelegatedTaskExecutorRegistry executorRegistry = new DelegatedTaskExecutorRegistry();

    public DelegatedTaskStore() {
        this(null);
    }

    public DelegatedTaskStore(Path storePath) {
        this.storePath = storePath;
        load();
    }

    public synchronized DelegatedTask createPending(DelegatedTaskEnvelope envelope) {
        return createPending(envelope, ParentVerificationPolicy.strict());
    }

    public synchronized DelegatedTask createPending(DelegatedTaskEnvelope envelope, ParentVerificationPolicy policy) {
        String id = "delegated_" + ids.incrementAndGet();
        DelegatedTask task = new DelegatedTask(id, envelope, policy != null ? policy : ParentVerificationPolicy.strict());
        tasks.put(id, task);
        save();
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

    public synchronized ParentVerificationResult submitResult(String taskId, DelegatedTaskResult result) {
        DelegatedTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown delegated task: " + taskId);
        ParentVerificationResult verification = task.submitResult(result);
        save();
        return verification;
    }

    /**
     * Execute a pending delegated task through a named safe executor adapter and,
     * when an adapter produces a DelegatedTaskResult, submit it for parent
     * verification. This does not add any real external execution by itself;
     * the default noop adapter returns NOT_EXECUTED.
     */
    public synchronized DelegatedTaskExecutionResult executePending(
        String taskId,
        String executorName,
        DelegatedTaskExecutionPolicy policy
    ) {
        DelegatedTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown delegated task: " + taskId);
        DelegatedTaskExecutionPolicy effectivePolicy = policy != null
            ? policy
            : DelegatedTaskExecutionPolicy.safeDefault().withParentVerificationPolicy(task.verificationPolicy());
        DelegatedTaskExecutor executor = executorRegistry.find(executorName).orElse(null);
        if (executor == null) {
            return DelegatedTaskExecutionResult.notExecuted(
                DelegatedTaskExecutorRegistry.normalize(executorName),
                "UNSUPPORTED_EXECUTOR",
                "Unsupported delegated task executor: " + executorName,
                effectivePolicy
            );
        }
        DelegatedTaskExecutionResult execution = executor.execute(task, effectivePolicy);
        if (execution != null && execution.executed() && execution.delegatedTaskResult() != null) {
            ParentVerificationResult verification = task.submitResult(
                execution.delegatedTaskResult(),
                effectivePolicy.parentVerificationPolicy()
            );
            DelegatedTaskExecutionResult submitted = execution.withSubmission(verification);
            task.recordExecution(submitted);
            save();
            return submitted;
        }
        if (execution != null) {
            task.recordExecution(execution);
            save();
        }
        return execution;
    }

    public DelegatedTaskExecutorRegistry executorRegistry() {
        return executorRegistry;
    }

    public synchronized ParentVerificationResult verify(String taskId, ParentVerificationPolicy policy) {
        DelegatedTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown delegated task: " + taskId);
        ParentVerificationResult verification = task.verifyWithPolicy(policy);
        save();
        return verification;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tasks", list().stream().map(DelegatedTask::toMap).toList());
        m.put("count", tasks.size());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegatedTaskStore fromMap(Map<String, Object> m) {
        DelegatedTaskStore store = new DelegatedTaskStore();
        store.loadFromMap(m);
        return store;
    }

    @SuppressWarnings("unchecked")
    private void loadFromMap(Map<String, Object> m) {
        if (m == null) return;
        Object rawTasks = m.get("tasks");
        long maxId = 0;
        if (rawTasks instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> tm)) continue;
                try {
                    DelegatedTask task = DelegatedTask.fromMap((Map<String, Object>) tm);
                    if (task.taskId() != null && !task.taskId().isBlank() && !"null".equals(task.taskId())) {
                        tasks.put(task.taskId(), task);
                        maxId = Math.max(maxId, numericSuffix(task.taskId()));
                    }
                } catch (Exception ignored) {
                    // A single corrupt task should not block loading remaining tenant state.
                }
            }
        }
        ids.set(Math.max(ids.get(), maxId));
    }

    private void load() {
        if (storePath == null || !Files.exists(storePath)) return;
        try {
            Map<String, Object> map = MAPPER.readValue(storePath.toFile(), new TypeReference<Map<String, Object>>() {});
            loadFromMap(map);
        } catch (Exception ignored) {
            // Corrupt delegated-task state should not block tenant startup; later writes will repair it.
        }
    }

    private synchronized void save() {
        if (storePath == null) return;
        try {
            Files.createDirectories(storePath.getParent());
            MAPPER.writeValue(storePath.toFile(), toMap());
        } catch (IOException ignored) {
            // Best-effort persistence; callers still receive in-memory state and audit events.
        }
    }

    private static long numericSuffix(String id) {
        if (id == null) return 0;
        int idx = id.lastIndexOf('_');
        if (idx < 0 || idx == id.length() - 1) return 0;
        try { return Long.parseLong(id.substring(idx + 1)); }
        catch (Exception ignored) { return 0; }
    }
}
