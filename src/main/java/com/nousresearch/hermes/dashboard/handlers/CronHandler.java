package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private final CronJobExecutor executor;
    // Per-job rolling run history (in-memory, last N).
    private static final int MAX_RUN_HISTORY = 20;
    private final ConcurrentHashMap<String, Deque<RunRecord>> runHistory = new ConcurrentHashMap<>();
    // SSE subscribers per job id, used to push live trigger output to the UI.
    private final ConcurrentHashMap<String, Set<SseClient>> runSubscribers = new ConcurrentHashMap<>();

    public CronHandler() {
        this(Path.of(System.getProperty("user.home"), ".hermes", "dashboard-cron-jobs.json"));
    }

    public CronHandler(Path storePath) {
        this(storePath, true);
    }

    public CronHandler(Path storePath, boolean withExecutor) {
        this(storePath, withExecutor, null);
    }

    public CronHandler(Path storePath, boolean withExecutor, CronJobExecutor.JobRunner runner) {
        this.storePath = storePath.toAbsolutePath().normalize();
        loadJobs();
        if (withExecutor) {
            CronJobExecutor.JobRunner effectiveRunner = runner != null ? runner : this::defaultRunner;
            this.executor = new CronJobExecutor(
                effectiveRunner,
                id -> {
                    lock.readLock().lock();
                    try {
                        return Optional.ofNullable(jobs.get(id));
                    } finally {
                        lock.readLock().unlock();
                    }
                });
            for (CronJob job : jobs.values()) {
                if (job.enabled) {
                    executor.schedule(job);
                }
            }
        } else {
            this.executor = null;
        }
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
            String workspaceId = blankToNull(body.getString("workspaceId"));

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
            job.workspaceId = workspaceId;
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

            if (executor != null && job.enabled) {
                executor.schedule(job);
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

            if (executor != null) {
                CronJobExecutor.RunResult result = executor.runNow(job);
                job.lastRunAt = Instant.now().toString();
                job.lastError = result.ok() ? null : result.error();
                saveJobs();

                recordRun(id, result);
                broadcastRun(id, result);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ok", result.ok());
                response.put("id", id);
                response.put("duration_ms", result.durationMs());
                response.put("output", result.output());
                response.put("error", result.error());
                response.put("job", toMap(job));
                ctx.status(result.ok() ? 200 : 500).json(response);
            } else {
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
            }
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
            if (executor != null) {
                executor.cancel(id);
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
            if (executor != null) {
                if (enabled) {
                    executor.schedule(job);
                } else {
                    executor.cancel(id);
                    job.nextRunAt = null;
                }
            }
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
        job.workspaceId = blankToNull(obj.getString("workspaceId"));
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
        map.put("workspaceId", job.workspaceId);
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

    /** Default runner: dashboard records the manual run but Agent execution is handed off elsewhere. */
    String defaultRunner(CronJob job) {
        logger.info("[cron] dashboard recorded run for job {} ({}): {}", job.id, job.schedule.expr(), job.prompt);
        return "Recorded by dashboard cron scheduler. Connect an agent runner for real execution.";
    }

    /* ---------------------------------------------------------------- */
    /* Schedule preview + run history + SSE                              */
    /* ---------------------------------------------------------------- */

    /** GET /api/cron/preview?schedule=<expr>&count=5 */
    public void previewSchedule(Context ctx) {
        String expr = ctx.queryParam("schedule");
        String countParam = ctx.queryParam("count");
        int count = countParam != null ? Math.min(20, Math.max(1, parseInt(countParam, 5))) : 5;
        if (expr == null || expr.isBlank()) {
            ctx.status(400).json(Map.of("error", "schedule parameter is required"));
            return;
        }
        CronSchedule schedule = parseSchedule(expr);
        List<String> upcoming = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.now(ZoneId.systemDefault());
        for (int i = 0; i < count; i++) {
            if ("relative".equalsIgnoreCase(schedule.kind())) {
                long delay = CronJobExecutor.relativeDelaySeconds(schedule.expr());
                if (delay <= 0) break;
                cursor = cursor.plusSeconds(delay);
            } else {
                ZonedDateTime match = nextCronMatch(cursor, schedule.expr());
                if (match == null) break;
                cursor = match;
            }
            upcoming.add(cursor.toInstant().toString());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schedule", Map.of(
            "kind", schedule.kind(),
            "expr", schedule.expr(),
            "display", describeSchedule(schedule)
        ));
        response.put("valid", !upcoming.isEmpty());
        response.put("upcoming", upcoming);
        response.put("timezone", ZoneId.systemDefault().getId());
        ctx.json(response);
    }

    private static ZonedDateTime nextCronMatch(ZonedDateTime from, String expr) {
        ZonedDateTime probe = from.withSecond(0).withNano(0).plusMinutes(1);
        long maxIter = 60L * 24L * 366L;
        for (long i = 0; i < maxIter; i++) {
            if (cronMatches(probe, expr)) {
                return probe;
            }
            probe = probe.plusMinutes(1);
        }
        return null;
    }

    private static boolean cronMatches(ZonedDateTime t, String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) return false;
        return CronJobExecutor.matchesFieldPublic(parts[0], t.getMinute(), 0, 59)
            && CronJobExecutor.matchesFieldPublic(parts[1], t.getHour(), 0, 23)
            && CronJobExecutor.matchesFieldPublic(parts[2], t.getDayOfMonth(), 1, 31)
            && CronJobExecutor.matchesFieldPublic(parts[3], t.getMonthValue(), 1, 12)
            && CronJobExecutor.matchesFieldPublic(parts[4], t.getDayOfWeek().getValue() % 7, 0, 6);
    }

    static String describeSchedule(CronSchedule schedule) {
        if ("relative".equalsIgnoreCase(schedule.kind())) {
            String expr = schedule.expr();
            char unit = expr.charAt(expr.length() - 1);
            String num = expr.substring(0, expr.length() - 1);
            return "every " + num + switch (Character.toLowerCase(unit)) {
                case 's' -> " seconds";
                case 'm' -> " minutes";
                case 'h' -> " hours";
                case 'd' -> " days";
                default -> "";
            };
        }
        String[] p = schedule.expr().trim().split("\\s+");
        if (p.length != 5) return schedule.expr();
        String mn = p[0], hr = p[1], dom = p[2], mo = p[3], dow = p[4];
        if (isStar(dom) && isStar(mo)) {
            if (isStar(dow)) {
                if (isStar(mn) && isStar(hr)) return "every minute";
                if (isStar(hr)) return "at minute " + mn + " every hour";
                if (mn.matches("\\d+") && hr.matches("\\d+"))
                    return String.format("daily at %02d:%02d",
                        Integer.parseInt(hr), Integer.parseInt(mn));
            } else if (mn.matches("\\d+") && hr.matches("\\d+")) {
                return String.format("at %02d:%02d on dow %s",
                    Integer.parseInt(hr), Integer.parseInt(mn), dow);
            }
        }
        return schedule.expr();
    }

    private static boolean isStar(String s) {
        return "*".equals(s.trim());
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }

    /** GET /api/cron/jobs/{id}/runs */
    public void getJobRuns(Context ctx) {
        String id = ctx.pathParam("id");
        Deque<RunRecord> history = runHistory.get(id);
        if (history == null) {
            ctx.json(Map.of("id", id, "runs", List.of()));
            return;
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        synchronized (history) {
            for (RunRecord r : history) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", r.at);
                m.put("ok", r.ok);
                m.put("duration_ms", r.durationMs);
                m.put("output", truncate(r.output, 8 * 1024));
                m.put("error", r.error);
                serialized.add(m);
            }
        }
        ctx.json(Map.of("id", id, "runs", serialized));
    }

    /** SSE GET /api/cron/jobs/{id}/runs/stream */
    public void streamJobRuns(SseClient client) {
        String id = client.ctx().pathParam("id");
        client.keepAlive();
        Set<SseClient> set = runSubscribers.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        set.add(client);
        client.onClose(() -> {
            Set<SseClient> s = runSubscribers.get(id);
            if (s != null) s.remove(client);
        });
        Deque<RunRecord> history = runHistory.get(id);
        if (history != null && !history.isEmpty()) {
            RunRecord latest;
            synchronized (history) { latest = history.peekLast(); }
            client.sendEvent("run", recordToJson(id, latest));
        } else {
            client.sendEvent("ready", "{\"id\":\"" + escapeJson(id) + "\"}");
        }
    }

    private void recordRun(String id, CronJobExecutor.RunResult result) {
        RunRecord record = new RunRecord(
            Instant.now().toString(),
            result.ok(),
            result.durationMs(),
            result.output(),
            result.error()
        );
        Deque<RunRecord> history = runHistory.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(record);
            while (history.size() > MAX_RUN_HISTORY) history.pollFirst();
        }
    }

    private void broadcastRun(String id, CronJobExecutor.RunResult result) {
        Set<SseClient> set = runSubscribers.get(id);
        if (set == null || set.isEmpty()) return;
        RunRecord rec = new RunRecord(
            Instant.now().toString(),
            result.ok(),
            result.durationMs(),
            result.output(),
            result.error()
        );
        String payload = recordToJson(id, rec);
        for (SseClient client : new HashSet<>(set)) {
            try {
                client.sendEvent("run", payload);
            } catch (Exception e) {
                set.remove(client);
            }
        }
    }

    private static String recordToJson(String id, RunRecord r) {
        return "{\"id\":\"" + escapeJson(id) + "\","
            + "\"at\":\"" + escapeJson(r.at) + "\","
            + "\"ok\":" + r.ok + ","
            + "\"duration_ms\":" + r.durationMs + ","
            + "\"output\":\"" + escapeJson(truncate(r.output, 8 * 1024)) + "\","
            + "\"error\":\"" + escapeJson(r.error) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "\n…[truncated]" : s;
    }

    int runSubscriberCount(String id) {
        Set<SseClient> s = runSubscribers.get(id);
        return s == null ? 0 : s.size();
    }

    static class RunRecord {
        final String at;
        final boolean ok;
        final long durationMs;
        final String output;
        final String error;

        RunRecord(String at, boolean ok, long durationMs, String output, String error) {
            this.at = at;
            this.ok = ok;
            this.durationMs = durationMs;
            this.output = output;
            this.error = error;
        }
    }

    static class CronJob {
        String id;
        String name;
        String prompt;
        CronSchedule schedule;
        boolean enabled;
        String state;
        String deliver;
        String workspaceId;
        String lastRunAt;
        String nextRunAt;
        String lastError;
        String createdAt;
    }

    record CronSchedule(String kind, String expr, String display) {}
}
