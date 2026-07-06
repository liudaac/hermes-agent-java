package com.nousresearch.hermes.skills;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detailed report from a curator LLM consolidation pass.
 *
 * <p>Aligned with the original Python Hermes curator's run.json + REPORT.md.
 * Captures both the deterministic auto-transitions and the LLM consolidation
 * results, including which skills were consolidated into which umbrellas
 * and which were pruned for staleness.</p>
 */
public class CuratorRunReport {

    public Instant startedAt;
    public double durationSeconds;
    public String model;
    public String provider;

    // Layer 1: deterministic auto-transitions
    public int autoChecked;
    public int autoMarkedStale;
    public int autoArchived;
    public int autoReactivated;

    // Layer 2: LLM consolidation
    public int llmToolCalls;
    public int consolidatedCount;
    public int prunedCount;
    public int newSkillsCreated;

    // Details
    public List<ConsolidationEntry> consolidations = new ArrayList<>();
    public List<PruningEntry> prunings = new ArrayList<>();
    public List<String> newSkills = new ArrayList<>();

    // LLM raw output
    public String llmFinalResponse;
    public String llmError;

    // Cron job rewrites
    public int cronJobsRewritten;

    public static class ConsolidationEntry {
        public String from;
        public String into;
        public String reason;

        public ConsolidationEntry(String from, String into, String reason) {
            this.from = from;
            this.into = into;
            this.reason = reason != null ? reason : "";
        }
    }

    public static class PruningEntry {
        public String name;
        public String reason;

        public PruningEntry(String name, String reason) {
            this.name = name;
            this.reason = reason != null ? reason : "";
        }
    }

    /**
     * Render a human-readable markdown report.
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Curator Run — ").append(startedAt).append("\n\n");

        sb.append("## Auto-transitions (deterministic)\n");
        sb.append("- checked: ").append(autoChecked).append("\n");
        sb.append("- marked stale: ").append(autoMarkedStale).append("\n");
        sb.append("- archived: ").append(autoArchived).append("\n");
        sb.append("- reactivated: ").append(autoReactivated).append("\n\n");

        sb.append("## LLM Consolidation\n");
        sb.append("- tool calls: ").append(llmToolCalls).append("\n");
        sb.append("- consolidated into umbrellas: ").append(consolidatedCount).append("\n");
        sb.append("- pruned (archived for staleness): ").append(prunedCount).append("\n");
        sb.append("- new skills created: ").append(newSkillsCreated).append("\n\n");

        if (!consolidations.isEmpty()) {
            sb.append("### Consolidated\n\n");
            sb.append("_Content absorbed into umbrella skills. Originals archived under `.archive/`._\n\n");
            for (var c : consolidations) {
                sb.append("- `").append(c.from).append("` → merged into `").append(c.into).append("`");
                if (!c.reason.isBlank()) sb.append(" — ").append(c.reason);
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!prunings.isEmpty()) {
            sb.append("### Pruned\n\n");
            sb.append("_Archived without merge (stale/irrelevant). Recoverable via restore._\n\n");
            for (var p : prunings) {
                sb.append("- `").append(p.name).append("`");
                if (!p.reason.isBlank()) sb.append(" — ").append(p.reason);
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!newSkills.isEmpty()) {
            sb.append("### New umbrella skills\n\n");
            for (String name : newSkills) {
                sb.append("- `").append(name).append("`\n");
            }
            sb.append("\n");
        }

        if (cronJobsRewritten > 0) {
            sb.append("### Cron job skill references rewritten: ").append(cronJobsRewritten).append("\n\n");
        }

        sb.append("## Recovery\n");
        sb.append("- Restore: `hermes curator restore <name>`\n");
        sb.append("- Archives live under `~/.hermes/skills/.archive/`\n");

        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("CuratorRunReport{auto: checked=%d stale=%d archived=%d, " +
                "llm: consolidated=%d pruned=%d new=%d, cron_rewritten=%d}",
            autoChecked, autoMarkedStale, autoArchived,
            consolidatedCount, prunedCount, newSkillsCreated, cronJobsRewritten);
    }
}
