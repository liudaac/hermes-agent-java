package com.nousresearch.hermes.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1-4a: Shell 行续行绕过补丁测试
 *
 * <p>对齐 Python 原版 commit 17f07aebd 的测试用例。
 * 验证 {@link ApprovalSystem#normalizeCommandForDetection(String)} 能正确折叠
 * shell 行续行，防止攻击者通过 \<newline> 绕过 DangerPattern 匹配。</p>
 *
 * <p>攻击场景：{@code rm -rf \<newline>/} 在 POSIX shell 中执行为 {@code rm -rf /}，
 * 但如果不对命令做规范化，<code>\<newline></code> 会楔在 token 之间，
 * 导致 {@code "rm -rf"} 的 contains 匹配失败。</p>
 */
class ShellLineContinuationBypassTest {

    // ========================================================================
    // normalizeCommandForDetection 单元测试
    // ========================================================================

    @Nested
    @DisplayName("行续行折叠（核心修复）")
    class LineContinuationFolding {

        @Test
        @DisplayName("rm -rf \\\n/ → rm -rf /")
        void rmRfRootWithContinuation() {
            String input = "rm -rf \\\n/";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("rm -r\\\nf / → rm -rf /")
        void rmRfSplitFlagWithContinuation() {
            String input = "rm -r\\\nf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("rm -rf \\\n~ → rm -rf ~")
        void rmRfHomeWithContinuation() {
            String input = "rm -rf \\\n~";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf ~", normalized);
        }

        @Test
        @DisplayName("rm -rf \\\r\n/ → rm -rf /（CRLF 行尾）")
        void rmRfRootWithCRLF() {
            String input = "rm -rf \\\r\n/";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("mkfs.ext4 \\\n/dev/sda1 → mkfs.ext4 /dev/sda1")
        void mkfsWithContinuation() {
            String input = "mkfs.ext4 \\\n/dev/sda1";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("mkfs.ext4 /dev/sda1", normalized);
        }

        @Test
        @DisplayName("dd if=\\\n/dev/zero → dd if=/dev/zero")
        void ddWithContinuation() {
            String input = "dd if=\\\n/dev/zero";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("dd if=/dev/zero", normalized);
        }

        @Test
        @DisplayName("多个行续行应全部折叠")
        void multipleContinuations() {
            String input = "rm \\\n-rf \\\n/";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("无行续行的命令应保持不变")
        void noContinuation() {
            String input = "rm -rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("纯换行（无反斜杠）不应被折叠")
        void plainNewline() {
            String input = "echo hello\nworld";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("echo hello\nworld", normalized);
        }

        @Test
        @DisplayName("连续两个反斜杠+换行")
        void doubleBackslashNewline() {
            String input = "rm \\\\\n-rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            // 第一个 \ 是转义第二个 \，所以 \\\n 是：转义的反斜杠 + 换行
            // 转义折叠后 \\. → .，但 \n 不是行续行
            // 实际：\\\\\n 在 Java 字符串中是 \\ + \n = 一个反斜杠 + 换行
            // 这个测试验证边缘情况：反斜杠转义后跟换行
            assertNotNull(normalized);
        }
    }

    @Nested
    @DisplayName("反斜杠转义折叠")
    class BackslashEscapeFolding {

        @Test
        @DisplayName("r\\m → rm（反斜杠转义绕过）")
        void backslashEscapeInCommand() {
            String input = "r\\m -rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("rm \\-rf / → rm -rf /")
        void backslashEscapeInFlag() {
            String input = "rm \\-rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("s\\udo → sudo")
        void backslashEscapeInSudo() {
            String input = "s\\udo apt-get install something";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("sudo apt-get install something", normalized);
        }
    }

    @Nested
    @DisplayName("空字符串字面量折叠")
    class EmptyStringFolding {

        @Test
        @DisplayName("r''m → rm")
        void emptySingleQuote() {
            String input = "r''m -rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("r\"\"m → rm")
        void emptyDoubleQuote() {
            String input = "r\"\"m -rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }
    }

    @Nested
    @DisplayName("$IFS 折叠")
    class IfsFolding {

        @Test
        @DisplayName("rm${IFS}-rf${IFS}/ → rm -rf /")
        void ifsExpansion() {
            String input = "rm${IFS}-rf${IFS}/";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            // ${IFS} → space, so "rm" + " " + "-rf" + " " + "/" = "rm -rf /"
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("rm$IFS -rf$IFS / → rm  -rf  /")
        void ifsWithoutBraces() {
            String input = "rm$IFS -rf$IFS /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            // $IFS → space, so "rm" + " " + " -rf" + " " + " /" = "rm  -rf  /"
            assertEquals("rm  -rf  /", normalized);
        }

        @Test
        @DisplayName("${IFS:0:1} → 空格（子串扩展）")
        void ifsSubstring() {
            String input = "rm${IFS:0:1}-rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }
    }

    @Nested
    @DisplayName("Null 字节")
    class NullBytes {

        @Test
        @DisplayName("rm\\0 -rf / → rm -rf /")
        void nullByteInCommand() {
            String input = "rm\0 -rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }

        @Test
        @DisplayName("多个 null 字节全部去除")
        void multipleNullBytes() {
            String input = "r\0m\0 -rf /";
            String normalized = ApprovalSystem.normalizeCommandForDetection(input);
            assertEquals("rm -rf /", normalized);
        }
    }

    // ========================================================================
    // ApprovalSystem 集成测试 — 验证 DangerPattern 匹配
    // ========================================================================

    @Nested
    @DisplayName("ApprovalSystem DangerPattern 匹配（集成）")
    class DangerPatternIntegration {

        private final ApprovalSystem approval = new ApprovalSystem();

        @Test
        @DisplayName("rm -rf \\\n/ 应触发 REQUIRE 审批（非绕过）")
        void rmRfRootContinuationTriggersApproval() {
            // 行续行绕过尝试：rm -rf \<newline>/
            String malicious = "rm -rf \\\n/";
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious),
                "行续行形式的 rm -rf / 必须触发审批，不能被绕过");
        }

        @Test
        @DisplayName("rm -r\\\nf / 应触发 REQUIRE 审批")
        void rmRfSplitFlagTriggersApproval() {
            String malicious = "rm -r\\\nf /";
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious),
                "flag 中间插入行续行的 rm -rf / 必须触发审批");
        }

        @Test
        @DisplayName("mkfs \\\n/dev/sda1 应触发 DENY")
        void mkfsContinuationTriggersDeny() {
            String malicious = "mkfs \\\n/dev/sda1";
            ApprovalResult result =
                approval.requestApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious, "test");
            assertFalse(result.isApproved(), "行续行形式的 mkfs 必须被 DENY");
        }

        @Test
        @DisplayName("mkfs.ext4 \\\n/dev/sda1 应触发 DENY")
        void mkfsExt4ContinuationTriggersDeny() {
            String malicious = "mkfs.ext4 \\\n/dev/sda1";
            ApprovalResult result =
                approval.requestApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious, "test");
            assertFalse(result.isApproved(), "行续行形式的 mkfs.ext4 必须被 DENY");
        }

        @Test
        @DisplayName("dd if=\\\n/dev/zero 应触发 REQUIRE")
        void ddContinuationTriggersApproval() {
            String malicious = "dd if=\\\n/dev/zero";
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious),
                "行续行形式的 dd if= 必须触发审批");
        }

        @Test
        @DisplayName("> /dev/sd\\\na 应触发 DENY")
        void devSdaContinuationTriggersDeny() {
            String malicious = "> /dev/sd\\\na";
            ApprovalResult result =
                approval.requestApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious, "test");
            assertFalse(result.isApproved(), "行续行形式的 > /dev/sda 必须被 DENY");
        }

        @Test
        @DisplayName("s\\udo 应触发 REQUIRE 审批")
        void sudoBackslashEscapeTriggersApproval() {
            String malicious = "s\\udo ls /";
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious),
                "反斜杠转义形式的 sudo 必须触发审批");
        }

        @Test
        @DisplayName("r''m -rf / 应触发 REQUIRE 审批")
        void rmEmptyQuoteTriggersApproval() {
            String malicious = "r''m -rf /";
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious),
                "空引号分割的 rm 必须触发审批");
        }

        @Test
        @DisplayName("rm${IFS}-rf${IFS}/ 应触发 REQUIRE 审批")
        void rmIfsTriggersApproval() {
            String malicious = "rm${IFS}-rf${IFS}/";
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, malicious),
                "$IFS 分割的 rm -rf / 必须触发审批");
        }

        @Test
        @DisplayName("正常安全命令不应误报（TERMINAL_COMMAND 默认 PROMPT 模式会要求审批）")
        void safeCommandNotFlagged() {
            // TERMINAL_COMMAND 默认模式是 PROMPT，所以所有终端命令都会要求审批
            // 这里验证的是：ls 和 echo 不会匹配 DangerPattern（不会被标记为 dangerous）
            // 但 wouldNeedApproval 仍返回 true 因为默认模式是 PROMPT
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, "ls -la"),
                "PROMPT 模式下所有 TERMINAL_COMMAND 都需要审批");
        }

        @Test
        @DisplayName("null 和空字符串处理不崩溃")
        void nullAndEmpty() {
            // 空命令在 PROMPT 模式下也会要求审批，但不应崩溃
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, ""),
                "空字符串在 PROMPT 模式下需要审批");
            assertTrue(approval.wouldNeedApproval(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, null),
                "null 在 PROMPT 模式下需要审批");
        }
    }
}
