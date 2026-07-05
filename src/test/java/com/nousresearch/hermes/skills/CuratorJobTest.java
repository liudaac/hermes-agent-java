package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S5-2: CuratorJob 测试
 */
class CuratorJobTest {

    private SkillManager skillManager;
    private SkillProvenanceService provenanceService;
    private CuratorJob curator;

    @BeforeEach
    void setUp() {
        skillManager = org.mockito.Mockito.mock(SkillManager.class);
        provenanceService = org.mockito.Mockito.mock(SkillProvenanceService.class);
        curator = new CuratorJob(provenanceService, skillManager);
    }

    private SkillManager.Skill createSkill(String name, Instant lastUsed, boolean pinned, SkillLifecycleStatus status) {
        SkillManager.Skill s = new SkillManager.Skill();
        s.name = name;
        s.provenance = SkillProvenance.AGENT;
        s.lastUsedAt = lastUsed;
        s.pinned = pinned;
        s.lifecycleStatus = status;
        s.createdAt = Instant.now().minus(100, ChronoUnit.DAYS);
        s.tags = new java.util.ArrayList<>();
        return s;
    }

    // ========================================================================
    // 生命周期转换
    // ========================================================================

    @Nested
    @DisplayName("生命周期转换")
    class LifecycleTransitionsTest {

        @Test
        @DisplayName("超过 30 天未使用 → STALE")
        void markStale() {
            SkillManager.Skill skill = createSkill("old-skill",
                Instant.now().minus(45, ChronoUnit.DAYS), false, SkillLifecycleStatus.ACTIVE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(1, report.totalReviewed());
            assertEquals(1, report.markedStaleCount());
            assertEquals(SkillLifecycleStatus.STALE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("超过 90 天未使用 → ARCHIVED")
        void archive() {
            SkillManager.Skill skill = createSkill("ancient-skill",
                Instant.now().minus(100, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(1, report.archivedCount());
            assertEquals(SkillLifecycleStatus.ARCHIVED, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("最近使用过的 → 保持 ACTIVE")
        void stayActive() {
            SkillManager.Skill skill = createSkill("active-skill",
                Instant.now().minus(5, ChronoUnit.DAYS), false, SkillLifecycleStatus.ACTIVE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(0, report.markedStaleCount());
            assertEquals(0, report.archivedCount());
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("Pinned skill 跳过所有转换")
        void pinnedSkipped() {
            SkillManager.Skill skill = createSkill("pinned-skill",
                Instant.now().minus(200, ChronoUnit.DAYS), true, SkillLifecycleStatus.ACTIVE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(1, report.pinnedCount());
            assertEquals(0, report.archivedCount());
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("STALE skill 最近又用了 → 恢复 ACTIVE")
        void restoreFromStale() {
            SkillManager.Skill skill = createSkill("revived-skill",
                Instant.now().minus(2, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(1, report.restoredCount());
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("已是 ARCHIVED 的不重复归档")
        void alreadyArchived() {
            SkillManager.Skill skill = createSkill("already-archived",
                Instant.now().minus(200, ChronoUnit.DAYS), false, SkillLifecycleStatus.ARCHIVED);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(0, report.archivedCount()); // 不重复计数
        }

        @Test
        @DisplayName("已是 STALE 的不重复标记")
        void alreadyStale() {
            SkillManager.Skill skill = createSkill("already-stale",
                Instant.now().minus(45, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(0, report.markedStaleCount()); // 不重复计数
        }
    }

    // ========================================================================
    // 合并候选
    // ========================================================================

    @Nested
    @DisplayName("合并候选检测")
    class ConsolidationTest {

        @Test
        @DisplayName("共享 tag 的 skill 被识别为合并候选")
        void sharedTagCandidates() {
            SkillManager.Skill s1 = createSkill("search-web", Instant.now(), false, SkillLifecycleStatus.ACTIVE);
            s1.tags.add("search");
            SkillManager.Skill s2 = createSkill("search-files", Instant.now(), false, SkillLifecycleStatus.ACTIVE);
            s2.tags.add("search");
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(s1, s2));

            CuratorJob.CuratorReport report = curator.run();
            assertTrue(report.consolidationCandidateCount() > 0);
            assertFalse(report.consolidationCandidates().isEmpty());
        }

        @Test
        @DisplayName("无共享 tag → 无合并候选")
        void noSharedTag() {
            SkillManager.Skill s1 = createSkill("a", Instant.now(), false, SkillLifecycleStatus.ACTIVE);
            s1.tags.add("coding");
            SkillManager.Skill s2 = createSkill("b", Instant.now(), false, SkillLifecycleStatus.ACTIVE);
            s2.tags.add("writing");
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(s1, s2));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(0, report.consolidationCandidateCount());
        }

        @Test
        @DisplayName("ARCHIVED skill 不参与合并检测")
        void archivedExcluded() {
            SkillManager.Skill s1 = createSkill("a", Instant.now(), false, SkillLifecycleStatus.ACTIVE);
            s1.tags.add("shared");
            SkillManager.Skill s2 = createSkill("b", Instant.now(), false, SkillLifecycleStatus.ARCHIVED);
            s2.tags.add("shared");
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(s1, s2));

            CuratorJob.CuratorReport report = curator.run();
            // 只有 1 个活跃 skill 有 "shared" tag，不构成候选
            assertEquals(0, report.consolidationCandidateCount());
        }
    }

    // ========================================================================
    // 空数据处理
    // ========================================================================

    @Nested
    @DisplayName("空数据处理")
    class EmptyDataTest {

        @Test
        @DisplayName("无 agent skill → 空报告")
        void noAgentSkills() {
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of());

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(0, report.totalReviewed());
            assertEquals(0, report.pinnedCount());
            assertEquals(0, report.markedStaleCount());
            assertEquals(0, report.archivedCount());
        }
    }

    // ========================================================================
    // 混合场景
    // ========================================================================

    @Nested
    @DisplayName("混合场景")
    class MixedScenarioTest {

        @Test
        @DisplayName("5 个 skill 混合状态 → 正确分类")
        void mixed() {
            Instant now = Instant.now();
            SkillManager.Skill active = createSkill("active", now.minus(1, ChronoUnit.DAYS), false, SkillLifecycleStatus.ACTIVE);
            SkillManager.Skill pinned = createSkill("pinned", now.minus(200, ChronoUnit.DAYS), true, SkillLifecycleStatus.ACTIVE);
            SkillManager.Skill toStale = createSkill("to-stale", now.minus(40, ChronoUnit.DAYS), false, SkillLifecycleStatus.ACTIVE);
            SkillManager.Skill toArchive = createSkill("to-archive", now.minus(100, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            SkillManager.Skill toRestore = createSkill("to-restore", now.minus(3, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);

            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(active, pinned, toStale, toArchive, toRestore));

            CuratorJob.CuratorReport report = curator.run();
            assertEquals(5, report.totalReviewed());
            assertEquals(1, report.pinnedCount());
            assertEquals(1, report.markedStaleCount());
            assertEquals(1, report.archivedCount());
            assertEquals(1, report.restoredCount());
        }
    }

    // ========================================================================
    // restore 手动恢复
    // ========================================================================

    @Nested
    @DisplayName("restore 手动恢复")
    class RestoreTest {

        @Test
        @DisplayName("恢复 ARCHIVED skill")
        void restoreArchived() {
            SkillManager.Skill skill = createSkill("archived", Instant.now().minus(100, ChronoUnit.DAYS), false, SkillLifecycleStatus.ARCHIVED);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            assertTrue(curator.restore("archived"));
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
            assertNotNull(skill.lastUsedAt);
        }

        @Test
        @DisplayName("恢复 ACTIVE skill → false（不需要恢复）")
        void restoreActive() {
            SkillManager.Skill skill = createSkill("active", Instant.now(), false, SkillLifecycleStatus.ACTIVE);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            assertFalse(curator.restore("active"));
        }

        @Test
        @DisplayName("不存在的 skill → false")
        void notFound() {
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of());
            assertFalse(curator.restore("nonexistent"));
        }
    }

    // ========================================================================
    // CuratorReport
    // ========================================================================

    @Nested
    @DisplayName("CuratorReport")
    class ReportTest {

        @Test
        @DisplayName("toString 包含关键字段")
        void toString_() {
            CuratorJob.CuratorReport report = new CuratorJob.CuratorReport(
                10, 2, 3, 1, 0, 1, List.of(), Instant.now());
            String s = report.toString();
            assertTrue(s.contains("reviewed=10"));
            assertTrue(s.contains("pinned=2"));
            assertTrue(s.contains("stale=3"));
            assertTrue(s.contains("archived=1"));
        }
    }

    // ========================================================================
    // 阈值常量
    // ========================================================================

    @Nested
    @DisplayName("阈值常量")
    class ThresholdTest {

        @Test
        @DisplayName("STALE 阈值 = 30 天")
        void staleThreshold() {
            assertEquals(30, CuratorJob.STALE_THRESHOLD_DAYS);
        }

        @Test
        @DisplayName("ARCHIVE 阈值 = 90 天")
        void archiveThreshold() {
            assertEquals(90, CuratorJob.ARCHIVE_THRESHOLD_DAYS);
        }
    }
}
