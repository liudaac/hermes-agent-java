package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 进程沙箱 - 限制子进程资源使用
 */
public class ProcessSandbox {

    private final TenantContext context;
    private final ProcessSandboxConfig config;

    public ProcessSandbox(TenantContext context, ProcessSandboxConfig config) {
        this.context = context;
        this.config = config != null ? config : ProcessSandboxConfig.defaultConfig();
    }

    /**
     * 执行命令，带资源限制
     */
    public ProcessResult exec(List<String> command, ProcessOptions options) throws ProcessSandboxException {
        if (options == null) {
            options = ProcessOptions.builder().build();
        }

        // 1. 检查命令是否在白名单中
        String cmdName = command.get(0);
        if (!isCommandAllowed(cmdName)) {
            throw new ProcessSandboxException("Command not allowed: " + cmdName);
        }

        // 2. 构建带限制的进程
        ProcessBuilder pb = buildRestrictedProcess(command, options);

        // 3. 启动进程
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new ProcessSandboxException("Failed to start process", e);
        }

        // 4. 监控进程
        ProcessMonitor monitor = new ProcessMonitor(process, options, context.getTenantId());
        monitor.start();

        // 5. 等待完成或超时
        boolean completed = waitForProcess(process, options.getTimeoutSeconds());

        // 6. 收集结果
        return collectResult(process, completed);
    }

    /**
     * 构建带限制的 ProcessBuilder
     */
    private ProcessBuilder buildRestrictedProcess(List<String> command, ProcessOptions options) {
        List<String> wrappedCommand = new ArrayList<>(command);

        // Linux: 使用 timeout 命令限制执行时间
        if (isLinux() && options.getTimeoutSeconds() > 0) {
            wrappedCommand.add(0, String.valueOf(options.getTimeoutSeconds() + 5)); // 额外5秒缓冲
            wrappedCommand.add(0, "-k");
            wrappedCommand.add(0, String.valueOf(options.getTimeoutSeconds()));
            wrappedCommand.add(0, "-s");
            wrappedCommand.add(0, "SIGTERM");
            wrappedCommand.add(0, "timeout");
        }

        ProcessBuilder pb = new ProcessBuilder(wrappedCommand);

        // 设置工作目录（限制在租户目录内）
        Path workDir = config.getWorkDirectory();
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }

        // 设置环境变量（清理敏感变量）
        Map<String, String> env = pb.environment();
        sanitizeEnvironment(env);

        // 重定向输入输出
        if (options.isRedirectErrorStream()) {
            pb.redirectErrorStream(true);
        }

        return pb;
    }

    /**
     * 检查命令是否允许
     */
    private boolean isCommandAllowed(String command) {
        // 获取命令名称（去除路径）
        String cmdName = command;
        int lastSlash = command.lastIndexOf('/');
        if (lastSlash >= 0) {
            cmdName = command.substring(lastSlash + 1);
        }
        int lastBackslash = cmdName.lastIndexOf('\\');
        if (lastBackslash >= 0) {
            cmdName = cmdName.substring(lastBackslash + 1);
        }

        // 检查黑名单
        Set<String> blacklist = config.getCommandBlacklist();
        if (blacklist != null && blacklist.contains(cmdName)) {
            return false;
        }

        // 检查白名单（如果配置了）
        Set<String> whitelist = config.getCommandWhitelist();
        if (whitelist != null && !whitelist.isEmpty()) {
            return whitelist.contains(cmdName);
        }

        return true;
    }

    /**
     * 清理环境变量
     */
    private void sanitizeEnvironment(Map<String, String> env) {
        // 移除敏感环境变量
        env.remove("HERMES_API_KEY");
        env.remove("HERMES_SECRET");
        env.remove("AWS_ACCESS_KEY_ID");
        env.remove("AWS_SECRET_ACCESS_KEY");
        env.remove("OPENAI_API_KEY");
        env.remove("PRIVATE_KEY");

        // 设置安全的 PATH
        if (isWindows()) {
            env.put("PATH", "C:\\Windows\\System32;C:\\Windows");
        } else {
            env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        }
    }

    /**
     * 等待进程完成
     */
    private boolean waitForProcess(Process process, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            try {
                process.waitFor();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return false;
            }
        }

        try {
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    /**
     * 收集执行结果
     */
    private ProcessResult collectResult(Process process, boolean completed) {
        ProcessResult result = new ProcessResult();

        if (!completed) {
            result.setTimedOut(true);
            result.setExitCode(-1);
            result.setError("Process timed out");
            process.destroyForcibly();
            return result;
        }

        result.setExitCode(process.exitValue());

        // 读取输出
        try {
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            // 限制输出大小
            int maxOutput = 1024 * 1024; // 1MB
            if (stdout.length() > maxOutput) {
                stdout = stdout.substring(0, maxOutput) + "\n... (truncated)";
            }
            if (stderr.length() > maxOutput) {
                stderr = stderr.substring(0, maxOutput) + "\n... (truncated)";
            }

            result.setStdout(stdout);
            result.setStderr(stderr);
        } catch (IOException e) {
            result.setError("Failed to read output: " + e.getMessage());
        }

        return result;
    }

    // ============ 辅助方法 ============

    private boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    // ============ 内部类 ============

    /**
     * 进程监控器
     */
    private static class ProcessMonitor {
        private final Process process;
        private final ProcessOptions options;
        private final String tenantId;

        ProcessMonitor(Process process, ProcessOptions options, String tenantId) {
            this.process = process;
            this.options = options;
            this.tenantId = tenantId;
        }

        void start() {
            // 监控逻辑（可选实现）
        }
    }
}
