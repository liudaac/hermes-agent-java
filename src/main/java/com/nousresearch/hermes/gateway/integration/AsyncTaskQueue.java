package com.nousresearch.hermes.gateway.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * D3: Async task queue with built-in thread pool.
 *
 * <p>Business systems submit tasks via {@code POST /api/v1/tasks}.
 * Tasks are persisted to MySQL, then consumed by worker threads
 * that invoke the Agent and store results.</p>
 *
 * <p>State machine: PENDING -> RUNNING -> COMPLETED / FAILED / CANCELLED</p>
 *
 * <p>Uses a fixed thread pool (configurable, default 4 workers).
 * Workers poll the DB for PENDING tasks ordered by priority + created_at.</p>
 */
public class AsyncTaskQueue {

    private static final Logger logger = LoggerFactory.getLogger(AsyncTaskQueue.class);

    private final DataSource dataSource;
    private final ExecutorService executor;
    private final ScheduledExecutorService poller;
    private final TaskProcessor processor;
    private volatile boolean running = false;

    public AsyncTaskQueue(DataSource dataSource, TaskProcessor processor) {
        this(dataSource, processor, 4);  // default 4 workers
    }

    public AsyncTaskQueue(DataSource dataSource, TaskProcessor processor, int workerCount) {
        this.dataSource = dataSource;
        this.processor = processor;
        this.executor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "hermes-task-worker");
            t.setDaemon(true);
            return t;
        });
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hermes-task-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit a new async task.
     */
    public AsyncTask submit(String tenantId, String systemId, String workspaceId,
                            String agentId, String input, int priority, int timeoutSeconds) {
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "");

        String sql = """
            INSERT INTO async_task (task_id, tenant_id, system_id, workspace_id, agent_id, input, status, priority, timeout_seconds)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, tenantId);
            ps.setString(3, systemId);
            ps.setString(4, workspaceId);
            ps.setString(5, agentId);
            ps.setString(6, input);
            ps.setInt(7, priority);
            ps.setInt(8, timeoutSeconds > 0 ? timeoutSeconds : 300);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to submit task: {}", e.getMessage());
            throw new RuntimeException("Failed to submit task", e);
        }

        logger.info("Task submitted: {} (tenant={}, system={})", taskId, tenantId, systemId);
        return get(taskId);
    }

    /**
     * Get task status by task ID.
     */
    public AsyncTask get(String taskId) {
        String sql = "SELECT * FROM async_task WHERE task_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to get task {}: {}", taskId, e.getMessage());
        }
        return null;
    }

    /**
     * Cancel a task (only if PENDING or RUNNING).
     */
    public boolean cancel(String taskId) {
        String sql = "UPDATE async_task SET status = 'CANCELLED', completed_at = NOW() WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to cancel task {}: {}", taskId, e.getMessage());
        }
        return false;
    }

    /**
     * List tasks for a tenant with optional status filter.
     */
    public List<AsyncTask> list(String tenantId, String statusFilter, int limit) {
        String sql = statusFilter != null
            ? "SELECT * FROM async_task WHERE tenant_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?"
            : "SELECT * FROM async_task WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            if (statusFilter != null) ps.setString(2, statusFilter);
            ps.setInt(statusFilter != null ? 3 : 2, Math.min(limit, 100));
            List<AsyncTask> tasks = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tasks.add(mapRow(rs));
            }
            return tasks;
        } catch (SQLException e) {
            logger.error("Failed to list tasks for tenant {}: {}", tenantId, e.getMessage());
        }
        return List.of();
    }

    /**
     * Start the worker poller.
     */
    public void start() {
        running = true;
        poller.scheduleAtFixedRate(this::pollAndDispatch, 0, 2, TimeUnit.SECONDS);
        logger.info("AsyncTaskQueue started (2s poll interval)");
    }

    /**
     * Stop the worker.
     */
    public void stop() {
        running = false;
        poller.shutdown();
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("AsyncTaskQueue stopped");
    }

    // ============ Internal ============

    private void pollAndDispatch() {
        if (!running) return;
        try {
            // Claim one PENDING task (oldest first, highest priority)
            AsyncTask task = claimNextTask();
            if (task != null) {
                executor.submit(() -> processTask(task));
            }
        } catch (Exception e) {
            logger.debug("Poll error: {}", e.getMessage());
        }
    }

    private AsyncTask claimNextTask() {
        // Simple claim: SELECT + UPDATE to RUNNING in a transaction
        String selectSql = """
            SELECT * FROM async_task
            WHERE status = 'PENDING'
            ORDER BY priority DESC, created_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;
        String updateSql = "UPDATE async_task SET status = 'RUNNING', started_at = NOW() WHERE task_id = ? AND status = 'PENDING'";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement selectPs = conn.prepareStatement(selectSql);
                 ResultSet rs = selectPs.executeQuery()) {
                if (rs.next()) {
                    AsyncTask task = mapRow(rs);
                    try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                        updatePs.setString(1, task.taskId());
                        updatePs.executeUpdate();
                    }
                    conn.commit();
                    return task;
                }
            }
            conn.rollback();
        } catch (SQLException e) {
            logger.debug("Claim task error: {}", e.getMessage());
        }
        return null;
    }

    private void processTask(AsyncTask task) {
        logger.info("Processing task: {} (agent={})", task.taskId(), task.agentId());
        try {
            String result = processor.process(task);
            updateResult(task.taskId(), "COMPLETED", result, null);
            logger.info("Task completed: {}", task.taskId());
        } catch (Exception e) {
            updateResult(task.taskId(), "FAILED", null, e.getMessage());
            logger.error("Task failed: {} - {}", task.taskId(), e.getMessage(), e);
        }
    }

    private void updateResult(String taskId, String status, String result, String error) {
        String sql = "UPDATE async_task SET status = ?, result = ?, error = ?, completed_at = NOW() WHERE task_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, result);
            ps.setString(3, error);
            ps.setString(4, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update task result {}: {}", taskId, e.getMessage());
        }
    }

    private AsyncTask mapRow(ResultSet rs) throws SQLException {
        return new AsyncTask(
            rs.getString("task_id"),
            rs.getString("tenant_id"),
            rs.getString("system_id"),
            rs.getString("workspace_id"),
            rs.getString("agent_id"),
            rs.getString("session_id"),
            rs.getString("input"),
            rs.getString("status"),
            rs.getString("result"),
            rs.getString("error"),
            rs.getInt("priority"),
            rs.getInt("timeout_seconds"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
            rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
            rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
        );
    }

    /**
     * Interface for processing a task (implemented by caller).
     */
    @FunctionalInterface
    public interface TaskProcessor {
        String process(AsyncTask task) throws Exception;
    }
}
