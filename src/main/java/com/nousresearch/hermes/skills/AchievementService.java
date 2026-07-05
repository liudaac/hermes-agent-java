package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S6-3: Achievements 成就系统 — 让沉淀可见。
 *
 * <p>对齐原版 plugins/hermes-achievements。</p>
 *
 * <p>游戏化激励：agent 学了 N 个 skill / 跑了 N 次任务 → 成就解锁。</p>
 */
public class AchievementService {
    private static final Logger logger = LoggerFactory.getLogger(AchievementService.class);

    private final Map<String, Achievement> achievements = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> unlockedByTenant = new ConcurrentHashMap<>();
    private final Map<String, Integer> progressByTenant = new ConcurrentHashMap<>();

    /**
     * 注册一个成就。
     */
    public void register(Achievement achievement) {
        achievements.put(achievement.id(), achievement);
        logger.debug("Registered achievement: {}", achievement);
    }

    /**
     * 注册内建成就。
     */
    public void registerDefaults() {
        register(new Achievement("first-skill", "初出茅庐", "创建第一个 skill", 1, "🎯"));
        register(new Achievement("ten-skills", "技能收藏家", "拥有 10 个 skill", 10, "🏆"));
        register(new Achievement("fifty-skills", "技能大师", "拥有 50 个 skill", 50, "👑"));
        register(new Achievement("first-task", "破冰", "完成第一个任务", 1, "⚡"));
        register(new Achievement("hundred-tasks", "百战不殆", "完成 100 个任务", 100, "🔥"));
        register(new Achievement("first-curated", "策展人", "首次 Curator 审查", 1, "🧹"));
        register(new Achievement("first-bundle", "组合拳", "创建第一个 Skill Bundle", 1, "📦"));
        register(new Achievement("evolution-master", "进化者", "从失败中学习 10 次", 10, "🧬"));
        register(new Achievement("cost-conscious", "精打细算", "单次任务成本 <$0.01", 1, "💰"));
        register(new Achievement("polyglot", "多语言", "使用 3 种以上模型", 3, "🌍"));
    }

    /**
     * 更新进度并检查是否解锁。
     *
     * @param tenantId 租户 ID
     * @param metric 指标 key（如 "skills-created", "tasks-completed"）
     * @param currentValue 当前值
     * @return 新解锁的成就列表
     */
    public List<Achievement> updateProgress(String tenantId, String metric, int currentValue) {
        String progressKey = tenantId + ":" + metric;
        progressByTenant.put(progressKey, currentValue);

        List<Achievement> newlyUnlocked = new ArrayList<>();
        Set<String> unlocked = unlockedByTenant.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet());

        for (Achievement achievement : achievements.values()) {
            if (unlocked.contains(achievement.id())) continue;
            if (achievement.metric() == null || !achievement.metric().equals(metric)) continue;
            if (currentValue >= achievement.threshold()) {
                unlocked.add(achievement.id());
                newlyUnlocked.add(achievement);
                logger.info("Achievement unlocked! tenant={}, achievement={}", tenantId, achievement.title());
            }
        }

        return newlyUnlocked;
    }

    /**
     * 直接解锁成就（特殊事件触发）。
     */
    public boolean unlock(String tenantId, String achievementId) {
        Achievement achievement = achievements.get(achievementId);
        if (achievement == null) return false;
        Set<String> unlocked = unlockedByTenant.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet());
        if (unlocked.contains(achievementId)) return false;
        unlocked.add(achievementId);
        logger.info("Achievement unlocked! tenant={}, achievement={}", tenantId, achievement.title());
        return true;
    }

    /**
     * 获取租户已解锁的成就。
     */
    public List<Achievement> getUnlocked(String tenantId) {
        Set<String> unlocked = unlockedByTenant.get(tenantId);
        if (unlocked == null) return List.of();
        return unlocked.stream()
            .map(achievements::get)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * 获取所有成就（含未解锁）。
     */
    public List<Achievement> getAll() {
        return new ArrayList<>(achievements.values());
    }

    /**
     * 获取未解锁的成就。
     */
    public List<Achievement> getLocked(String tenantId) {
        Set<String> unlocked = unlockedByTenant.getOrDefault(tenantId, Set.of());
        return achievements.values().stream()
            .filter(a -> !unlocked.contains(a.id()))
            .toList();
    }

    /**
     * 获取解锁进度。
     */
    public UnlockProgress getUnlockProgress(String tenantId) {
        int total = achievements.size();
        int unlocked = getUnlocked(tenantId).size();
        return new UnlockProgress(unlocked, total, total > 0 ? (double) unlocked / total : 0);
    }

    // ============ 数据类 ============

    public record Achievement(
        String id,
        String title,
        String description,
        int threshold,
        String emoji,
        String metric  // 关联的指标 key（null = 手动解锁）
    ) {
        public Achievement(String id, String title, String description, int threshold, String emoji) {
            this(id, title, description, threshold, emoji, null);
        }

        public Achievement(String id, String title, String description, int threshold, String emoji, String metric) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.threshold = threshold;
            this.emoji = emoji;
            this.metric = metric;
        }
    }

    public record UnlockProgress(int unlocked, int total, double percentage) {
        public boolean isComplete() { return unlocked == total && total > 0; }
    }
}
