package com.nousresearch.hermes.skills;

import com.nousresearch.hermes.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the /curator slash command.
 *
 * <p>Aligned with the original Python Hermes {@code hermes curator} CLI.</p>
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>/curator run — execute a curator pass (deterministic only by default)</li>
 *   <li>/curator run --consolidate — include LLM umbrella-building</li>
 *   <li>/curator run --dry-run — preview only, no mutations</li>
 *   <li>/curator status — show last run info</li>
 *   <li>/curator pause / resume — control the scheduler</li>
 *   <li>/curator restore &lt;name&gt; — restore an archived skill</li>
 * </ul>
 */
public class CuratorCommandRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CuratorCommandRegistrar.class);

    private static CuratorJob curatorJob;

    public static void register(PluginManager pluginManager, CuratorJob job) {
        curatorJob = job;
        pluginManager.trackSlashCommand(
            "curator",
            CuratorCommandRegistrar::handleCurator,
            "Manage the background skill curator (run, status, pause, restore)",
            "<run|status|pause|resume|restore> [--consolidate] [--dry-run]",
            "hermes-core"
        );
        logger.info("Registered /curator slash command");
    }

    private static Object handleCurator(String input) {
        String args = extractArgs(input);
        String[] parts = args.split("\\s+");
        String subcommand = parts.length > 0 ? parts[0].toLowerCase() : "status";

        if (curatorJob == null) {
            return errorResult("Curator not initialized");
        }

        switch (subcommand) {
            case "run" -> {
                boolean consolidate = args.contains("--consolidate");
                boolean dryRun = args.contains("--dry-run");
                CuratorRunReport report = curatorJob.run(consolidate, dryRun);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "message");
                result.put("message", formatRunResult(report, dryRun));
                return result;
            }
            case "status" -> {
                CuratorJob.CuratorState state = curatorJob.getState();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "message");
                result.put("message", String.format(
                    "Curator status:\n  last run: %s\n  run count: %d\n  paused: %s\n  last summary: %s\n  report: %s",
                    state.lastRunAt != null ? state.lastRunAt : "never",
                    state.runCount,
                    state.paused,
                    state.lastRunSummary != null ? state.lastRunSummary : "n/a",
                    state.lastReportPath != null ? state.lastReportPath : "n/a"
                ));
                return result;
            }
            case "pause" -> {
                curatorJob.setPaused(true);
                return messageResult("Curator paused. Use /curator resume to re-enable.");
            }
            case "resume" -> {
                curatorJob.setPaused(false);
                return messageResult("Curator resumed.");
            }
            case "restore" -> {
                if (parts.length < 2) {
                    return errorResult("Usage: /curator restore <skill-name>");
                }
                boolean ok = curatorJob.restore(parts[1]);
                return ok
                    ? messageResult("Skill restored: " + parts[1])
                    : errorResult("Could not restore skill: " + parts[1] + " (not found or not archived)");
            }
            default -> {
                return errorResult("Unknown subcommand: " + subcommand + ". Use: run, status, pause, resume, restore");
            }
        }
    }

    private static String formatRunResult(CuratorRunReport report, boolean dryRun) {
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "🔍 Curator DRY-RUN preview\n\n" : "✅ Curator run completed\n\n");
        sb.append("Auto-transitions:\n");
        sb.append("  checked: ").append(report.autoChecked).append("\n");
        sb.append("  marked stale: ").append(report.autoMarkedStale).append("\n");
        sb.append("  archived: ").append(report.autoArchived).append("\n");
        sb.append("  reactivated: ").append(report.autoReactivated).append("\n");

        if (report.llmFinalResponse != null || report.llmError != null) {
            sb.append("\nLLM consolidation:\n");
            sb.append("  consolidated: ").append(report.consolidatedCount).append("\n");
            sb.append("  pruned: ").append(report.prunedCount).append("\n");
            sb.append("  new skills: ").append(report.newSkillsCreated).append("\n");
            if (report.llmError != null) {
                sb.append("  error: ").append(report.llmError).append("\n");
            }
            if (report.cronJobsRewritten > 0) {
                sb.append("  cron jobs rewritten: ").append(report.cronJobsRewritten).append("\n");
            }
        }

        if (!report.consolidations.isEmpty()) {
            sb.append("\nConsolidated:\n");
            for (var c : report.consolidations) {
                sb.append("  • ").append(c.from).append(" → ").append(c.into);
                if (!c.reason.isBlank()) sb.append(" — ").append(c.reason);
                sb.append("\n");
            }
        }
        if (!report.prunings.isEmpty()) {
            sb.append("\nPruned:\n");
            for (var p : report.prunings) {
                sb.append("  • ").append(p.name);
                if (!p.reason.isBlank()) sb.append(" — ").append(p.reason);
                sb.append("\n");
            }
        }

        sb.append("\nDuration: ").append(String.format("%.1fs", report.durationSeconds));
        return sb.toString();
    }

    private static String extractArgs(String input) {
        if (input == null || input.isBlank()) return "status";
        String trimmed = input.strip();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return "status";
        return trimmed.substring(spaceIdx + 1).strip();
    }

    private static Map<String, Object> messageResult(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "message");
        result.put("message", msg);
        return result;
    }

    private static Map<String, Object> errorResult(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "error");
        result.put("message", msg);
        return result;
    }
}
