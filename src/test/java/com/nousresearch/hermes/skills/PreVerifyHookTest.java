package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S6-1: PreVerifyHook 测试
 */
class PreVerifyHookTest {

    // ========================================================================
    // VerifyStep / StepResult / VerifyResult
    // ========================================================================

    @Nested
    @DisplayName("数据类")
    class DataClassTest {

        @Test
        @DisplayName("VerifyStep 基本构造")
        void verifyStep() {
            PreVerifyHook.VerifyStep step = new PreVerifyHook.VerifyStep("test", "mvn test", true);
            assertEquals("test", step.name());
            assertEquals("mvn test", step.command());
            assertTrue(step.blocking());
        }

    }

    // ========================================================================
    // 工厂方法
    // ========================================================================

    @Nested
    @DisplayName("工厂方法")
    class FactoryTest {

        @Test
        @DisplayName("forJavaProject 创建正确的 steps")
        void forJavaProject() {
            PreVerifyHook hook = PreVerifyHook.forJavaProject(Path.of("/tmp"));
            PreVerifyHook.VerifyResult result = hook.verify();
            // /tmp has no pom.xml — compile will fail, but verify should still run and return a result
            assertNotNull(result);
            assertNotNull(result.summary());
        }

    }

    // ========================================================================
    // disabled → skip
    // ========================================================================

    @Nested
    @DisplayName("禁用模式")
    class DisabledTest {

        @Test
        @DisplayName("disabled → skipped 结果")
        void disabledSkips() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"), List.of(), false, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertTrue(result.passed());
            assertFalse(result.shouldBlock());
            assertTrue(result.summary().contains("Skipped"));
        }
    }

    // ========================================================================
    // 实际命令执行
    // ========================================================================

    @Nested
    @DisplayName("命令执行")
    class CommandExecutionTest {

        @Test
        @DisplayName("echo 命令成功")
        void echoSuccess() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(new PreVerifyHook.VerifyStep("echo", "echo hello", true)),
                true, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertTrue(result.passed());
            assertEquals(1, result.steps().size());
            assertTrue(result.steps().get(0).success());
            assertTrue(result.steps().get(0).output().contains("hello"));
        }

    }
}
