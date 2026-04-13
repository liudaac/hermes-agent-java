package com.nousresearch.hermes.terminal;

import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Abstract terminal environment interface.
 * Supports multiple backends: local, docker, ssh, modal, daytona.
 */
public interface TerminalEnvironment {
    
    /**
     * Execute a command in this environment.
     */
    ExecutionResult execute(String command, Path cwd, int timeoutSeconds, 
                           Map<String, String> env) throws Exception;
    
    /**
     * Check if environment is alive.
     */
    boolean isAlive();
    
    /**
     * Get environment type.
     */
    String getType();
    
    /**
     * Clean up resources.
     */
    void cleanup();
    
    /**
     * Get working directory.
     */
    Path getWorkingDirectory();
    
    /**
     * Change working directory.
     */
    void setWorkingDirectory(Path cwd);
    
    /**
     * Execution result.
     */
    class ExecutionResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        public final long durationMs;
        
        public ExecutionResult(int exitCode, String stdout, String stderr, 
                              boolean timedOut, long durationMs) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
            this.durationMs = durationMs;
        }
        
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }
}
