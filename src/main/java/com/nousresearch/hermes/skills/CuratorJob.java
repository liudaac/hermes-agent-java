package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * S5-2: Curator Job — 后台维护 agent 自建 skill 的生命周期。
 *
 * <p>对齐原版 agent/curator.py，但复用已有的 SelfEvolutionEngine + EvolutionProposalService 基础。</p>
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>只碰 provenance = AGENT 的 skill</li>
 *   <li>生命周期：active → stale (30d) → archived (90d)</li>
 *   <li>永不删除，只归档，可恢复</li>
 *   <li>Pinned skill 跳过所有转换</li>
 *   <li>输出 CuratorReport</li>
 * </ul>
 */
public class CuratorJob {
    private static final Logger logger = LoggerFactory.getLogger(CuratorJob.class);

    /** 超过 30 天未使用 → STALE */
    public static final long STALE_THRESHOLD_DAYS = 30;
    /** 超过 90 天未使用 → ARCHIVED */
    public static final long ARCHIVE_THRESHOLD_DAYS = 90;

    private final SkillProvenanceService provenanceService;
    private final SkillManager skillManager;

    public CuratorJob(SkillProvenanceService provenanceService, SkillManager skillManager) {
        this.provenanceService = provenanceService;
        this.skillManager = skillManager;
    }

    /**
     * 执行一次完整的 curate 循环。
     *
     * @return curate 报告
     */
    public CuratorReport run() {
        logger.info("Curator job started");

        List<SkillManager.Skill> agentSkills = provenanceService.getByProvenance(SkillProvenance.AGENT);
        int totalReviewed = agentSkills.size();
        int pinned = 0;
        int markedStale = 0;
        int archived = 0;
        int restored = 0;
        int consolidated = 0;

        Instant now = Instant.now();
        Instant staleThreshold = now.minus(STALE_THRESHOLD_DAYS, ChronoUnit.DAYS);
        Instant archiveThreshold = now.minus(ARCHIVE_THRESHOLD_DAYS, ChronoUnit.DAYS);

        for (SkillManager.Skill skill : agentSkills) {
            // Pinned skill 跳过
            if (skill.pinned) {
                pinned++;
                continue;
            }

            Instant lastUsed = skill.lastUsedAt != null ? skill.lastUsedAt : skill.createdAt;
            if (lastUsed == null) lastUsed = now;

            // 归档检查：超过 90 天未使用
            if (lastUsed.isBefore(archiveThreshold)) {
                if (skill.lifecycleStatus != SkillLifecycleStatus.ARCHIVED) {
                    skill.lifecycleStatus = SkillLifecycleStatus.ARCHIVED;
                    archived++;
                    logger.debug("Archived skill: '{}' (last used: {})", skill.name, lastUsed);
                }
                continue;
            }

            // 过期检查：超过 30 天未使用
            if (lastUsed.isBefore(staleThreshold)) {
                if (skill.lifecycleStatus == SkillLifecycleStatus.ACTIVE) {
                    skill.lifecycleStatus = SkillLifecycleStatus.STALE;
                    markedStale++;
                    logger.debug("Marked stale: '{}' (last used: {})", skill.name, lastUsed);
                }
                continue;
            }

            // 活跃检查：如果之前是 stale/archived 但最近又用了，恢复
            if (skill.lifecycleStatus != SkillLifecycleStatus.ACTIVE) {
                skill.lifecycleStatus = SkillLifecycleStatus.ACTIVE;
                restored++;
                logger.debug("Restored to active: '{}'", skill.name);
            }
        }

        // 合并检查：寻找名称相似或内容重复的 skill
        List<ConsolidationCandidate> candidates = findConsolidationCandidates(agentSkills);
        consolidated = candidates.size();

        CuratorReport report = new CuratorReport(
            totalReviewed, pinned, markedStale, archived, restored, consolidated,
            candidates, now
        );

        logger.info("Curator job completed: {}", report);
        return report;
    }

    /**
     * 查找可合并的 skill 候选（名称相似或标签重叠）。
     */
    private List<ConsolidationCandidate> findConsolidationCandidates(List<SkillManager.Skill> skills) {
        List<ConsolidationCandidate> candidates = new ArrayList<>();

        // 按 tag 分组，相同 tag 的 skill 可能可合并
        Map<String, List<SkillManager.Skill>> byTag = new HashMap<>();
        for (SkillManager.Skill s : skills) {
            if (s.lifecycleStatus == SkillLifecycleStatus.ARCHIVED) continue;
            for (String tag : s.tags) {
                byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(s);
            }
        }

        // 找有 2+ skill 共享同一 tag 的
        for (var entry : byTag.entrySet()) {
            if (entry.getValue().size() >= 2) {
                candidates.add(new ConsolidationCandidate(
                    entry.getValue().stream().map(s -> s.name).toList(),
                    entry.getKey(),
                    "Shared tag: " + entry.getKey()
                ));
            }
        }

        return candidates;
    }

    /**
     * 恢复已归档的 skill（手动操作）。
     */
    public boolean restore(String skillName) {
        SkillManager.Skill skill = skillManager.listSkills().stream()
            .filter(s -> skillName.equals(s.name))
            .findFirst()
            .orElse(null);
        if (skill == null) return false;
        if (skill.lifecycleStatus != SkillLifecycleStatus.ARCHIVED) return false;

        skill.lifecycleStatus = SkillLifecycleStatus.ACTIVE;
        skill.lastUsedAt = Instant.now();
        logger.info("Manually restored skill: '{}'", skillName);
        return true;
    }

    // ============ 报告数据类 ============

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
