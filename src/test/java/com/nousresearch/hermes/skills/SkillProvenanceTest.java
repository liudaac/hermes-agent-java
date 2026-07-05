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
 * S5-1: SkillProvenanceService 测试
 */
class SkillProvenanceTest {

    private SkillManager skillManager;
    private SkillProvenanceService service;

    @BeforeEach
    void setUp() {
        skillManager = org.mockito.Mockito.mock(SkillManager.class);
        service = new SkillProvenanceService(skillManager);
    }

    private SkillManager.Skill createSkill(String name, SkillProvenance provenance, int usage, Instant lastUsed) {
        SkillManager.Skill s = new SkillManager.Skill();
        s.name = name;
        s.provenance = provenance;
        s.usageCount = usage;
        s.lastUsedAt = lastUsed;
        s.lifecycleStatus = SkillLifecycleStatus.ACTIVE;
        return s;
    }

    // ========================================================================
    // SkillProvenance 枚举
    // ========================================================================

    @Nested
    @DisplayName("SkillProvenance")
    class ProvenanceEnumTest {

        @Test
        @DisplayName("4 种来源")
        void values() {
            assertEquals(4, SkillProvenance.values().length);
            assertEquals(SkillProvenance.USER, SkillProvenance.valueOf("USER"));
            assertEquals(SkillProvenance.AGENT, SkillProvenance.valueOf("AGENT"));
            assertEquals(SkillProvenance.IMPORT, SkillProvenance.valueOf("IMPORT"));
            assertEquals(SkillProvenance.BUNDLED, SkillProvenance.valueOf("BUNDLED"));
        }

        @Test
        @DisplayName("fromString 大小写不敏感")
        void fromString() {
            assertEquals(SkillProvenance.USER, SkillProvenance.fromString("user"));
            assertEquals(SkillProvenance.AGENT, SkillProvenance.fromString("AGENT"));
            assertEquals(SkillProvenance.IMPORT, SkillProvenance.fromString("Import"));
            assertEquals(SkillProvenance.BUNDLED, SkillProvenance.fromString("BUNDLED"));
        }

        @Test
        @DisplayName("fromString null → USER")
        void fromStringNull() {
            assertEquals(SkillProvenance.USER, SkillProvenance.fromString(null));
        }

        @Test
        @DisplayName("fromString 未知 → USER")
        void fromStringUnknown() {
            assertEquals(SkillProvenance.USER, SkillProvenance.fromString("unknown"));
        }
    }

    // ========================================================================
    // SkillLifecycleStatus
    // ========================================================================

    @Nested
    @DisplayName("SkillLifecycleStatus")
    class LifecycleStatusTest {

        @Test
        @DisplayName("3 种状态")
        void values() {
            assertEquals(3, SkillLifecycleStatus.values().length);
        }
    }

    // ========================================================================
    // setProvenance / getProvenance
    // ========================================================================

    @Nested
    @DisplayName("setProvenance / getProvenance")
    class SetGetProvenanceTest {

        @Test
        @DisplayName("设置 + 获取 provenance")
        void setAndGet() {
            SkillManager.Skill skill = createSkill("search", SkillProvenance.USER, 0, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            assertTrue(service.setProvenance("search", SkillProvenance.AGENT));
            assertEquals(SkillProvenance.AGENT, service.getProvenance("search"));
        }

        @Test
        @DisplayName("不存在的 skill → false")
        void notFound() {
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of());
            assertFalse(service.setProvenance("nonexistent", SkillProvenance.AGENT));
            assertNull(service.getProvenance("nonexistent"));
        }
    }

    // ========================================================================
    // recordUsage
    // ========================================================================

    @Nested
    @DisplayName("recordUsage")
    class RecordUsageTest {

        @Test
        @DisplayName("使用次数 +1 + lastUsedAt 更新")
        void incrementsUsage() {
            SkillManager.Skill skill = createSkill("search", SkillProvenance.AGENT, 5, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            service.recordUsage("search");
            assertEquals(6, skill.usageCount);
            assertNotNull(skill.lastUsedAt);
        }

        @Test
        @DisplayName("ARCHIVED 状态使用时自动恢复为 ACTIVE")
        void autoRestoreFromArchived() {
            SkillManager.Skill skill = createSkill("old", SkillProvenance.AGENT, 0, null);
            skill.lifecycleStatus = SkillLifecycleStatus.ARCHIVED;
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            service.recordUsage("old");
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
        }

        @Test
        @DisplayName("不存在的 skill 不崩溃")
        void notFound() {
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of());
            assertDoesNotThrow(() -> service.recordUsage("nonexistent"));
        }
    }

    // ========================================================================
    // getByProvenance
    // ========================================================================

    @Nested
    @DisplayName("getByProvenance")
    class GetByProvenanceTest {

        @Test
        @DisplayName("按来源筛选")
        void filter() {
            SkillManager.Skill s1 = createSkill("a", SkillProvenance.AGENT, 1, null);
            SkillManager.Skill s2 = createSkill("b", SkillProvenance.USER, 2, null);
            SkillManager.Skill s3 = createSkill("c", SkillProvenance.AGENT, 3, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(s1, s2, s3));

            List<SkillManager.Skill> agentSkills = service.getByProvenance(SkillProvenance.AGENT);
            assertEquals(2, agentSkills.size());
            assertTrue(agentSkills.stream().allMatch(s -> s.provenance == SkillProvenance.AGENT));
        }
    }

    // ========================================================================
    // createAgentSkill
    // ========================================================================

    @Nested
    @DisplayName("createAgentSkill")
    class CreateAgentSkillTest {

        @Test
        @DisplayName("创建 agent skill，provenance=AGENT")
        void create() {
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of());

            SkillManager.Skill skill = service.createAgentSkill("new-skill", "desc", "content", List.of("tag1"));
            assertNotNull(skill);
            assertEquals("new-skill", skill.name);
            assertEquals(SkillProvenance.AGENT, skill.provenance);
            assertEquals(SkillLifecycleStatus.ACTIVE, skill.lifecycleStatus);
            assertFalse(skill.pinned);
            assertNotNull(skill.createdAt);
            assertNotNull(skill.lastUsedAt);
        }

        @Test
        @DisplayName("已存在 → null")
        void alreadyExists() {
            SkillManager.Skill existing = createSkill("existing", SkillProvenance.USER, 0, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(existing));

            SkillManager.Skill skill = service.createAgentSkill("existing", "desc", "content", null);
            assertNull(skill);
        }
    }

    // ========================================================================
    // pin / unpin
    // ========================================================================

    @Nested
    @DisplayName("pin / unpin")
    class PinTest {

        @Test
        @DisplayName("pin 设置 pinned=true")
        void pin() {
            SkillManager.Skill skill = createSkill("s", SkillProvenance.AGENT, 0, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            assertTrue(service.pin("s"));
            assertTrue(skill.pinned);
        }

        @Test
        @DisplayName("unpin 设置 pinned=false")
        void unpin() {
            SkillManager.Skill skill = createSkill("s", SkillProvenance.AGENT, 0, null);
            skill.pinned = true;
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(skill));

            assertTrue(service.unpin("s"));
            assertFalse(skill.pinned);
        }

        @Test
        @DisplayName("不存在的 skill → false")
        void notFound() {
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of());
            assertFalse(service.pin("nonexistent"));
            assertFalse(service.unpin("nonexistent"));
        }
    }

    // ========================================================================
    // getStatistics
    // ========================================================================

    @Nested
    @DisplayName("getStatistics")
    class StatisticsTest {

        @Test
        @DisplayName("空列表 → 全零")
        void empty() {
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of());
            SkillProvenanceService.SkillStatistics stats = service.getStatistics();
            assertEquals(0, stats.totalSkills());
            assertEquals(0, stats.agentCreated());
            assertEquals(0, stats.totalUsageCount());
        }

        @Test
        @DisplayName("正确统计各维度")
        void mixed() {
            SkillManager.Skill s1 = createSkill("a", SkillProvenance.AGENT, 10, null);
            s1.pinned = true;
            SkillManager.Skill s2 = createSkill("b", SkillProvenance.USER, 5, null);
            SkillManager.Skill s3 = createSkill("c", SkillProvenance.IMPORT, 3, null);
            s3.lifecycleStatus = SkillLifecycleStatus.STALE;
            SkillManager.Skill s4 = createSkill("d", SkillProvenance.BUNDLED, 0, null);
            s4.lifecycleStatus = SkillLifecycleStatus.ARCHIVED;
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(s1, s2, s3, s4));

            SkillProvenanceService.SkillStatistics stats = service.getStatistics();
            assertEquals(4, stats.totalSkills());
            assertEquals(1, stats.agentCreated());
            assertEquals(1, stats.userCreated());
            assertEquals(1, stats.imported());
            assertEquals(1, stats.bundled());
            assertEquals(1, stats.pinned());
            assertEquals(2, stats.active());   // s1 + s2
            assertEquals(1, stats.stale());     // s3
            assertEquals(1, stats.archived());  // s4
            assertEquals(18, stats.totalUsageCount());
        }

        @Test
        @DisplayName("avgUsagePerSkill")
        void avgUsage() {
            SkillManager.Skill s1 = createSkill("a", SkillProvenance.AGENT, 10, null);
            SkillManager.Skill s2 = createSkill("b", SkillProvenance.USER, 20, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(s1, s2));

            SkillProvenanceService.SkillStatistics stats = service.getStatistics();
            assertEquals(15.0, stats.avgUsagePerSkill(), 0.01);
        }
    }

    // ========================================================================
    // getTopUsed / getRecentlyUsed
    // ========================================================================

    @Nested
    @DisplayName("getTopUsed / getRecentlyUsed")
    class TopAndRecentTest {

        @Test
        @DisplayName("getTopUsed 按使用次数降序")
        void topUsed() {
            SkillManager.Skill s1 = createSkill("a", SkillProvenance.AGENT, 30, null);
            SkillManager.Skill s2 = createSkill("b", SkillProvenance.USER, 10, null);
            SkillManager.Skill s3 = createSkill("c", SkillProvenance.AGENT, 50, null);
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(s1, s2, s3));

            List<SkillManager.Skill> top = service.getTopUsed(2);
            assertEquals(2, top.size());
            assertEquals("c", top.get(0).name); // 50
            assertEquals("a", top.get(1).name); // 30
        }

        @Test
        @DisplayName("getRecentlyUsed 按最近使用降序")
        void recentlyUsed() {
            Instant now = Instant.now();
            SkillManager.Skill s1 = createSkill("a", SkillProvenance.AGENT, 1, now.minus(1, ChronoUnit.DAYS));
            SkillManager.Skill s2 = createSkill("b", SkillProvenance.USER, 1, now.minus(1, ChronoUnit.HOURS));
            SkillManager.Skill s3 = createSkill("c", SkillProvenance.AGENT, 1, null); // 没用过
            org.mockito.Mockito.when(skillManager.listSkills()).thenReturn(List.of(s1, s2, s3));

            List<SkillManager.Skill> recent = service.getRecentlyUsed(5);
            assertEquals(2, recent.size()); // s3 没有 lastUsedAt 被过滤
            assertEquals("b", recent.get(0).name); // 1 小时前
            assertEquals("a", recent.get(1).name); // 1 天前
        }
    }

    // ========================================================================
    // Skill 新字段默认值
    // ========================================================================

    @Nested
    @DisplayName("Skill 新字段默认值")
    class SkillDefaultsTest {

        @Test
        @DisplayName("新建 Skill 默认值正确")
        void defaults() {
            SkillManager.Skill s = new SkillManager.Skill();
            assertEquals(SkillProvenance.USER, s.provenance);
            assertNull(s.lastUsedAt);
            assertFalse(s.pinned);
            assertEquals(SkillLifecycleStatus.ACTIVE, s.lifecycleStatus);
        }
    }
}
