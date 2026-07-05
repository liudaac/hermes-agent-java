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

        @Test
        @DisplayName("未注册的 bundle → empty")
        void notRegistered() {
            assertTrue(service.getBundle("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("listBundles 返回所有")
        void listAll() {
            service.registerBundle("a", "desc a", List.of("s1"));
            service.registerBundle("b", "desc b", List.of("s2", "s3"));
            assertEquals(2, service.listBundles().size());
        }

        @Test
        @DisplayName("removeBundle")
        void remove() {
            service.registerBundle("temp", "temp", List.of("s1"));
            assertTrue(service.removeBundle("temp"));
            assertTrue(service.getBundle("temp").isEmpty());
        }

        @Test
        @DisplayName("removeBundle 不存在的 → false")
        void removeNotFound() {
            assertFalse(service.removeBundle("nonexistent"));
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

        @Test
        @DisplayName("部分 skill 不存在")
        void partialLoad() {
            service.registerBundle("mixed", "混合包", List.of("exists", "missing"));
            org.mockito.Mockito.when(skillManager.listSkills())
                .thenReturn(List.of(createSkill("exists")));

            SkillBundleService.BundleLoadResult result = service.load("mixed");
            assertEquals(1, result.loadedSkills().size());
            assertEquals(1, result.failedSkills().size());
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("bundle 不存在")
        void bundleNotFound() {
            SkillBundleService.BundleLoadResult result = service.load("nonexistent");
            assertFalse(result.bundleFound());
            assertNotNull(result.errorMessage());
        }

        @Test
        @DisplayName("空 bundle 加载成功")
        void emptyBundle() {
            service.registerBundle("empty", "空包", List.of());
            SkillBundleService.BundleLoadResult result = service.load("empty");
            assertTrue(result.bundleFound());
            assertTrue(result.loadedSkills().isEmpty());
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

        @Test
        @DisplayName("无冲突 → 空_map")
        void noConflicts() {
            service.registerBundle("a", "包A", List.of("s1"));
            service.registerBundle("b", "包B", List.of("s2"));
            assertTrue(service.findConflicts().isEmpty());
        }

        @Test
        @DisplayName("无 bundle → 空_map")
        void noBundles() {
            assertTrue(service.findConflicts().isEmpty());
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

        @Test
        @DisplayName("skills 不可变")
        void skillsImmutable() {
            SkillBundleService.SkillBundle b = new SkillBundleService.SkillBundle(
                "test", "desc", List.of("s1"));
            assertThrows(UnsupportedOperationException.class, () -> b.skills().add("s2"));
        }
    }

    // ========================================================================
    // BundleLoadResult
    // ========================================================================

    @Nested
    @DisplayName("BundleLoadResult")
    class ResultTest {

        @Test
        @DisplayName("isSuccess = true 当所有 skill 加载成功")
        void success() {
            SkillBundleService.BundleLoadResult r = new SkillBundleService.BundleLoadResult(
                "bundle", true, List.of("s1", "s2"), List.of(), null);
            assertTrue(r.isSuccess());
        }

        @Test
        @DisplayName("isSuccess = false 当有失败")
        void hasFailure() {
            SkillBundleService.BundleLoadResult r = new SkillBundleService.BundleLoadResult(
                "bundle", true, List.of("s1"), List.of("s2 (not found)"), null);
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("isSuccess = false 当 bundle 不存在")
        void notFound() {
            SkillBundleService.BundleLoadResult r = new SkillBundleService.BundleLoadResult(
                "bundle", false, List.of(), List.of(), "not found");
            assertFalse(r.isSuccess());
        }
    }
}
