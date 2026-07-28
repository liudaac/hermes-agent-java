package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S5-4: SkillBundleService 测试
 */
class SkillBundleTest {

    private SkillManager skillManager;
    private SkillBundleService service;

    @BeforeEach
    void setUp() {
        skillManager = org.mockito.Mockito.mock(SkillManager.class);
        service = new SkillBundleService(skillManager);
    }

    private SkillManager.Skill createSkill(String name) {
        SkillManager.Skill s = new SkillManager.Skill();
        s.name = name;
        return s;
    }

    // ========================================================================
    // registerBundle / getBundle / listBundles / removeBundle
    // ========================================================================

    @Nested
    @DisplayName("Bundle CRUD")
    class CrudTest {

        @Test
        @DisplayName("register + get")
        void registerAndGet() {
            service.registerBundle("coding", "编程包", List.of("code-review", "test-gen"));
            assertTrue(service.getBundle("coding").isPresent());
            assertTrue(service.getBundle("CODING").isPresent()); // 大小写不敏感
            assertEquals(2, service.getBundle("coding").get().skills().size());
        }

    }

    // ========================================================================
    // load
    // ========================================================================

    @Nested
    @DisplayName("load")
    class LoadTest {

        @Test
        @DisplayName("成功加载所有 skill")
        void loadAllSuccess() {
            service.registerBundle("coding", "编程包", List.of("code-review", "test-gen"));
            org.mockito.Mockito.when(skillManager.listSkills())
                .thenReturn(List.of(createSkill("code-review"), createSkill("test-gen")));

            SkillBundleService.BundleLoadResult result = service.load("coding");
            assertTrue(result.bundleFound());
            assertEquals(2, result.loadedSkills().size());
            assertTrue(result.failedSkills().isEmpty());
            assertTrue(result.isSuccess());
        }

    }

    // ========================================================================
    // findConflicts
    // ========================================================================

    @Nested
    @DisplayName("findConflicts")
    class ConflictTest {

        @Test
        @DisplayName("同一 skill 在多个 bundle 中 → 冲突")
        void conflictDetected() {
            service.registerBundle("a", "包A", List.of("shared", "a1"));
            service.registerBundle("b", "包B", List.of("shared", "b1"));

            Map<String, List<String>> conflicts = service.findConflicts();
            assertEquals(1, conflicts.size());
            assertTrue(conflicts.containsKey("shared"));
            assertEquals(2, conflicts.get("shared").size());
        }

    }

    // ========================================================================
    // SkillBundle 值对象
    // ========================================================================

    @Nested
    @DisplayName("SkillBundle 值对象")
    class BundleValueTest {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            SkillBundleService.SkillBundle b = new SkillBundleService.SkillBundle(
                "coding", "编程包", List.of("s1", "s2"));
            assertEquals("coding", b.name());
            assertEquals("编程包", b.description());
            assertEquals(2, b.skills().size());
        }

    }

    // ========================================================================
    // BundleLoadResult
    // ========================================================================

    @Nested
    @DisplayName("BundleLoadResult")
    class ResultTest {

    }
}
