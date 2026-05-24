package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Dashboard-local Cron API handler.
 *
 * This intentionally implements the Dashboard CRUD contract without pretending a
 * real runtime scheduler is wired yet.  Jobs are persisted so the UI is usable;
 * trigger records a clear unsupported execution result until a scheduler is
 * connected.
 */
public class CronHandler {
    private static final Logger logger = LoggerFactory.getLogger(CronHandler.class);
    private static final Pattern RELATIVE_PATTERN = Pattern.compile("^\\d+[smhd]$", Pattern.CASE_INSENSITIVE);

    private final Path storePath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final LinkedHashMap<String, CronJob> jobs = new LinkedHashMap<>();

    public CronHandler() {
        this(Path.of(System.getProperty("user.home"), ".hermes", "dashboard-cron-jobs.json"));
    }

    public CronHandler(Path storePath) {
        this.storePath = storePath.toAbsolutePath().normalize();
        loadJobs();
    }

    /** GET /api/cron/jobs */
    public void listJobs(Context ctx) {
        lock.readLock().lock();
        try {
            ctx.json(jobs.values().stream()
                .sorted(Comparator.comparing((CronJob job) -> job.createdAt).reversed())
                .map(CronHandler::toMap)
                .toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** POST /api/cron/jobs */
    public void createJob(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String prompt = body.getString("prompt");
            String scheduleExpr = body.getString("schedule");
            String name = blankToNull(body.getString("name"));
            String deliver = blankToNull(body.getString("deliver"));

            if (prompt == null || prompt.isBlank()) {
                ctx.status(400).json(Map.of("error", "prompt is required"));
                return;
            }
            if (scheduleExpr == null || scheduleExpr.isBlank()) {
                ctx.status(400).json(Map.of("error", "schedule is required"));
                return;
            }

            CronSchedule schedule = parseSchedule(scheduleExpr);
            CronJob job = new CronJob();
            job.id = UUID.randomUUID().toString();
            job.name = name;
            job.prompt = prompt;
            job.schedule = schedule;
            job.enabled = true;
            job.state = "scheduled";
            job.deliver = deliver != null ? deliver : "local";
            job.createdAt = Instant.now().toString();
            job.nextRunAt = null; // no runtime scheduler wired yet
            job.lastRunAt = null;
            job.lastError = null;

            lock.writeLock().lock();
            try {
                jobs.put(job.id, job);
                saveJobs();
            } finally {
                lock.writeLock().unlock();
            }

            ctx.status(201).json(toMap(job));
        } catch (Exception e) {
            logger.error("Error creating cron job: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/cron/jobs/{id}/pause */
    public void pauseJob(Context ctx) {
        updateJobState(ctx, false, "paused", null);
    }

    /** POST /api/cron/jobs/{id}/resume */
    public void resumeJob(Context ctx) {
        updateJobState(ctx, true, "scheduled", null);
    }

    /** POST /api/cron/jobs/{id}/trigger */
    public void triggerJob(Context ctx) {
        String id = ctx.pathParam("id");
        lock.writeLock().lock();
        try {
            CronJob job = jobs.get(id);
            if (job == null) {
                ctx.status(404).json(Map.of("error", "Cron job not found: " + id));
                return;
            }

            job.lastRunAt = Instant.now().toString();
            job.lastError = "Manual trigger recorded, but no cron execution engine is wired to the dashboard yet.";
            saveJobs();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", false);
            response.put("unsupported", true);
            response.put("id", id);
            response.put("message", job.lastError);
            response.put("job", toMap(job));
            ctx.status(501).json(response);
        } catch (Exception e) {
            logger.error("Error triggering cron job {}: {}", id, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** DELETE /api/cron/jobs/{id} */
    public void deleteJob(Context ctx) {
        String id = ctx.pathParam("id");
        lock.writeLock().lock();
        try {
            CronJob removed = jobs.remove(id);
            if (removed == null) {
                ctx.status(404).json(Map.of("error", "Cron job not found: " + id));
                return;
            }
            saveJobs();
            ctx.json(Map.of("ok", true, "id", id));
        } catch (Exception e) {
            logger.error("Error deleting cron job {}: {}", id, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void updateJobState(Context ctx, boolean enabled, String state, String error) {
        String id = ctx.pathParam("id");
        lock.writeLock().lock();
        try {
            CronJob job = jobs.get(id);
            if (job == null) {
                ctx.status(404).json(Map.of("error", "Cron job not found: " + id));
                return;
            }
            job.enabled = enabled;
            job.state = state;
            job.lastError = error;
            saveJobs();
            ctx.json(Map.of("ok", true, "id", id, "job", toMap(job)));
        } catch (Exception e) {
            logger.error("Error updating cron job {}: {}", id, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private CronSchedule parseSchedule(String expr) {
        String trimmed = expr.trim();
        String kind = RELATIVE_PATTERN.matcher(trimmed).matches() ? "relative" : "cron";
        return new CronSchedule(kind, trimmed, trimmed);
    }

    private void loadJobs() {
        lock.writeLock().lock();
        try {
            jobs.clear();
            if (!Files.exists(storePath)) {
                return;
            }
            String raw = Files.readString(storePath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return;
            }
            JSONArray array = JSON.parseArray(raw);
            for (Object item : array) {
                if (!(item instanceof JSONObject obj)) {
                    continue;
                }
                CronJob job = fromJson(obj);
                if (job.id != null && !job.id.isBlank()) {
                    jobs.put(job.id, job);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load dashboard cron jobs from {}: {}", storePath, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveJobs() throws IOException {
        Files.createDirectories(storePath.getParent());
        List<Map<String, Object>> serialized = jobs.values().stream().map(CronHandler::toMap).toList();
        Files.writeString(storePath, JSON.toJSONString(serialized), StandardCharsets.UTF_8);
    }

    private static CronJob fromJson(JSONObject obj) {
        CronJob job = new CronJob();
        job.id = obj.getString("id");
        job.name = blankToNull(obj.getString("name"));
        job.prompt = obj.getString("prompt");
        JSONObject scheduleObj = obj.getJSONObject("schedule");
        if (scheduleObj != null) {
            job.schedule = new CronSchedule(
                firstNonBlank(scheduleObj.getString("kind"), "cron"),
                firstNonBlank(scheduleObj.getString("expr"), ""),
                firstNonBlank(scheduleObj.getString("display"), scheduleObj.getString("expr"), "")
            );
        } else {
            String display = firstNonBlank(obj.getString("schedule_display"), "");
            job.schedule = new CronSchedule("cron", display, display);
        }
        job.enabled = obj.getBooleanValue("enabled");
        job.state = firstNonBlank(obj.getString("state"), job.enabled ? "scheduled" : "paused");
        job.deliver = blankToNull(obj.getString("deliver"));
        job.lastRunAt = blankToNull(obj.getString("last_run_at"));
        job.nextRunAt = blankToNull(obj.getString("next_run_at"));
        job.lastError = blankToNull(obj.getString("last_error"));
        job.createdAt = firstNonBlank(obj.getString("created_at"), Instant.now().toString());
        return job;
    }

    private static Map<String, Object> toMap(CronJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.id);
        map.put("name", job.name);
        map.put("prompt", job.prompt);
        map.put("schedule", Map.of(
            "kind", job.schedule.kind,
            "expr", job.schedule.expr,
            "display", job.schedule.display
        ));
        map.put("schedule_display", job.schedule.display);
        map.put("enabled", job.enabled);
        map.put("state", job.state);
        map.put("deliver", job.deliver);
        map.put("last_run_at", job.lastRunAt);
        map.put("next_run_at", job.nextRunAt);
        map.put("last_error", job.lastError);
        map.put("created_at", job.createdAt);
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static class CronJob {
        String id;
        String name;
        String prompt;
        CronSchedule schedule;
        boolean enabled;
        String state;
        String deliver;
        String lastRunAt;
        String nextRunAt;
        String lastError;
        String createdAt;
    }

    record CronSchedule(String kind, String expr, String display) {}
}
