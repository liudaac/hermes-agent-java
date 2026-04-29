package com.nousresearch.hermes.tenant.container;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.sandbox.ProcessOptions;
import com.nousresearch.hermes.tenant.sandbox.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 容器化沙箱 - Docker/Podman 集成
 * 
 * 提供基于容器的完全隔离执行环境：
 * - 独立的文件系统命名空间
 * - 独立的网络命名空间
 * - 资源限制（CPU、内存、GPU）
 * - 安全策略（只读根文件系统、能力限制）
 */
public class ContainerSandbox {

    private static final Logger logger = LoggerFactory.getLogger(ContainerSandbox.class);
    
    private final String tenantId;
    private final Path tenantDir;
    private final ContainerRuntime runtime;
    private final boolean gpuEnabled;
    
    // 容器镜像
    private static final String DEFAULT_IMAGE = "hermes-sandbox:latest";
    
    public enum ContainerRuntime {
        DOCKER, PODMAN, CONTAINERD
    }
    
    public ContainerSandbox(TenantContext context, ContainerRuntime runtime, boolean gpuEnabled) {
        this.tenantId = context.getTenantId();
        this.tenantDir = context.getTenantDir();
        this.runtime = runtime;
        this.gpuEnabled = gpuEnabled;
    }
    
    /**
     * 在容器中执行命令
     */
    public ProcessResult exec(List<String> command, ProcessOptions options) {
        String containerId = "hermes-" + tenantId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        try {
            // 构建容器运行命令
            List<String> containerCmd = buildContainerCommand(containerId, command, options);
            
            logger.debug("Executing in container: {}", String.join(" ", containerCmd));
            
            ProcessBuilder pb = new ProcessBuilder(containerCmd);
            pb.redirectErrorStream(true);
            
            long startTime = System.currentTimeMillis();
            Process process = pb.start();
            
            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // 等待完成或超时
            boolean finished = process.waitFor(
                options.getTimeoutSeconds(), 
                TimeUnit.SECONDS
            );
            
            ProcessResult result = new ProcessResult();
            if (!finished) {
                process.destroyForcibly();
                result.setTimedOut(true);
                result.setExitCode(-1);
                result.setError("Process timed out after " + options.getTimeoutSeconds() + " seconds");
            } else {
                result.setExitCode(process.exitValue());
                result.setStdout(output.toString());
            }
            
            // 清理容器
            cleanupContainer(containerId);
            
            return result;
            
        } catch (Exception e) {
            logger.error("Container execution failed", e);
            ProcessResult result = new ProcessResult();
            result.setExitCode(-1);
            result.setError("Container execution failed: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 构建容器运行命令
     */
    private List<String> buildContainerCommand(String containerId, 
                                                List<String> command, 
                                                ProcessOptions options) {
        List<String> cmd = new ArrayList<>();
        
        switch (runtime) {
            case DOCKER -> cmd.add("docker");
            case PODMAN -> cmd.add("podman");
            case CONTAINERD -> cmd.add("ctr");
        }
        
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--name" + containerId);
        
        // 资源限制
        if (options.getMaxMemoryMB() > 0) {
            cmd.add("--memory=" + options.getMaxMemoryMB() + "m");
            cmd.add("--memory-swap=" + options.getMaxMemoryMB() + "m");
        }
        
        if (options.getMaxCpuCores() > 0) {
            cmd.add("--cpus=" + options.getMaxCpuCores());
        }
        
        if (options.getMaxPids() > 0) {
            cmd.add("--pids-limit=" + options.getMaxPids());
        }
        
        // GPU 支持
        if (gpuEnabled && options.isGpuEnabled()) {
            cmd.add("--gpus=all");
        }
        
        // 安全选项
        cmd.add("--read-only");
        cmd.add("--security-opt=no-new-privileges:true");
        cmd.add("--cap-drop=ALL");
        cmd.add("--cap-add=CHOWN");
        cmd.add("--cap-add=SETUID");
        cmd.add("--cap-add=SETGID");
        
        // 网络隔离
        cmd.add("--network=none");
        
        // 挂载租户目录
        cmd.add("-v" + tenantDir.toAbsolutePath() + ":/workspace:rw");
        cmd.add("-w/workspace");
        
        // 镜像
        cmd.add(DEFAULT_IMAGE);
        
        // 要执行的命令
        cmd.addAll(command);
        
        return cmd;
    }
    
    /**
     * 清理容器
     */
    private void cleanupContainer(String containerId) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(runtime == ContainerRuntime.DOCKER ? "docker" : "podman");
            cmd.add("rm");
            cmd.add("-f");
            cmd.add(containerId);
            
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            logger.warn("Failed to cleanup container: {}", containerId, e);
        }
    }
    
    /**
     * 检查容器运行时是否可用
     */
    public static boolean isRuntimeAvailable(ContainerRuntime runtime) {
        try {
            String cmd = runtime == ContainerRuntime.DOCKER ? "docker" : 
                        runtime == ContainerRuntime.PODMAN ? "podman" : "ctr";
            Process process = new ProcessBuilder(cmd, "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 检测可用的容器运行时
     */
    public static ContainerRuntime detectRuntime() {
        if (isRuntimeAvailable(ContainerRuntime.DOCKER)) {
            return ContainerRuntime.DOCKER;
        }
        if (isRuntimeAvailable(ContainerRuntime.PODMAN)) {
            return ContainerRuntime.PODMAN;
        }
        return null;
    }
}
