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

        @Test
        @DisplayName("StepResult 成功")
        void stepResultSuccess() {
            PreVerifyHook.StepResult r = new PreVerifyHook.StepResult("compile", true, null, "BUILD SUCCESS", 500);
            assertTrue(r.success());
            assertNull(r.error());
            assertEquals(500, r.durationMs());
        }

        @Test
        @DisplayName("StepResult 失败")
        void stepResultFailure() {
            PreVerifyHook.StepResult r = new PreVerifyHook.StepResult("test", false, "Exit code: 1", "FAIL", 1000);
            assertFalse(r.success());
            assertEquals("Exit code: 1", r.error());
        }

        @Test
        @DisplayName("VerifyResult passed=true")
        void verifyResultPassed() {
            PreVerifyHook.VerifyResult r = new PreVerifyHook.VerifyResult(true, List.of(), "All passed");
            assertTrue(r.passed());
            assertFalse(r.shouldBlock());
        }

        @Test
        @DisplayName("VerifyResult passed=false → shouldBlock=true")
        void verifyResultBlocked() {
            PreVerifyHook.VerifyResult r = new PreVerifyHook.VerifyResult(false, List.of(), "Failed");
            assertFalse(r.passed());
            assertTrue(r.shouldBlock());
        }

        @Test
        @DisplayName("VerifyResult.skipped → passed=true")
        void verifyResultSkipped() {
            PreVerifyHook.VerifyResult r = PreVerifyHook.VerifyResult.skipped("disabled");
            assertTrue(r.passed());
            assertFalse(r.shouldBlock());
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

        @Test
        @DisplayName("forTypeScriptProject 创建正确的 steps")
        void forTypeScriptProject() {
            PreVerifyHook hook = PreVerifyHook.forTypeScriptProject(Path.of("/tmp"));
            assertNotNull(hook);
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

        @Test
        @DisplayName("false 命令失败 → 阻塞")
        void falseCommandFails() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(new PreVerifyHook.VerifyStep("fail", "false", true)),
                true, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertFalse(result.passed());
            assertTrue(result.shouldBlock());
        }

        @Test
        @DisplayName("阻塞步骤失败 → 后续步骤不执行")
        void blockingStopsChain() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(
                    new PreVerifyHook.VerifyStep("fail", "false", true),
                    new PreVerifyHook.VerifyStep("never-run", "echo hello", true)
                ),
                true, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertFalse(result.passed());
            assertEquals(1, result.steps().size()); // 只有第一个步骤的结果
        }

        @Test
        @DisplayName("非阻塞步骤失败 → 继续执行")
        void nonBlockingContinues() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(
                    new PreVerifyHook.VerifyStep("fail", "false", false),
                    new PreVerifyHook.VerifyStep("ok", "echo hello", true)
                ),
                true, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertEquals(2, result.steps().size());
            assertFalse(result.steps().get(0).success());
            assertTrue(result.steps().get(1).success());
        }

        @Test
        @DisplayName("全部成功 → passed=true")
        void allPass() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(
                    new PreVerifyHook.VerifyStep("a", "echo a", true),
                    new PreVerifyHook.VerifyStep("b", "echo b", false)
                ),
                true, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertTrue(result.passed());
            assertEquals(2, result.steps().size());
        }

        @Test
        @DisplayName("超时 → 失败")
        void timeout() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(new PreVerifyHook.VerifyStep("sleep", "sleep 10", true)),
                true, 1);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertFalse(result.passed());
            assertTrue(result.steps().get(0).error().contains("Timeout"));
        }

        @Test
        @DisplayName("durationMs 记录")
        void durationRecorded() {
            PreVerifyHook hook = new PreVerifyHook(
                Path.of("/tmp"),
                List.of(new PreVerifyHook.VerifyStep("sleep", "sleep 0.1", true)),
                true, 10);
            PreVerifyHook.VerifyResult result = hook.verify();
            assertTrue(result.steps().get(0).durationMs() >= 50);
        }
    }
}
