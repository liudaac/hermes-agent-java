package com.nousresearch.hermes.dashboard.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight scheduler that drives dashboard cron jobs.
 *
 * <p>Supports two schedule kinds:
 * <ul>
 *   <li>relative: {@code "<number><s|m|h|d>"} -> reschedule with that fixed delay.</li>
 *   <li>cron: a standard 5-field cron expression with {@code "* / step"} and ranges.
 *       Hour/day-of-week scheduling is supported well enough for the dashboard's
 *       "every day at HH:MM" / "every weekday" use cases.</li>
 * </ul>
 *
 * <p>Triggers go through the supplied executor, which records the run on the
 * persisted CronJob. The scheduler intentionally does not interpret the prompt;
 * actual delivery is handed off to the runner so the engine stays decoupled.
 */
public class CronJobExecutor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(CronJobExecutor.class);
    private static final Pattern RELATIVE_PATTERN = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final JobRunner runner;
    private final java.util.function.Function<String, Optional<CronHandler.CronJob>> jobLookup;

    public CronJobExecutor(JobRunner runner,
                           java.util.function.Function<String, Optional<CronHandler.CronJob>> jobLookup) {
        this(runner, jobLookup, Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "dashboard-cron-scheduler");
            thread.setDaemon(true);
            return thread;
        }));
    }

    CronJobExecutor(JobRunner runner,
                    java.util.function.Function<String, Optional<CronHandler.CronJob>> jobLookup,
                    ScheduledExecutorService scheduler) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.jobLookup = Objects.requireNonNull(jobLookup, "jobLookup");
        this.scheduler = scheduler;
    }

    /** Schedule a job, replacing any previously scheduled version. */
    public void schedule(CronHandler.CronJob job) {
        cancel(job.id);
        if (!job.enabled) {
            return;
        }
        long delaySeconds = nextDelaySeconds(job.schedule);
        if (delaySeconds <= 0) {
            logger.warn("Skipping cron job {} ({}): unable to compute next run", job.id, job.schedule.expr());
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> runAndReschedule(job.id), delaySeconds, TimeUnit.SECONDS);
        scheduled.put(job.id, future);
        job.nextRunAt = Instant.now().plusSeconds(delaySeconds).toString();
    }

    /** Cancel a job. */
    public void cancel(String id) {
        ScheduledFuture<?> future = scheduled.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Trigger a job immediately. Returns the run result, which the caller can persist.
     */
    public RunResult runNow(CronHandler.CronJob job) {
        RunResult result = invokeRunner(job);
        if (job.enabled) {
            schedule(job);
        }
        return result;
    }

    public boolean isScheduled(String id) {
        return scheduled.containsKey(id);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        scheduled.clear();
    }

    private void runAndReschedule(String id) {
        Optional<CronHandler.CronJob> snapshot = jobLookup.apply(id);
        if (snapshot.isEmpty()) {
            scheduled.remove(id);
            return;
        }
        CronHandler.CronJob job = snapshot.get();
        invokeRunner(job);
        if (job.enabled) {
            schedule(job);
        } else {
            scheduled.remove(id);
        }
    }

    private RunResult invokeRunner(CronHandler.CronJob job) {
        long startedAt = System.currentTimeMillis();
        try {
            String output = runner.run(job);
            long durationMs = System.currentTimeMillis() - startedAt;
            return new RunResult(true, output, null, durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            logger.warn("Cron job {} failed: {}", job.id, e.getMessage());
            return new RunResult(false, null, e.getMessage(), durationMs);
        }
    }

    /** Compute the next delay in seconds for a schedule expression. */
    public static long nextDelaySeconds(CronHandler.CronSchedule schedule) {
        if (schedule == null || schedule.expr() == null) {
            return -1;
        }
        String expr = schedule.expr().trim();
        if ("relative".equalsIgnoreCase(schedule.kind())) {
            return relativeDelaySeconds(expr);
        }
        return cronDelaySeconds(expr);
    }

    static long relativeDelaySeconds(String expr) {
        Matcher matcher = RELATIVE_PATTERN.matcher(expr);
        if (!matcher.matches()) {
            return -1;
        }
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase()) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> -1;
        };
    }

    /**
     * Very small standard 5-field cron parser: minute hour dayOfMonth month dayOfWeek.
     * Supports '*', step "*" + step suffix, single integers, comma lists, and ranges 'a-b'.
     */
    static long cronDelaySeconds(String expr) {
        String[] parts = expr.split("\\s+");
        if (parts.length != 5) {
            return -1;
        }
        try {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now()
                .withSecond(0).withNano(0).plusMinutes(1);
            for (int i = 0; i < 60 * 24 * 366; i++) {
                if (matches(now, parts)) {
                    long delta = java.time.Duration.between(java.time.ZonedDateTime.now(), now).getSeconds();
                    return Math.max(delta, 1);
                }
                now = now.plusMinutes(1);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse cron expression '{}': {}", expr, e.getMessage());
        }
        return -1;
    }

    private static boolean matches(java.time.ZonedDateTime t, String[] parts) {
        return matchesField(parts[0], t.getMinute(), 0, 59)
            && matchesField(parts[1], t.getHour(), 0, 23)
            && matchesField(parts[2], t.getDayOfMonth(), 1, 31)
            && matchesField(parts[3], t.getMonthValue(), 1, 12)
            && matchesField(parts[4], t.getDayOfWeek().getValue() % 7, 0, 6);
    }

    private static boolean matchesField(String field, int value, int min, int max) {
        for (String token : field.split(",")) {
            if (matchesToken(token.trim(), value, min, max)) {
                return true;
            }
        }
        return false;
    }

    /** Package-visible wrapper so CronHandler can reuse this matcher for schedule preview. */
    static boolean matchesFieldPublic(String field, int value, int min, int max) {
        return matchesField(field, value, min, max);
    }

    private static boolean matchesToken(String token, int value, int min, int max) {
        int step = 1;
        if (token.contains("/")) {
            String[] parts = token.split("/");
            token = parts[0];
            try {
                step = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (step <= 0) return false;
        }
        int lo;
        int hi;
        if (token.equals("*") || token.isEmpty()) {
            lo = min;
            hi = max;
        } else if (token.contains("-")) {
            String[] range = token.split("-");
            try {
                lo = Integer.parseInt(range[0]);
                hi = Integer.parseInt(range[1]);
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            try {
                lo = Integer.parseInt(token);
                hi = lo;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (value < lo || value > hi) {
            return false;
        }
        return ((value - lo) % step) == 0;
    }

    /** Pluggable execution backend for triggered cron jobs. */
    public interface JobRunner {
        String run(CronHandler.CronJob job) throws Exception;
    }

    /** Result of a single cron run, used by the handler to update persisted state. */
    public record RunResult(boolean ok, String output, String error, long durationMs) {}
}
