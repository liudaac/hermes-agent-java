package com.nousresearch.hermes.tenant.sandbox;

/**
 * 进程执行结果
 */
public class ProcessResult {

    private int exitCode;
    private String stdout;
    private String stderr;
    private boolean timedOut;
    private String error;

    // cgroups 资源统计
    private long memoryPeakBytes;
    private String cpuStats;
    private boolean oomKilled;

    public ProcessResult() {
    }

    public ProcessResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    // Getters and Setters
    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }

    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }

    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }

    public boolean isTimedOut() { return timedOut; }
    public void setTimedOut(boolean timedOut) { this.timedOut = timedOut; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    /**
     * 是否成功执行
     */
    // cgroups 资源统计 getter/setter
    public long getMemoryPeakBytes() { return memoryPeakBytes; }
    public void setMemoryPeakBytes(long memoryPeakBytes) { this.memoryPeakBytes = memoryPeakBytes; }

    public String getCpuStats() { return cpuStats; }
    public void setCpuStats(String cpuStats) { this.cpuStats = cpuStats; }

    public boolean isOomKilled() { return oomKilled; }
    public void setOomKilled(boolean oomKilled) { this.oomKilled = oomKilled; }

    public boolean isSuccess() {
        return !timedOut && !oomKilled && exitCode == 0 && error == null;
    }

    /**
     * 获取格式化的资源使用报告
     */
    public String getResourceReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource Usage:\n");
        if (memoryPeakBytes > 0) {
            sb.append(String.format("  Memory Peak: %.2f MB\n", memoryPeakBytes / (1024.0 * 1024.0)));
        }
        if (cpuStats != null && !cpuStats.isEmpty()) {
            sb.append("  CPU Stats: ").append(cpuStats).append("\n");
        }
        if (oomKilled) {
            sb.append("  ⚠️ OOM Killed\n");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ProcessResult{" +
                "exitCode=" + exitCode +
                ", timedOut=" + timedOut +
                ", oomKilled=" + oomKilled +
                ", error='" + error + '\'' +
                '}';
    }
}
