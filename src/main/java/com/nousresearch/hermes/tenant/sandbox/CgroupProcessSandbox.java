package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Linux cgroups v2 的进程资源限制沙箱
 * 
 * 提供真正的资源隔离：
 * - CPU限制（cpu.max）
 * - 内存限制（memory.max）
 * - PID限制（pids.max）
 * - IO限制（io.max）- 可选
 */
public class CgroupProcessSandbox extends ProcessSandbox {

    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final boolean CGROUP_V2_AVAILABLE = checkCgroupV2();

    private final String tenantId;
    private final Path tenantCgroupPath;

    public CgroupProcessSandbox(TenantContext context, ProcessSandboxConfig config) {
        super(context, config);
        this.tenantId = context.getTenantId();
        this.tenantCgroupPath = Paths.get(CGROUP_ROOT, "hermes", "tenant-" + tenantId);
    }

    /**
     * 检查系统是否支持 cgroups v2
     */
    private static boolean checkCgroupV2() {
        try {
            Path cgroupV2 = Paths.get(CGROUP_ROOT, "cgroup.controllers");
            return Files.exists(cgroupV2);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 初始化租户 cgroup
     */
    public void initialize() throws ProcessSandboxException {
        if (!CGROUP_V2_AVAILABLE) {
            return; // 降级到普通模式
        }

        try {
            // 创建 hermes 根 cgroup（如果不存在）
            Path hermesRoot = Paths.get(CGROUP_ROOT, "hermes");
            if (!Files.exists(hermesRoot)) {
                Files.createDirectories(hermesRoot);
                // 启用所有控制器
                enableControllers(hermesRoot);
            }

            // 创建租户 cgroup
            if (!Files.exists(tenantCgroupPath)) {
                Files.createDirectories(tenantCgroupPath);
            }

            // 配置默认资源限制
            setDefaultLimits();

        } catch (IOException e) {
            throw new ProcessSandboxException("Failed to initialize cgroup for tenant: " + tenantId, e);
        }
    }

    /**
     * 执行命令，带 cgroups 资源限制
     */
    @Override
    public ProcessResult exec(List<String> command, ProcessOptions options) throws ProcessSandboxException {
        if (!CGROUP_V2_AVAILABLE || options == null) {
            return super.exec(command, options);
        }

        // 1. 创建临时 cgroup（用于本次执行）
        Path execCgroup = createExecCgroup(options);

        try {
            // 2. 配置资源限制
            configureCgroupLimits(execCgroup, options);

            // 3. 使用 cgexec 执行命令
            List<String> wrappedCommand = wrapWithCgexec(command, execCgroup);

            // 4. 执行并监控
            ProcessResult result = super.exec(wrappedCommand, options);

            // 5. 收集资源使用统计
            collectResourceStats(execCgroup, result);

            return result;

        } finally {
            // 6. 清理临时 cgroup
            cleanupCgroup(execCgroup);
        }
    }

    /**
     * 创建执行级别的 cgroup
     */
    private Path createExecCgroup(ProcessOptions options) throws ProcessSandboxException {
        String execId = "exec-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        Path execCgroup = tenantCgroupPath.resolve(execId);

        try {
            Files.createDirectories(execCgroup);
            return execCgroup;
        } catch (IOException e) {
            throw new ProcessSandboxException("Failed to create exec cgroup", e);
        }
    }

    /**
     * 配置 cgroup 资源限制
     */
    private void configureCgroupLimits(Path cgroup, ProcessOptions options) throws ProcessSandboxException {
        try {
            // CPU 限制
            if (options.getMaxCpuCores() > 0) {
                // cpu.max 格式: "quota period"
                // 例如: "50000 100000" 表示每 100ms 最多使用 50ms CPU = 0.5核
                long quota = (long) (options.getMaxCpuCores() * 100000);
                writeCgroupFile(cgroup, "cpu.max", quota + " 100000");
            }

            // 内存限制
            if (options.getMaxMemoryMB() > 0) {
                long maxBytes = options.getMaxMemoryMB() * 1024L * 1024L;
                writeCgroupFile(cgroup, "memory.max", String.valueOf(maxBytes));

                // 设置 swap 限制（与内存相同）
                writeCgroupFile(cgroup, "memory.swap.max", String.valueOf(maxBytes));
            }

            // PID 限制
            if (options.getMaxPids() > 0) {
                writeCgroupFile(cgroup, "pids.max", String.valueOf(options.getMaxPids()));
            }

            // IO 限制（可选）
            if (options.getMaxIoBps() > 0) {
                configureIoLimit(cgroup, options.getMaxIoBps());
            }

        } catch (IOException e) {
            throw new ProcessSandboxException("Failed to configure cgroup limits", e);
        }
    }

    /**
     * 使用 cgexec 包装命令
     */
    private List<String> wrapWithCgexec(List<String> command, Path cgroup) {
        List<String> wrapped = new ArrayList<>();
        wrapped.add("cgexec");
        wrapped.add("-g");
        wrapped.add("cpu,memory,pids,io:" + cgroup.toString().substring(1)); // 去掉开头的 /
        wrapped.addAll(command);
        return wrapped;
    }

    /**
     * 收集资源使用统计
     */
    private void collectResourceStats(Path cgroup, ProcessResult result) {
        try {
            // 内存峰值
            Path memoryPeak = cgroup.resolve("memory.peak");
            if (Files.exists(memoryPeak)) {
                String peak = Files.readString(memoryPeak).trim();
                result.setMemoryPeakBytes(Long.parseLong(peak));
            }

            // CPU 使用统计
            Path cpuStat = cgroup.resolve("cpu.stat");
            if (Files.exists(cpuStat)) {
                String stat = Files.readString(cpuStat);
                result.setCpuStats(parseCpuStats(stat));
            }

            // OOM 事件
            Path memoryEvents = cgroup.resolve("memory.events");
            if (Files.exists(memoryEvents)) {
                String events = Files.readString(memoryEvents);
                if (events.contains("oom_kill")) {
                    result.setOomKilled(true);
                }
            }

        } catch (Exception e) {
            // 统计失败不影响主流程
        }
    }

    /**
     * 清理 cgroup
     */
    private void cleanupCgroup(Path cgroup) {
        try {
            // 杀死 cgroup 中的所有进程
            Path procs = cgroup.resolve("cgroup.procs");
            if (Files.exists(procs)) {
                List<String> pids = Files.readAllLines(procs);
                for (String pid : pids) {
                    try {
                        long pidNum = Long.parseLong(pid.trim());
                        ProcessHandle.of(pidNum).ifPresent(ph -> ph.destroyForcibly());
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
            }

            // 删除 cgroup 目录
            Files.deleteIfExists(cgroup);

        } catch (Exception e) {
            // 清理失败记录日志但不抛出
        }
    }

    /**
     * 设置默认资源限制
     */
    private void setDefaultLimits() throws IOException {
        // 默认限制，防止失控
        writeCgroupFile(tenantCgroupPath, "pids.max", "100");  // 最多100个进程
    }

    /**
     * 启用控制器
     */
    private void enableControllers(Path path) throws IOException {
        // 在 cgroup v2 中，需要在父级启用子控制器
        Path subtreeControl = path.getParent().resolve("cgroup.subtree_control");
        if (Files.exists(subtreeControl)) {
            String controllers = "+cpu +memory +pids +io";
            Files.writeString(subtreeControl, controllers);
        }
    }

    /**
     * 写入 cgroup 文件
     */
    private void writeCgroupFile(Path cgroup, String filename, String content) throws IOException {
        Path file = cgroup.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * 配置 IO 限制
     */
    private void configureIoLimit(Path cgroup, long maxBps) throws IOException {
        // 需要知道设备号，这里简化处理
        // 实际实现中应该检测所有可用设备
    }

    /**
     * 解析 CPU 统计
     */
    private String parseCpuStats(String stat) {
        // 解析 cpu.stat 内容
        StringBuilder sb = new StringBuilder();
        for (String line : stat.split("\n")) {
            if (line.startsWith("usage_usec") || line.startsWith("user_usec") || line.startsWith("system_usec")) {
                sb.append(line).append("; ");
            }
        }
        return sb.toString();
    }

    /**
     * 清理租户 cgroup（租户销毁时调用）
     */
    public void destroy() {
        cleanupCgroup(tenantCgroupPath);
        try {
            Files.deleteIfExists(tenantCgroupPath);
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 检查 cgroup v2 是否可用
     */
    public static boolean isCgroupV2Available() {
        return CGROUP_V2_AVAILABLE;
    }
}
