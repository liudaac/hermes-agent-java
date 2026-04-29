# 进程沙箱详细实现方案

## 1. ProcessSandbox 核心实现

```java
package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程沙箱 - 限制子进程资源使用
 */
public class ProcessSandbox {
    
    private final TenantContext context;
    private final ProcessSandboxConfig config;
    
    public ProcessSandbox(TenantContext context, ProcessSandboxConfig config) {
        this.context = context;
        this.config = config;
    }
    
    /**
     * 执行命令，带资源限制
     */
    public ProcessResult exec(List<String> command, ProcessOptions options) 
            throws ProcessSandboxException {
        
        // 1. 检查命令是否在白名单中
        if (!isCommandAllowed(command.get(0))) {
            throw new ProcessSandboxException("Command not allowed: " + command.get(0));
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
        
        // 4. 应用资源限制（平台特定）
        applyResourceLimits(process, options);
        
        // 5. 监控进程
        ProcessMonitor monitor = new ProcessMonitor(process, options);
        monitor.start();
        
        // 6. 等待完成或超时
        boolean completed = waitForProcess(process, options.getTimeoutSeconds());
        
        // 7. 收集结果
        return collectResult(process, completed);
    }
    
    /**
     * 构建带限制的 ProcessBuilder
     */
    private ProcessBuilder buildRestrictedProcess(List<String> command, ProcessOptions options) {
        ProcessBuilder pb = new ProcessBuilder(command);
        
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
        
        // Linux: 使用 cgexec 包装
        if (isLinux() && config.isUseCgroups()) {
            wrapWithCgroups(pb, options);
        }
        
        return pb;
    }
    
    /**
     * Linux: 使用 cgroups 限制资源
     */
    private void wrapWithCgroups(ProcessBuilder pb, ProcessOptions options) {
        List<String> cmd = pb.command();
        String tenantId = context.getTenantId();
        
        // 在命令前添加 cgexec
        cmd.add(0, "cgexec");
        cmd.add(1, "-g");
        cmd.add(2, buildCgroupPath(options, tenantId));
        
        // 确保 cgroup 存在
        ensureCgroupExists(tenantId, options);
    }
    
    /**
     * 构建 cgroup 路径
     */
    private String buildCgroupPath(ProcessOptions options, String tenantId) {
        StringBuilder sb = new StringBuilder();
        
        if (options.getMaxMemoryBytes() > 0) {
            sb.append("memory:/hermes/").append(tenantId).append(",");
        }
        if (options.getMaxCpuPercent() > 0) {
            sb.append("cpu,cpuacct:/hermes/").append(tenantId).append(",");
        }
        if (options.getMaxPids() > 0) {
            sb.append("pids:/hermes/").append(tenantId).append(",");
        }
        
        // 移除末尾逗号
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        
        return sb.toString();
    }
    
    /**
     * 创建并配置 cgroup
     */
    private void ensureCgroupExists(String tenantId, ProcessOptions options) {
        String cgroupBase = "/sys/fs/cgroup/hermes/" + tenantId;
        
        try {
            // 创建 cgroup 目录
            Process mkdir = Runtime.getRuntime().exec(new String[]{"mkdir", "-p", cgroupBase});
            mkdir.waitFor();
            
            // 设置内存限制
            if (options.getMaxMemoryBytes() > 0) {
                writeCgroupFile(cgroupBase + "/memory.limit_in_bytes", 
                    String.valueOf(options.getMaxMemoryBytes()));
            }
            
            // 设置 CPU 限制
            if (options.getMaxCpuPercent() > 0) {
                long quota = options.getMaxCpuPercent() * 1000; //  microseconds per 100ms
                writeCgroupFile(cgroupBase + "/cpu.cfs_quota_us", String.valueOf(quota));
                writeCgroupFile(cgroupBase + "/cpu.cfs_period_us", "100000");
            }
            
            // 设置 PID 限制
            if (options.getMaxPids() > 0) {
                writeCgroupFile(cgroupBase + "/pids.max", String.valueOf(options.getMaxPids()));
            }
            
        } catch (Exception e) {
            throw new ProcessSandboxException("Failed to setup cgroup", e);
        }
    }
    
    private void writeCgroupFile(String path, String value) throws IOException {
        Process echo = Runtime.getRuntime().exec(new String[]{"sh", "-c", "echo " + value + " > " + path});
        try {
            echo.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 应用资源限制（跨平台）
     */
    private void applyResourceLimits(Process process, ProcessOptions options) {
        if (isWindows()) {
            applyWindowsLimits(process, options);
        } else if (isMac()) {
            applyMacLimits(process, options);
        }
    }
    
    /**
     * Windows: 使用 Job Objects
     */
    private void applyWindowsLimits(Process process, ProcessOptions options) {
        // 使用 JNI 或 JNA 调用 Windows API
        // SetInformationJobObject + JOBOBJECT_BASIC_LIMIT_INFORMATION
    }
    
    /**
     * macOS: 使用 setrlimit
     */
    private void applyMacLimits(Process process, ProcessOptions options) {
        // 使用 JNI 调用 setrlimit
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
        result.setExitCode(completed ? process.exitValue() : -1);
        result.setTimedOut(!completed);
        
        // 读取输出
        try {
            result.setStdout(new String(process.getInputStream().readAllBytes()));
            result.setStderr(new String(process.getErrorStream().readAllBytes()));
        } catch (IOException e) {
            result.setError("Failed to read output: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 检查命令是否允许
     */
    private boolean isCommandAllowed(String command) {
        // 检查黑名单
        if (config.getCommandBlacklist().contains(command)) {
            return false;
        }
        
        // 检查白名单（如果配置了）
        if (!config.getCommandWhitelist().isEmpty()) {
            return config.getCommandWhitelist().contains(command);
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
        
        // 设置安全的 PATH
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
    }
    
    // ============ 辅助方法 ============
    
    private boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }
    
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
    
    private boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
}

/**
 * 进程选项
 */
public class ProcessOptions {
    private int timeoutSeconds = 30;
    private long maxMemoryBytes = 128 * 1024 * 1024; // 128MB
    private int maxCpuPercent = 50; // 50% of one CPU
    private int maxPids = 10;
    private boolean redirectErrorStream = true;
    private Path workDirectory;
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private ProcessOptions options = new ProcessOptions();
        
        public Builder timeoutSeconds(int timeout) {
            options.timeoutSeconds = timeout;
            return this;
        }
        
        public Builder maxMemoryMB(int mb) {
            options.maxMemoryBytes = mb * 1024L * 1024L;
            return this;
        }
        
        public Builder maxCpuPercent(int percent) {
            options.maxCpuPercent = percent;
            return this;
        }
        
        public Builder maxPids(int pids) {
            options.maxPids = pids;
            return this;
        }
        
        public ProcessOptions build() {
            return options;
        }
    }
    
    // Getters...
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public long getMaxMemoryBytes() { return maxMemoryBytes; }
    public int getMaxCpuPercent() { return maxCpuPercent; }
    public int getMaxPids() { return maxPids; }
    public boolean isRedirectErrorStream() { return redirectErrorStream; }
    public Path getWorkDirectory() { return workDirectory; }
}

/**
 * 进程执行结果
 */
public class ProcessResult {
    private int exitCode;
    private String stdout;
    private String stderr;
    private boolean timedOut;
    private String error;
    
    // Getters and setters...
}

/**
 * 沙箱异常
 */
public class ProcessSandboxException extends RuntimeException {
    public ProcessSandboxException(String message) {
        super(message);
    }
    
    public ProcessSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 进程监控器
 */
class ProcessMonitor {
    private final Process process;
    private final ProcessOptions options;
    
    ProcessMonitor(Process process, ProcessOptions options) {
        this.process = process;
        this.options = options;
    }
    
    void start() {
        Thread monitor = new Thread(() -> {
            while (process.isAlive()) {
                try {
                    Thread.sleep(1000);
                    
                    // 检查资源使用
                    long memoryUsage = getMemoryUsage();
                    if (memoryUsage > options.getMaxMemoryBytes()) {
                        process.destroyForcibly();
                        return;
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }
    
    private long getMemoryUsage() {
        // 读取 /proc/{pid}/status 或 ProcessHandle
        return 0;
    }
}
```

## 2. 使用示例

```java
// 在 TenantContext 中集成
public class TenantContext {
    private final ProcessSandbox processSandbox;
    
    public ProcessResult executeCommand(List<String> command) {
        ProcessOptions options = ProcessOptions.builder()
            .timeoutSeconds(30)
            .maxMemoryMB(128)
            .maxCpuPercent(50)
            .maxPids(10)
            .build();
        
        return processSandbox.exec(command, options);
    }
}

// 租户配置示例
ProcessSandboxConfig config = ProcessSandboxConfig.builder()
    .workDirectory(Paths.get("/data/tenants/" + tenantId))
    .commandWhitelist(Arrays.asList("git", "mvn", "python3", "node"))
    .commandBlacklist(Arrays.asList("rm", "mkfs", "dd"))
    .useCgroups(true)
    .build();
```

## 3. 集成到现有系统

```java
// 在 TenantContext 构造函数中添加
public TenantContext(TenantProvisioningRequest request, ...) {
    // ... 现有代码 ...
    
    this.processSandbox = new ProcessSandbox(
        this,
        request.getProcessSandboxConfig() != null 
            ? request.getProcessSandboxConfig() 
            : ProcessSandboxConfig.defaultConfig()
    );
}
```
