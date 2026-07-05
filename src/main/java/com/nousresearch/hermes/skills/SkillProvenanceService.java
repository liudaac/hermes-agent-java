package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * S5-1: Skill 来源追溯服务。
 *
 * <p>对齐原版 tools/skill_provenance.py + tools/skill_usage.py。</p>
 *
 * <p>能力：</p>
 * <ul>
 *   <li>设置/获取 skill 的 provenance</li>
 *   <li>记录 skill 使用（更新 usageCount + lastUsedAt）</li>
 *   <li>按 provenance 筛选 skill</li>
 *   <li>Agent 主动创建 skill（provenance = AGENT）</li>
 *   <li>Pin/unpin skill</li>
 *   <li>获取 skill 统计信息</li>
 * </ul>
 */
public class SkillProvenanceService {
    private static final Logger logger = LoggerFactory.getLogger(SkillProvenanceService.class);

    private final SkillManager skillManager;

    public SkillProvenanceService(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    /**
     * 设置 skill 的 provenance。
     */
    public boolean setProvenance(String skillName, SkillProvenance provenance) {
        SkillManager.Skill skill = findSkill(skillName);
        if (skill == null) {
            logger.warn("Cannot set provenance: skill '{}' not found", skillName);
            return false;
        }
        skill.provenance = provenance;
        logger.info("Set provenance of '{}' to {}", skillName, provenance);
        return true;
    }

    /**
     * 获取 skill 的 provenance。
     */
    public SkillProvenance getProvenance(String skillName) {
        SkillManager.Skill skill = findSkill(skillName);
        return skill != null ? skill.provenance : null;
    }

    /**
     * 记录 skill 使用（更新 usageCount + lastUsedAt）。
     */
    public void recordUsage(String skillName) {
        SkillManager.Skill skill = findSkill(skillName);
        if (skill == null) return;
        skill.usageCount++;
        skill.lastUsedAt = Instant.now();
        // 如果是归档状态，使用时自动恢复为 active
        if (skill.lifecycleStatus == SkillLifecycleStatus.ARCHIVED) {
            skill.lifecycleStatus = SkillLifecycleStatus.ACTIVE;
            logger.info("Skill '{}' auto-restored from ARCHIVED to ACTIVE", skillName);
        }
    }

    /**
     * 按来源筛选 skill。
     */
    public List<SkillManager.Skill> getByProvenance(SkillProvenance provenance) {
        return skillManager.listSkills().stream()
            .filter(s -> s.provenance == provenance)
            .toList();
    }

    /**
     * Agent 主动创建 skill。
     *
     * @param name skill 名称
     * @param description 描述
     * @param content 内容
     * @param tags 标签
     * @return 创建的 skill，或 null 如果已存在
     */
    public SkillManager.Skill createAgentSkill(String name, String description,
                                                String content, List<String> tags) {
        // 检查是否已存在
        if (findSkill(name) != null) {
            logger.warn("Cannot create agent skill: '{}' already exists", name);
            return null;
        }

        SkillManager.Skill skill = new SkillManager.Skill();
        skill.name = name;
        skill.description = description;
        skill.content = content;
        skill.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        skill.createdAt = Instant.now();
        skill.updatedAt = Instant.now();
        skill.source = "agent";
        skill.provenance = SkillProvenance.AGENT;
        skill.lastUsedAt = Instant.now();
        skill.pinned = false;
        skill.lifecycleStatus = SkillLifecycleStatus.ACTIVE;

        logger.info("Agent skill created: '{}'", name);
        return skill;
    }

    /**
     * Pin skill（跳过所有生命周期转换）。
     */
    public boolean pin(String skillName) {
        SkillManager.Skill skill = findSkill(skillName);
        if (skill == null) return false;
        skill.pinned = true;
        logger.info("Skill '{}' pinned", skillName);
        return true;
    }

    /**
     * Unpin skill。
     */
    public boolean unpin(String skillName) {
        SkillManager.Skill skill = findSkill(skillName);
        if (skill == null) return false;
        skill.pinned = false;
        logger.info("Skill '{}' unpinned", skillName);
        return true;
    }

    /**
     * 获取 skill 统计信息。
     */
    public SkillStatistics getStatistics() {
        List<SkillManager.Skill> all = skillManager.listSkills();
        long totalCount = all.size();
        long agentCount = all.stream().filter(s -> s.provenance == SkillProvenance.AGENT).count();
        long userCount = all.stream().filter(s -> s.provenance == SkillProvenance.USER).count();
        long importCount = all.stream().filter(s -> s.provenance == SkillProvenance.IMPORT).count();
        long bundledCount = all.stream().filter(s -> s.provenance == SkillProvenance.BUNDLED).count();
        long pinnedCount = all.stream().filter(s -> s.pinned).count();
        long activeCount = all.stream().filter(s -> s.lifecycleStatus == SkillLifecycleStatus.ACTIVE).count();
        long staleCount = all.stream().filter(s -> s.lifecycleStatus == SkillLifecycleStatus.STALE).count();
        long archivedCount = all.stream().filter(s -> s.lifecycleStatus == SkillLifecycleStatus.ARCHIVED).count();
        long totalUsage = all.stream().mapToLong(s -> s.usageCount).sum();

        return new SkillStatistics(
            totalCount, agentCount, userCount, importCount, bundledCount,
            pinnedCount, activeCount, staleCount, archivedCount, totalUsage
        );
    }

    /**
     * 获取最常使用的 skill top-K。
     */
    public List<SkillManager.Skill> getTopUsed(int limit) {
        return skillManager.listSkills().stream()
            .sorted(Comparator.comparingInt((SkillManager.Skill s) -> s.usageCount).reversed())
            .limit(limit)
            .toList();
    }

    /**
     * 获取最近使用的 skill。
     */
    public List<SkillManager.Skill> getRecentlyUsed(int limit) {
        return skillManager.listSkills().stream()
            .filter(s -> s.lastUsedAt != null)
            .sorted(Comparator.comparing((SkillManager.Skill s) -> s.lastUsedAt).reversed())
            .limit(limit)
            .toList();
    }

    private SkillManager.Skill findSkill(String name) {
        if (name == null) return null;
        return skillManager.listSkills().stream()
            .filter(s -> name.equals(s.name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Skill 统计信息。
     */
    public record SkillStatistics(
        long totalSkills,
        long agentCreated,
        long userCreated,
        long imported,
        long bundled,
        long pinned,
        long active,
        long stale,
        long archived,
        long totalUsageCount
    ) {
        public double avgUsagePerSkill() {
            return totalSkills > 0 ? (double) totalUsageCount / totalSkills : 0;
        }
    }
}
