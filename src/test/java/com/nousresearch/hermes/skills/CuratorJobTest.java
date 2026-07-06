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
        // Mock getSearchPaths so CuratorJob constructor doesn't fail
        java.nio.file.Path tmpDir = java.nio.file.Paths.get(
            System.getProperty("java.io.tmpdir"), "hermes-test-skills");
        org.mockito.Mockito.when(skillManager.getSearchPaths())
            .thenReturn(java.util.List.of(tmpDir));
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

            CuratorRunReport report = curator.run();
            assertEquals(1, report.autoChecked);
            assertEquals(1, report.autoMarkedStale);
            assertEquals(SkillLifecycleStatus.STALE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("超过 90 天未使用 → ARCHIVED")
        void archive() {
            SkillManager.Skill skill = createSkill("ancient-skill",
                Instant.now().minus(100, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorRunReport report = curator.run();
            assertEquals(1, report.autoArchived);
            assertEquals(SkillLifecycleStatus.ARCHIVED, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("最近使用过的 → 保持 ACTIVE")
        void stayActive() {
            SkillManager.Skill skill = createSkill("active-skill",
                Instant.now().minus(5, ChronoUnit.DAYS), false, SkillLifecycleStatus.ACTIVE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorRunReport report = curator.run();
            assertEquals(0, report.autoMarkedStale);
            assertEquals(0, report.autoArchived);
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("Pinned skill 跳过所有转换")
        void pinnedSkipped() {
            SkillManager.Skill skill = createSkill("pinned-skill",
                Instant.now().minus(200, ChronoUnit.DAYS), true, SkillLifecycleStatus.ACTIVE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorRunReport report = curator.run();
            // pinned skills are skipped — not counted in auto transitions
            assertEquals(0, report.autoMarkedStale);
            assertEquals(0, report.autoArchived);
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("STALE skill 最近又用了 → 恢复 ACTIVE")
        void restoreFromStale() {
            SkillManager.Skill skill = createSkill("revived-skill",
                Instant.now().minus(2, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorRunReport report = curator.run();
            assertEquals(1, report.autoReactivated);
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("已是 ARCHIVED 的不重复归档")
        void alreadyArchived() {
            SkillManager.Skill skill = createSkill("already-archived",
                Instant.now().minus(200, ChronoUnit.DAYS), false, SkillLifecycleStatus.ARCHIVED);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorRunReport report = curator.run();
            assertEquals(0, report.autoArchived); // 不重复计数
        }

        @Test
        @DisplayName("已是 STALE 的不重复标记")
        void alreadyStale() {
            SkillManager.Skill skill = createSkill("already-stale",
                Instant.now().minus(45, ChronoUnit.DAYS), false, SkillLifecycleStatus.STALE);
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of(skill));

            CuratorRunReport report = curator.run();
            assertEquals(0, report.autoMarkedStale); // 不重复计数
        }
    }

    // ========================================================================
    // 合并候选 — Layer 1 只做确定性转换，合并候选在 Layer 2 (LLM)
    // ========================================================================

    @Nested
    @DisplayName("空数据处理")
    class EmptyDataTest {

        @Test
        @DisplayName("无 agent skill → 空报告")
        void noAgentSkills() {
            org.mockito.Mockito.when(provenanceService.getByProvenance(SkillProvenance.AGENT))
                .thenReturn(List.of());

            CuratorRunReport report = curator.run();
            assertEquals(0, report.autoChecked);
            assertEquals(0, report.autoMarkedStale);
            assertEquals(0, report.autoArchived);
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

            CuratorRunReport report = curator.run();
            assertEquals(5, report.autoChecked);
            assertEquals(1, report.autoMarkedStale);
            assertEquals(1, report.autoArchived);
            assertEquals(1, report.autoReactivated);
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
            org.mockito.Mockito.when(skillManager.restoreSkill("archived")).thenReturn(true);

            assertTrue(curator.restore("archived"));
        }

        @Test
        @DisplayName("恢复不存在的 skill → false")
        void notFound() {
            org.mockito.Mockito.when(skillManager.restoreSkill("nonexistent")).thenReturn(false);
            assertFalse(curator.restore("nonexistent"));
        }
    }

    // ========================================================================
    // CuratorRunReport
    // ========================================================================

    @Nested
    @DisplayName("CuratorRunReport")
    class ReportTest {

        @Test
        @DisplayName("toString 包含关键字段")
        void toString_() {
            CuratorRunReport report = new CuratorRunReport();
            report.autoChecked = 10;
            report.autoMarkedStale = 3;
            report.autoArchived = 1;
            String s = report.toString();
            assertTrue(s.contains("checked=10"));
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
