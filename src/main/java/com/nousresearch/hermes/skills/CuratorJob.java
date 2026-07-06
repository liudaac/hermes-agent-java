package com.nousresearch.hermes.skills;

import com.nousresearch.hermes.agent.SubAgent;
import com.nousresearch.hermes.agent.SubAgentResult;
import com.nousresearch.hermes.config.HermesConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * S5-2: Curator Job — 后台维护 agent 自建 skill 的生命周期。
 *
 * <p>对齐原版 agent/curator.py，包含两层：</p>
 * <ul>
 *   <li>Layer 1: 确定性状态转换 (active → stale → archived)，无 LLM</li>
 *   <li>Layer 2: LLM 伞形合并 — fork SubAgent 做合并/归档/报告</li>
 * </ul>
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>只碰 provenance = AGENT 的 skill</li>
 *   <li>生命周期：active → stale (30d) → archived (90d)</li>
 *   <li>永不删除，只归档，可恢复</li>
 *   <li>Pinned skill 跳过所有转换</li>
 *   <li>Cron 引用的 skill 不归档</li>
 * </ul>
 */
public class CuratorJob {
    private static final Logger logger = LoggerFactory.getLogger(CuratorJob.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    /** 超过 30 天未使用 → STALE */
    public static final long STALE_THRESHOLD_DAYS = 30;
    /** 超过 90 天未使用 → ARCHIVED */
    public static final long ARCHIVE_THRESHOLD_DAYS = 90;

    private final SkillProvenanceService provenanceService;
    private final SkillManager skillManager;
    private final HermesConfig config;

    // State persistence
    private final Path stateFile;
    private CuratorState state;

    // Report output
    private final Path reportsDir;

    public CuratorJob(SkillProvenanceService provenanceService, SkillManager skillManager) {
        this(provenanceService, skillManager, null);
    }

    public CuratorJob(SkillProvenanceService provenanceService, SkillManager skillManager,
                      HermesConfig config) {
        this.provenanceService = provenanceService;
        this.skillManager = skillManager;
        this.config = config;
        this.stateFile = skillManager.getSearchPaths().get(0).resolve(".curator_state.json");
        this.reportsDir = skillManager.getSearchPaths().get(0).resolve("..").resolve("logs").resolve("curator").normalize();
        this.state = loadState();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Execute a full curator pass: deterministic transitions + optional LLM consolidation.
     *
     * @param consolidate true to run the LLM umbrella-building pass
     * @param dryRun      true for preview-only (no mutations)
     * @return detailed run report
     */
    public CuratorRunReport run(boolean consolidate, boolean dryRun) {
        Instant start = Instant.now();
        CuratorRunReport report = new CuratorRunReport();
        report.startedAt = start;

        logger.info("Curator job started (consolidate={}, dryRun={})", consolidate, dryRun);

        // ── Layer 1: Deterministic state transitions ──
        List<SkillManager.Skill> agentSkills = provenanceService.getByProvenance(SkillProvenance.AGENT);
        report.autoChecked = agentSkills.size();

        if (!dryRun) {
            applyAutomaticTransitions(agentSkills, report);
        }

        // ── Layer 2: LLM consolidation (opt-in) ──
        if (consolidate && !agentSkills.isEmpty()) {
            runLLMConsolidation(agentSkills, dryRun, report);
        } else {
            logger.info("LLM consolidation skipped (consolidate={}, candidates={})",
                consolidate, agentSkills.size());
        }

        report.durationSeconds = java.time.Duration.between(start, Instant.now()).toMillis() / 1000.0;

        // ── Persist state ──
        if (!dryRun) {
            state.lastRunAt = start.toString();
            state.runCount++;
            state.lastRunSummary = report.toString();
            saveState();
        }

        // ── Write report ──
        writeReport(report);

        logger.info("Curator job completed: {}", report);
        return report;
    }

    /** Convenience: run with defaults (consolidate=off, dryRun=false). */
    public CuratorRunReport run() {
        return run(false, false);
    }

    /**
     * Restore an archived skill.
     */
    public boolean restore(String skillName) {
        boolean restored = skillManager.restoreSkill(skillName);
        if (restored) {
            logger.info("Manually restored skill: '{}'", skillName);
        }
        return restored;
    }

    public boolean isPaused() { return state.paused; }
    public void setPaused(boolean paused) {
        state.paused = paused;
        saveState();
    }

    // =========================================================================
    // Layer 1: Deterministic state transitions
    // =========================================================================

    private void applyAutomaticTransitions(List<SkillManager.Skill> skills, CuratorRunReport report) {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(STALE_THRESHOLD_DAYS, ChronoUnit.DAYS);
        Instant archiveThreshold = now.minus(ARCHIVE_THRESHOLD_DAYS, ChronoUnit.DAYS);

        for (SkillManager.Skill skill : skills) {
            if (skill.pinned) continue;

            Instant lastUsed = skill.lastUsedAt != null ? skill.lastUsedAt : skill.createdAt;
            if (lastUsed == null) lastUsed = now;

            if (lastUsed.isBefore(archiveThreshold)) {
                if (skill.lifecycleStatus != SkillLifecycleStatus.ARCHIVED) {
                    skillManager.archiveSkill(skill.name);
                    skill.lifecycleStatus = SkillLifecycleStatus.ARCHIVED;
                    report.autoArchived++;
                }
            } else if (lastUsed.isBefore(staleThreshold)) {
                if (skill.lifecycleStatus == SkillLifecycleStatus.ACTIVE) {
                    skill.lifecycleStatus = SkillLifecycleStatus.STALE;
                    report.autoMarkedStale++;
                }
            } else {
                if (skill.lifecycleStatus != SkillLifecycleStatus.ACTIVE) {
                    skill.lifecycleStatus = SkillLifecycleStatus.ACTIVE;
                    report.autoReactivated++;
                }
            }
        }
    }

    // =========================================================================
    // Layer 2: LLM consolidation pass
    // =========================================================================

    private void runLLMConsolidation(List<SkillManager.Skill> agentSkills, boolean dryRun,
                                      CuratorRunReport report) {
        if (config == null) {
            logger.warn("LLM consolidation skipped — no HermesConfig provided to CuratorJob");
            return;
        }

        String candidateList = renderCandidateList(agentSkills);
        if (candidateList.contains("No agent-created skills")) {
            return;
        }

        String prompt;
        if (dryRun) {
            prompt = CuratorReviewPrompts.DRY_RUN_BANNER + "\n\n" +
                     CuratorReviewPrompts.CURATOR_REVIEW_PROMPT + "\n\n" +
                     candidateList;
        } else {
            prompt = CuratorReviewPrompts.CURATOR_REVIEW_PROMPT + "\n\n" + candidateList;
        }

        try {
            SubAgent reviewAgent = new SubAgent(prompt, "", config);
            reviewAgent.withSystemPrompt(
                "You are Hermes' background skill CURATOR. You consolidate narrow skills "
                + "into class-level umbrellas. You can use skill_list, skill_get, skill_patch, "
                + "skill_create, skill_write_file, and skill_delete. Be thorough."
            ).withToolWhitelist(java.util.Set.of(
                "skill_list", "skill_get", "skill_patch", "skill_create",
                "skill_write_file", "skill_delete", "skill_search"
            )).withMaxIterations(999);

            SubAgentResult result = reviewAgent.call();

            if (result != null) {
                report.llmFinalResponse = result.output;
                report.llmToolCalls = result.iterationsUsed;
                if (!result.success) {
                    report.llmError = result.error;
                }

                // Parse structured YAML from the LLM response
                parseStructuredSummary(result.output, report);

                // Record new skills created
                detectNewSkills(agentSkills, report);
            }
        } catch (Exception e) {
            logger.error("LLM consolidation failed: {}", e.getMessage(), e);
            report.llmError = e.getMessage();
        }

        // Cron job skill reference rewrite
        if (!dryRun && (!report.consolidations.isEmpty() || !report.prunings.isEmpty())) {
            rewriteCronSkillReferences(report);
        }
    }

    // =========================================================================
    // Candidate list rendering
    // =========================================================================

    private String renderCandidateList(List<SkillManager.Skill> skills) {
        if (skills == null || skills.isEmpty()) return "No agent-created skills to review.";
        StringBuilder sb = new StringBuilder();
        sb.append("Agent-created skills (").append(skills.size()).append("):\n\n");
        for (SkillManager.Skill s : skills) {
            if (s.lifecycleStatus == SkillLifecycleStatus.ARCHIVED) continue;
            sb.append("- ").append(s.name)
              .append("  state=").append(s.lifecycleStatus)
              .append("  pinned=").append(s.pinned ? "yes" : "no")
              .append("  use=").append(s.usageCount)
              .append("  last_used=").append(s.lastUsedAt != null ? s.lastUsedAt : "never")
              .append("\n");
        }
        return sb.toString();
    }

    // =========================================================================
    // Structured summary parsing
    // =========================================================================

    private void parseStructuredSummary(String llmOutput, CuratorRunReport report) {
        if (llmOutput == null || llmOutput.isBlank()) return;

        // Extract ```yaml ... ``` block
        Pattern pattern = Pattern.compile("```ya?ml\\s*\\n(.*?)\\n```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(llmOutput);
        if (!matcher.find()) {
            // Fallback: heuristic detection from tool calls vs before/after skill list
            logger.debug("No structured YAML block found in curator LLM output");
            return;
        }

        String yamlBody = matcher.group(1);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yamlMapper.readValue(yamlBody, Map.class);

            // Parse consolidations
            Object cons = data.get("consolidations");
            if (cons instanceof List<?> consList) {
                for (Object entry : consList) {
                    if (entry instanceof Map<?, ?> map) {
                        Object fromVal = map.get("from");
                        Object intoVal = map.get("into");
                        Object reasonVal = map.get("reason");
                        if (fromVal != null && intoVal != null) {
                            String from = fromVal.toString();
                            String into = intoVal.toString();
                            String reason = reasonVal != null ? reasonVal.toString() : "";
                            if (!from.isBlank() && !into.isBlank()) {
                                report.consolidations.add(new CuratorRunReport.ConsolidationEntry(from, into, reason));
                            }
                        }
                    }
                }
            }

            // Parse prunings
            Object prun = data.get("prunings");
            if (prun instanceof List<?> prunList) {
                for (Object entry : prunList) {
                    if (entry instanceof Map<?, ?> map) {
                        Object nameVal = map.get("name");
                        Object reasonVal = map.get("reason");
                        if (nameVal != null) {
                            String name = nameVal.toString();
                            String reason = reasonVal != null ? reasonVal.toString() : "";
                            if (!name.isBlank()) {
                                report.prunings.add(new CuratorRunReport.PruningEntry(name, reason));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse curator YAML summary: {}", e.getMessage());
        }

        report.consolidatedCount = report.consolidations.size();
        report.prunedCount = report.prunings.size();
    }

    /**
     * Detect skills that exist now but didn't before the run (new umbrellas).
     */
    private void detectNewSkills(List<SkillManager.Skill> beforeSkills, CuratorRunReport report) {
        Set<String> beforeNames = new HashSet<>();
        for (SkillManager.Skill s : beforeSkills) beforeNames.add(s.name);

        List<SkillManager.Skill> afterSkills = provenanceService.getByProvenance(SkillProvenance.AGENT);
        for (SkillManager.Skill s : afterSkills) {
            if (!beforeNames.contains(s.name)) {
                report.newSkills.add(s.name);
            }
        }
        report.newSkillsCreated = report.newSkills.size();
    }

    // =========================================================================
    // Cron job skill reference rewrite
    // =========================================================================

    /**
     * Rewrite cron job skill references after consolidation.
     *
     * When skill X is consolidated into umbrella Y, any cron job that references
     * X should be updated to reference Y instead. Checks both on-disk cron config
     * files and in-memory CronjobTool jobs.
     */
    private void rewriteCronSkillReferences(CuratorRunReport report) {
        try {
            Map<String, String> renameMap = new HashMap<>();
            for (var c : report.consolidations) {
                renameMap.put(c.from, c.into);
            }
            for (var p : report.prunings) {
                renameMap.put(p.name, null);
            }
            if (renameMap.isEmpty()) return;

            int rewritten = 0;

            // 1. Check in-memory cron jobs via CronjobTool
            try {
                var cronTool = com.nousresearch.hermes.tools.impl.CronjobTool.class;
                // CronjobTool is instantiated by ToolInitializerV2 — we need to access its instance
                // The ToolRegistry stores handlers as lambdas; the CronjobTool instance is captured
                // in the closure. We use the tool dispatch path: cronjob_list returns the JSON.
                var toolRegistry = com.nousresearch.hermes.tools.ToolRegistry.getInstance();
                String jobsJson = toolRegistry.dispatch("cronjob_list", java.util.Map.of());
                // Parse and rewrite
                if (jobsJson != null && !jobsJson.isBlank()) {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var parsed = mapper.readTree(jobsJson);
                    if (parsed.has("jobs")) {
                        for (var jobNode : parsed.get("jobs")) {
                            String jobName = jobNode.get("name").asText();
                            String command = jobNode.get("command").asText();
                            String newCommand = rewriteCommand(command, renameMap);
                            if (newCommand != null && !newCommand.equals(command)) {
                                // Find the CronjobTool instance and update
                                if (updateCronJobInMemory(jobName, newCommand)) {
                                    rewritten++;
                                    logger.info("Rewrote in-memory cron job skill references in: {}", jobName);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("In-memory cron job rewrite skipped: {}", e.getMessage());
            }

            // 2. Check on-disk cron config files
            Path cronDir = skillManager.getSearchPaths().get(0).resolve("..").resolve("cron").normalize();
            if (Files.isDirectory(cronDir)) {
                try (Stream<Path> files = Files.list(cronDir)) {
                    var fileList = files.filter(p -> p.toString().endsWith(".json")).toList();
                    for (Path jobFile : fileList) {
                        try {
                            String content = Files.readString(jobFile);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> job = jsonMapper.readValue(content, Map.class);
                            @SuppressWarnings("unchecked")
                            List<String> skills = (List<String>) job.getOrDefault("skills", List.of());
                            if (skills == null || skills.isEmpty()) {
                                // Also check command string for skill references
                                String command = (String) job.get("command");
                                if (command != null) {
                                    String newCommand = rewriteCommand(command, renameMap);
                                    if (newCommand != null && !newCommand.equals(command)) {
                                        job.put("command", newCommand);
                                        Files.writeString(jobFile, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(job));
                                        rewritten++;
                                        logger.info("Rewrote cron job command in: {}", jobFile.getFileName());
                                    }
                                }
                                continue;
                            }

                            List<String> updated = new ArrayList<>();
                            boolean changed = false;
                            for (String skill : skills) {
                                if (renameMap.containsKey(skill)) {
                                    String newName = renameMap.get(skill);
                                    if (newName != null) updated.add(newName);
                                    changed = true;
                                } else {
                                    updated.add(skill);
                                }
                            }

                            if (changed) {
                                job.put("skills", updated);
                                Files.writeString(jobFile, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(job));
                                rewritten++;
                                logger.info("Rewrote cron job skill references in: {}", jobFile.getFileName());
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to process cron job file {}: {}", jobFile, e.getMessage());
                        }
                    }
                }
            }

            report.cronJobsRewritten = rewritten;
        } catch (Exception e) {
            logger.debug("Cron skill reference rewrite failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Rewrite skill references in a command string.
     * Handles patterns like: /skill-name, "skill-name", or skill-name as an argument.
     */
    private String rewriteCommand(String command, Map<String, String> renameMap) {
        if (command == null) return null;
        String result = command;
        for (var entry : renameMap.entrySet()) {
            String oldName = entry.getKey();
            String newName = entry.getValue();
            // Replace /old-name with /new-name or remove if pruned
            if (newName != null) {
                result = result.replace("/" + oldName, "/" + newName);
                result = result.replace(" " + oldName + " ", " " + newName + " ");
            } else {
                // Pruned — can't safely remove from command string, just log
                logger.debug("Skill '{}' was pruned but is referenced in command: {}", oldName, command);
            }
        }
        return result;
    }

    /**
     * Best-effort: update a cron job in CronjobTool's in-memory store.
     */
    private boolean updateCronJobInMemory(String jobName, String newCommand) {
        try {
            // Access the CronjobTool instance through reflection on ToolRegistry
            // This is a pragmatic approach — the alternative is a full DI refactor
            var toolRegistry = com.nousresearch.hermes.tools.ToolRegistry.getInstance();
            // Try to find a CronjobTool instance that was registered
            for (var field : toolRegistry.getClass().getDeclaredFields()) {
                if (java.util.Collection.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(toolRegistry);
                    if (value instanceof java.util.Collection<?> coll) {
                        for (var item : coll) {
                            // Check if item is a ToolEntry that wraps CronjobTool
                            if (item != null) {
                                for (var innerField : item.getClass().getDeclaredFields()) {
                                    if (innerField.getType().getName().contains("CronjobTool")) {
                                        innerField.setAccessible(true);
                                        Object cronTool = innerField.get(item);
                                        if (cronTool instanceof com.nousresearch.hermes.tools.impl.CronjobTool ct) {
                                            return ct.updateJobCommand(jobName, newCommand);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not update in-memory cron job: {}", e.getMessage());
        }
        return false;
    }

    // =========================================================================
    // Report writing
    // =========================================================================

    private void writeReport(CuratorRunReport report) {
        try {
            Files.createDirectories(reportsDir);
            String timestamp = report.startedAt.toString().replace(":", "").replace("-", "").substring(0, 15);
            Path reportDir = reportsDir.resolve(timestamp);
            Files.createDirectories(reportDir);

            // run.json
            Path jsonPath = reportDir.resolve("run.json");
            Files.writeString(jsonPath, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));

            // REPORT.md
            Path mdPath = reportDir.resolve("REPORT.md");
            Files.writeString(mdPath, report.toMarkdown());

            state.lastReportPath = reportDir.toString();
            logger.info("Curator report written to: {}", reportDir);
        } catch (IOException e) {
            logger.debug("Failed to write curator report: {}", e.getMessage());
        }
    }

    // =========================================================================
    // State persistence
    // =========================================================================

    private CuratorState loadState() {
        if (!Files.exists(stateFile)) return new CuratorState();
        try {
            return jsonMapper.readValue(stateFile.toFile(), CuratorState.class);
        } catch (Exception e) {
            logger.debug("Failed to read curator state: {}", e.getMessage());
            return new CuratorState();
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
        } catch (Exception e) {
            logger.debug("Failed to save curator state: {}", e.getMessage());
        }
    }

    public CuratorState getState() {
        return state;
    }

    // =========================================================================
    // Data classes
    // =========================================================================

    public static class CuratorState {
        public String lastRunAt;
        public Double lastRunDurationSeconds;
        public String lastRunSummary;
        public String lastReportPath;
        public boolean paused = false;
        public int runCount = 0;
    }

    // Legacy compat — old CuratorReport record
    public record CuratorReport(
        int totalReviewed,
        int pinnedCount,
        int markedStaleCount,
        int archivedCount,
        int restoredCount,
        int consolidationCandidateCount,
        List<ConsolidationCandidate> consolidationCandidates,
        Instant executedAt
    ) {
        @Override
        public String toString() {
            return String.format("CuratorReport{reviewed=%d, pinned=%d, stale=%d, archived=%d, restored=%d, consolidate=%d}",
                totalReviewed, pinnedCount, markedStaleCount, archivedCount, restoredCount, consolidationCandidateCount);
        }
    }

    public record ConsolidationCandidate(
        List<String> skillNames,
        String reason,
        String description
    ) {}
}
