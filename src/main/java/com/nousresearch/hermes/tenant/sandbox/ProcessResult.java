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
    public boolean isSuccess() {
        return !timedOut && exitCode == 0 && error == null;
    }

    @Override
    public String toString() {
        return "ProcessResult{" +
                "exitCode=" + exitCode +
                ", timedOut=" + timedOut +
                ", error='" + error + '\'' +
                '}';
    }
}
