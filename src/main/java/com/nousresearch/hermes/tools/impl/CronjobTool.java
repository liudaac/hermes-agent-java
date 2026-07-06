package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cronjob tool for scheduled tasks.
 */
public class CronjobTool {
    private static final Logger logger = LoggerFactory.getLogger(CronjobTool.class);
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledJob> jobs;
    
    public CronjobTool() {
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.jobs = new ConcurrentHashMap<>();
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("cronjob_add")
            .toolset("cronjob")
            .schema(Map.of("description", "Add a scheduled job",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "schedule", Map.of("type", "string", "description", "Cron expression or '5m', '1h', 'daily'"),
                        "command", Map.of("type", "string"),
                        "timezone", Map.of("type", "string", "default", "UTC")),
                    "required", List.of("name", "schedule", "command"))))
            .risk(com.nousresearch.hermes.approval.ToolRisk.HIGH)
            .requiresApproval(true)
            .approvalType(com.nousresearch.hermes.approval.ApprovalSystem.ApprovalType.TERMINAL_COMMAND)
            .approvalMessageTemplate("Add cron job: {command}")
            .handler(this::addJob).emoji("⏰").build());
        
        registry.register(new ToolEntry.Builder()
            .name("cronjob_list")
            .toolset("cronjob")
            .schema(Map.of("description", "List scheduled jobs"))
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .requiresApproval(false)
            .handler(args -> listJobs()).emoji("📋").build());
        
        registry.register(new ToolEntry.Builder()
            .name("cronjob_remove")
            .toolset("cronjob")
            .schema(Map.of("description", "Remove a scheduled job",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("name", Map.of("type", "string")),
                    "required", List.of("name"))))
            .risk(com.nousresearch.hermes.approval.ToolRisk.HIGH)
            .requiresApproval(true)
            .approvalType(com.nousresearch.hermes.approval.ApprovalSystem.ApprovalType.TERMINAL_COMMAND)
            .approvalMessageTemplate("Remove cron job")
            .handler(this::removeJob).emoji("🗑️").build());
    }
    
    private String addJob(Map<String, Object> args) {
        String name = (String) args.get("name");
        String schedule = (String) args.get("schedule");
        String command = (String) args.get("command");
        
        if (jobs.containsKey(name)) {
            return ToolRegistry.toolError("Job already exists: " + name);
        }
        
        try {
            long delay = parseSchedule(schedule);
            
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeCommand(command),
                delay,
                delay,
                TimeUnit.SECONDS
            );
            
            jobs.put(name, new ScheduledJob(name, schedule, command, future));
            
            return ToolRegistry.toolResult(Map.of(
                "name", name,
                "schedule", schedule,
                "status", "scheduled"
            ));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to schedule: " + e.getMessage());
        }
    }
    
    private String listJobs() {
        return ToolRegistry.toolResult(Map.of("jobs", getJobSnapshots(), "count", jobs.size()));
    }

    /**
     * Return a snapshot of all cron jobs for external inspection (e.g. curator).
     */
    public List<Map<String, String>> getJobSnapshots() {
        List<Map<String, String>> jobList = new ArrayList<>();
        for (ScheduledJob job : jobs.values()) {
            jobList.add(Map.of(
                "name", job.name,
                "schedule", job.schedule,
                "command", job.command
            ));
        }
        return jobList;
    }

    /**
     * Update a cron job's command string (used by curator to rewrite skill references).
     */
    public boolean updateJobCommand(String jobName, String newCommand) {
        ScheduledJob job = jobs.get(jobName);
        if (job == null) return false;
        // Cancel old schedule and re-create with new command
        job.future().cancel(false);
        try {
            long delay = parseSchedule(job.schedule());
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeCommand(newCommand),
                delay, delay, TimeUnit.SECONDS
            );
            jobs.put(jobName, new ScheduledJob(jobName, job.schedule(), newCommand, future));
            logger.info("Updated cron job command: {} → {}", jobName, newCommand);
            return true;
        } catch (Exception e) {
            // Restore original on failure
            long delay = parseSchedule(job.schedule());
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeCommand(job.command()),
                delay, delay, TimeUnit.SECONDS
            );
            jobs.put(jobName, new ScheduledJob(jobName, job.schedule(), job.command(), future));
            logger.error("Failed to update cron job {}: {}", jobName, e.getMessage());
            return false;
        }
    }
    
    private String removeJob(Map<String, Object> args) {
        String name = (String) args.get("name");
        ScheduledJob job = jobs.remove(name);
        
        if (job == null) {
            return ToolRegistry.toolError("Job not found: " + name);
        }
        
        job.future.cancel(false);
        return ToolRegistry.toolResult(Map.of("removed", name));
    }
    
    private long parseSchedule(String schedule) {
        // Parse relative time: 5m, 1h, 30s
        Pattern pattern = Pattern.compile("^(\\d+)([smhd])$");
        Matcher matcher = pattern.matcher(schedule);
        
        if (matcher.matches()) {
            int value = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            
            return switch (unit) {
                case "s" -> value;
                case "m" -> value * 60;
                case "h" -> value * 3600;
                case "d" -> value * 86400;
                default -> 3600;
            };
        }
        
        // Default: 1 hour
        return 3600;
    }
    
    private void executeCommand(String command) {
        logger.info("Executing scheduled job: {}", command);
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            logger.error("Job execution failed: {}", e.getMessage());
        }
    }
    
    record ScheduledJob(String name, String schedule, String command, ScheduledFuture<?> future) {}
}
