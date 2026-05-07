package com.nousresearch.hermes.tenant.sandbox;

import java.nio.file.Path;

/**
 * 进程执行选项
 */
public class ProcessOptions {

    private int timeoutSeconds = 30;
    private long maxMemoryBytes = 128 * 1024 * 1024; // 128MB
    private int maxCpuPercent = 50; // 50% of one CPU
    private int maxPids = 10;
    private boolean redirectErrorStream = true;
    private Path workDirectory;

    // Phase 2: cgroups v2 资源限制
    private double maxCpuCores = 0; // 0 = unlimited, e.g., 0.5 = half core
    private long maxMemoryMB = 0; // 0 = unlimited
    private long maxIoBps = 0; // 0 = unlimited, bytes per second
    
    // GPU 支持
    private boolean gpuEnabled = false;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ProcessOptions options = new ProcessOptions();

        public Builder timeoutSeconds(int timeout) {
            options.timeoutSeconds = timeout;
            return this;
        }

        public Builder timeoutMinutes(int minutes) {
            options.timeoutSeconds = minutes * 60;
            return this;
        }

        public Builder maxMemoryMB(int mb) {
            options.maxMemoryBytes = mb * 1024L * 1024L;
            return this;
        }

        public Builder maxCpuPercent(int percent) {
            options.maxCpuPercent = Math.min(100, Math.max(1, percent));
            return this;
        }

        public Builder maxPids(int pids) {
            options.maxPids = pids;
            return this;
        }

        public Builder redirectErrorStream(boolean redirect) {
            options.redirectErrorStream = redirect;
            return this;
        }

        public Builder workDirectory(Path path) {
            options.workDirectory = path;
            return this;
        }

        // Phase 2: cgroups resource limits
        public Builder maxCpuCores(double cores) {
            options.maxCpuCores = cores;
            return this;
        }

        public Builder maxMemoryMB(long mb) {
            options.maxMemoryMB = mb;
            return this;
        }

        public Builder maxIoBps(long bps) {
            options.maxIoBps = bps;
            return this;
        }

        public Builder gpuEnabled(boolean enabled) {
            options.gpuEnabled = enabled;
            return this;
        }

        public ProcessOptions build() {
            return options;
        }
    }

    // Getters
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public long getMaxMemoryBytes() { return maxMemoryBytes; }
    public int getMaxCpuPercent() { return maxCpuPercent; }
    public int getMaxPids() { return maxPids; }
    public boolean isRedirectErrorStream() { return redirectErrorStream; }
    public Path getWorkDirectory() { return workDirectory; }
    public double getMaxCpuCores() { return maxCpuCores; }
    public long getMaxMemoryMB() { return maxMemoryMB; }
    public long getMaxIoBps() { return maxIoBps; }
    public boolean isGpuEnabled() { return gpuEnabled; }

    // Setters
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public void setMaxMemoryBytes(long maxMemoryBytes) { this.maxMemoryBytes = maxMemoryBytes; }
    public void setMaxCpuPercent(int maxCpuPercent) { this.maxCpuPercent = maxCpuPercent; }
    public void setMaxPids(int maxPids) { this.maxPids = maxPids; }
    public void setRedirectErrorStream(boolean redirectErrorStream) { this.redirectErrorStream = redirectErrorStream; }
    public void setWorkDirectory(Path workDirectory) { this.workDirectory = workDirectory; }
    public void setMaxCpuCores(double maxCpuCores) { this.maxCpuCores = maxCpuCores; }
    public void setMaxMemoryMB(long maxMemoryMB) { this.maxMemoryMB = maxMemoryMB; }
    public void setMaxIoBps(long maxIoBps) { this.maxIoBps = maxIoBps; }
    public void setGpuEnabled(boolean gpuEnabled) { this.gpuEnabled = gpuEnabled; }
}
