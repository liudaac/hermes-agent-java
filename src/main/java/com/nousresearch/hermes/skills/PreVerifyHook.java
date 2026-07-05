package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * S6-1: Pre-verify Hook — agent 停机前自动跑 verify（测试 / typecheck）。
 *
 * <p>对齐原版 agent/verify_hooks.py + verification_evidence.py。</p>
 *
 * <p>工作流：</p>
 * <ol>
 *   <li>Agent 完成任务准备停机</li>
 *   <li>Pre-verify hook 触发</li>
 *   <li>运行配置的 verify 命令（如 mvn test / tsc --noEmit）</li>
 *   <li>如果失败 → 阻止停机，返回错误让 agent 继续</li>
 *   <li>如果成功 → 允许停机</li>
 * </ol>
 */
public class PreVerifyHook {
    private static final Logger logger = LoggerFactory.getLogger(PreVerifyHook.class);

    private final Path workspaceDir;
    private final List<VerifyStep> steps;
    private final boolean enabled;
    private final long timeoutSeconds;

    public PreVerifyHook(Path workspaceDir, List<VerifyStep> steps, boolean enabled, long timeoutSeconds) {
        this.workspaceDir = workspaceDir;
        this.steps = steps != null ? List.copyOf(steps) : List.of();
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 默认配置（Java 项目）。
     */
    public static PreVerifyHook forJavaProject(Path workspaceDir) {
        return new PreVerifyHook(
            workspaceDir,
            List.of(
                new VerifyStep("compile", "mvn compile -q", true),
                new VerifyStep("test", "mvn test -q", false)
            ),
            true,
            120
        );
    }

    /**
     * 默认配置（TypeScript 项目）。
     */
    public static PreVerifyHook forTypeScriptProject(Path workspaceDir) {
        return new PreVerifyHook(
            workspaceDir,
            List.of(
                new VerifyStep("typecheck", "npx tsc --noEmit", true)
            ),
            true,
            60
        );
    }

    /**
     * 执行所有 verify 步骤。
     *
     * @return 验证结果
     */
    public VerifyResult verify() {
        if (!enabled) {
            return VerifyResult.skipped("Pre-verify hook is disabled");
        }

        logger.info("Pre-verify: running {} steps", steps.size());
        List<StepResult> results = new ArrayList<>();

        for (VerifyStep step : steps) {
            StepResult result = runStep(step);
            results.add(result);

            if (!result.success() && step.blocking()) {
                // 阻塞步骤失败 → 立即停止
                logger.warn("Pre-verify FAILED at step '{}' (blocking): {}", step.name(), result.error());
                return new VerifyResult(false, results, "Blocking step '" + step.name() + "' failed: " + result.error());
            }
        }

        boolean allPassed = results.stream().allMatch(StepResult::success);
        return new VerifyResult(allPassed, results,
            allPassed ? "All verify steps passed" : "Some non-blocking steps failed");
    }

    private StepResult runStep(VerifyStep step) {
        long startTime = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", step.command())
                .directory(workspaceDir.toFile())
                .redirectErrorStream(true);
            pb.environment().put("TERM", "dumb");

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new StepResult(step.name(), false, "Timeout after " + timeoutSeconds + "s", output,
                    System.currentTimeMillis() - startTime);
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String error = success ? null : "Exit code: " + exitCode;

            return new StepResult(step.name(), success, error, output,
                System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return new StepResult(step.name(), false, e.getMessage(), "",
                System.currentTimeMillis() - startTime);
        }
    }

    // ============ 数据类 ============

    public record VerifyStep(String name, String command, boolean blocking) {}

    public record StepResult(String stepName, boolean success, String error, String output, long durationMs) {}

    public record VerifyResult(boolean passed, List<StepResult> steps, String summary) {
        public static VerifyResult skipped(String reason) {
            return new VerifyResult(true, List.of(), "Skipped: " + reason);
        }

        public boolean shouldBlock() {
            return !passed;
        }
    }
}
