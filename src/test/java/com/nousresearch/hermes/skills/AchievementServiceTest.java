package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S6-3: AchievementService 测试
 */
class AchievementServiceTest {

    private AchievementService service;

    @BeforeEach
    void setUp() {
        service = new AchievementService();
        service.registerDefaults();
    }

    // ========================================================================
    // registerDefaults
    // ========================================================================

    @Nested
    @DisplayName("registerDefaults")
    class DefaultsTest {

        @Test
        @DisplayName("注册了 10 个内建成就")
        void count() {
            assertEquals(10, service.getAll().size());
        }

        @Test
        @DisplayName("包含关键成就")
        void containsKey() {
            assertTrue(service.getAll().stream().anyMatch(a -> a.id().equals("first-skill")));
            assertTrue(service.getAll().stream().anyMatch(a -> a.id().equals("hundred-tasks")));
        }
    }

    // ========================================================================
    // updateProgress + 自动解锁
    // ========================================================================

    @Nested
    @DisplayName("updateProgress 自动解锁")
    class UpdateProgressTest {

        @Test
        @DisplayName("skills-created=1 → 解锁 first-skill")
        void firstSkillUnlocked() {
            // 注册 metric 关联
            service.register(new Achievement("test-skill", "测试", "desc", 1, "🎯", "skills-created"));

            List<AchievementService.Achievement> unlocked = service.updateProgress("t1", "skills-created", 1);
            assertFalse(unlocked.isEmpty());
        }

        @Test
        @DisplayName("skills-created=10 → 解锁 first-skill + ten-skills")
        void tenSkillsUnlocked() {
            service.register(new Achievement("first-skill", "初出茅庐", "desc", 1, "🎯", "skills-created"));
            service.register(new Achievement("ten-skills", "收藏家", "desc", 10, "🏆", "skills-created"));

            List<AchievementService.Achievement> unlocked = service.updateProgress("t1", "skills-created", 10);
            assertEquals(2, unlocked.size());
        }

        @Test
        @DisplayName("重复 updateProgress 不重复解锁")
        void noRepeatUnlock() {
            service.register(new Achievement("a1", "A1", "desc", 5, "🎯", "metric-1"));

            service.updateProgress("t1", "metric-1", 5);
            List<AchievementService.Achievement> second = service.updateProgress("t1", "metric-1", 10);
            assertTrue(second.isEmpty());
        }

        @Test
        @DisplayName("未达阈值 → 不解锁")
        void belowThreshold() {
            service.register(new Achievement("hard", "困难", "desc", 100, "💎", "metric-x"));
            List<AchievementService.Achievement> unlocked = service.updateProgress("t1", "metric-x", 50);
            assertTrue(unlocked.isEmpty());
        }

        @Test
        @DisplayName("不同租户独立解锁")
        void tenantIsolation() {
            service.register(new Achievement("a1", "A1", "desc", 1, "🎯", "m1"));

            List<AchievementService.Achievement> t1 = service.updateProgress("t1", "m1", 1);
            List<AchievementService.Achievement> t2 = service.updateProgress("t2", "m1", 0);

            assertFalse(t1.isEmpty());
            assertTrue(t2.isEmpty());
            assertEquals(1, service.getUnlocked("t1").size());
            assertEquals(0, service.getUnlocked("t2").size());
        }
    }

    // ========================================================================
    // unlock 手动解锁
    // ========================================================================

    @Nested
    @DisplayName("unlock 手动解锁")
    class UnlockTest {

        @Test
        @DisplayName("手动解锁成功")
        void unlockSuccess() {
            assertTrue(service.unlock("t1", "first-skill"));
            assertEquals(1, service.getUnlocked("t1").size());
        }

        @Test
        @DisplayName("重复解锁 → false")
        void repeatUnlock() {
            service.unlock("t1", "first-skill");
            assertFalse(service.unlock("t1", "first-skill"));
        }

        @Test
        @DisplayName("不存在的成就 → false")
        void notFound() {
            assertFalse(service.unlock("t1", "nonexistent"));
        }
    }

    // ========================================================================
    // getUnlocked / getLocked / getAll
    // ========================================================================

    @Nested
    @DisplayName("查询方法")
    class QueryTest {

        @Test
        @DisplayName("getUnlocked 空租户 → 空列表")
        void emptyTenant() {
            assertTrue(service.getUnlocked("nobody").isEmpty());
        }

        @Test
        @DisplayName("getLocked 返回未解锁的")
        void lockedSkills() {
            service.unlock("t1", "first-skill");
            List<AchievementService.Achievement> locked = service.getLocked("t1");
            assertEquals(9, locked.size()); // 10 - 1 = 9
        }

        @Test
        @DisplayName("getAll 返回所有")
        void all() {
            assertEquals(10, service.getAll().size());
        }
    }

    // ========================================================================
    // getUnlockProgress
    // ========================================================================

    @Nested
    @DisplayName("getUnlockProgress")
    class ProgressTest {

        @Test
        @DisplayName("初始 0%")
        void initialProgress() {
            AchievementService.UnlockProgress p = service.getUnlockProgress("t1");
            assertEquals(0, p.unlocked());
            assertEquals(10, p.total());
            assertEquals(0.0, p.percentage(), 0.01);
            assertFalse(p.isComplete());
        }

        @Test
        @DisplayName("解锁 5 个 → 50%")
        void halfProgress() {
            service.unlock("t1", "first-skill");
            service.unlock("t1", "first-task");
            service.unlock("t1", "first-curated");
            service.unlock("t1", "first-bundle");
            service.unlock("t1", "cost-conscious");

            AchievementService.UnlockProgress p = service.getUnlockProgress("t1");
            assertEquals(5, p.unlocked());
            assertEquals(10, p.total());
            assertEquals(0.5, p.percentage(), 0.01);
            assertFalse(p.isComplete());
        }

        @Test
        @DisplayName("全部解锁 → 100% + isComplete")
        void complete() {
            for (AchievementService.Achievement a : service.getAll()) {
                service.unlock("t1", a.id());
            }
            AchievementService.UnlockProgress p = service.getUnlockProgress("t1");
            assertEquals(10, p.unlocked());
            assertEquals(1.0, p.percentage(), 0.01);
            assertTrue(p.isComplete());
        }
    }

    // ========================================================================
    // Achievement 值对象
    // ========================================================================

    @Nested
    @DisplayName("Achievement 值对象")
    class AchievementTest {

        @Test
        @DisplayName("基本构造（无 metric）")
        void withoutMetric() {
            AchievementService.Achievement a = new AchievementService.Achievement("id", "title", "desc", 5, "🎯");
            assertEquals("id", a.id());
            assertEquals("title", a.title());
            assertEquals("desc", a.description());
            assertEquals(5, a.threshold());
            assertEquals("🎯", a.emoji());
            assertNull(a.metric());
        }

        @Test
        @DisplayName("基本构造（带 metric）")
        void withMetric() {
            AchievementService.Achievement a = new AchievementService.Achievement("id", "title", "desc", 5, "🎯", "counter");
            assertEquals("counter", a.metric());
        }
    }
}
