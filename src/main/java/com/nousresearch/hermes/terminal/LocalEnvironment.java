package com.nousresearch.hermes.terminal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local terminal environment - executes commands on the host system.
 */
public class LocalEnvironment implements TerminalEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(LocalEnvironment.class);
    private static final long MAX_OUTPUT_CHARS = 50000;
    
    private Path workingDirectory;
    private final List<Process> activeProcesses = new CopyOnWriteArrayList<>();
    private volatile boolean alive = true;
    
    public LocalEnvironment(Path initialCwd) {
        this.workingDirectory = initialCwd != null ? initialCwd : Paths.get(".").toAbsolutePath();
    }
    
    @Override
    public ExecutionResult execute(String command, Path cwd, int timeoutSeconds, 
                                   Map<String, String> env) throws Exception {
        long startTime = System.currentTimeMillis();
        
        Path effectiveCwd = cwd != null ? cwd : workingDirectory;
        
        // Build process
        ProcessBuilder pb = new ProcessBuilder();
        
        // Use shell for complex commands
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("/bin/bash", "-c", command);
        }
        
        pb.directory(effectiveCwd.toFile());
        pb.redirectErrorStream(false);
        
        // Set environment variables
        if (env != null) {
            pb.environment().putAll(env);
        }
        
        logger.info("Executing: {}", command);
        
        Process process = pb.start();
        activeProcesses.add(process);
        
        try {
            // Read output with timeout
            ExecutorService executor = Executors.newFixedThreadPool(2);
            
            Future<String> stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));
            
            // Wait for completion
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            String stdout = "";
            String stderr = "";
            
            try {
                stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                stdoutFuture.cancel(true);
            }
            
            try {
                stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                stderrFuture.cancel(true);
            }
            
            executor.shutdownNow();
            
            if (!finished) {
                process.destroyForcibly();
                long duration = System.currentTimeMillis() - startTime;
                return new ExecutionResult(-1, truncate(stdout), truncate(stderr), 
                                          true, duration);
            }
            
            int exitCode = process.exitValue();
            long duration = System.currentTimeMillis() - startTime;
            
            return new ExecutionResult(exitCode, truncate(stdout), truncate(stderr), 
                                      false, duration);
            
        } finally {
            activeProcesses.remove(process);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
    
    @Override
    public boolean isAlive() {
        return alive;
    }
    
    @Override
    public String getType() {
        return "local";
    }
    
    @Override
    public void cleanup() {
        alive = false;
        for (Process process : activeProcesses) {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                logger.warn("Error cleaning up process: {}", e.getMessage());
            }
        }
        activeProcesses.clear();
    }
    
    @Override
    public Path getWorkingDirectory() {
        return workingDirectory;
    }
    
    @Override
    public void setWorkingDirectory(Path cwd) {
        this.workingDirectory = cwd;
    }
    
    // ==================== Private Methods ====================
    
    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > MAX_OUTPUT_CHARS * 2) {
                    sb.append("... [output truncated]\n");
                    // Drain remaining
                    while (reader.readLine() != null) {}
                    break;
                }
            }
        }
        return sb.toString();
    }
    
    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, (int) MAX_OUTPUT_CHARS) + 
               "\n... [truncated " + (text.length() - MAX_OUTPUT_CHARS) + " chars]";
    }
}
