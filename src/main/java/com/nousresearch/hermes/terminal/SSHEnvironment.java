package com.nousresearch.hermes.terminal;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * SSH terminal environment - executes commands on a remote host via SSH.
 */
public class SSHEnvironment implements TerminalEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(SSHEnvironment.class);
    private static final long MAX_OUTPUT_CHARS = 50000;
    
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String privateKey;
    private final JSch jsch;
    private Session session;
    private Path workingDirectory;
    private volatile boolean alive = false;
    
    public SSHEnvironment(String host, int port, String user, String password, 
                         String privateKey, Path cwd) throws Exception {
        this.host = host;
        this.port = port > 0 ? port : 22;
        this.user = user;
        this.password = password;
        this.privateKey = privateKey;
        this.workingDirectory = cwd != null ? cwd : Paths.get("/home/" + user);
        
        this.jsch = new JSch();
        
        // Add private key if provided
        if (privateKey != null && !privateKey.isEmpty()) {
            jsch.addIdentity(privateKey);
        }
        
        connect();
    }
    
    private void connect() throws Exception {
        session = jsch.getSession(user, host, port);
        
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }
        
        // Disable strict host key checking for simplicity
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("UserKnownHostsFile", "/dev/null");
        
        session.connect(30000); // 30 second timeout
        alive = true;
        
        logger.info("SSH connected to {}@{}:{}", user, host, port);
    }
    
    @Override
    public ExecutionResult execute(String command, Path cwd, int timeoutSeconds, 
                                   Map<String, String> env) throws Exception {
        long startTime = System.currentTimeMillis();
        
        if (!isAlive()) {
            connect();
        }
        
        String effectiveCwd = cwd != null ? cwd.toString() : workingDirectory.toString();
        
        // Build command with cd and env
        StringBuilder fullCommand = new StringBuilder();
        fullCommand.append("cd ").append(escapeShell(effectiveCwd)).append(" && ");
        
        // Add environment variables
        if (env != null && !env.isEmpty()) {
            for (Map.Entry<String, String> entry : env.entrySet()) {
                fullCommand.append("export ").append(entry.getKey())
                          .append("=").append(escapeShell(entry.getValue())).append(" && ");
            }
        }
        
        fullCommand.append(command);
        
        logger.info("SSH exec [{}]: {}", host, command);
        
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(fullCommand.toString());
            
            // Capture output
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            
            channel.connect();
            
            // Wait with timeout
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Integer> future = executor.submit(() -> {
                while (!channel.isClosed()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                return channel.getExitStatus();
            });
            
            Integer exitCode;
            try {
                exitCode = future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                channel.disconnect();
                long duration = System.currentTimeMillis() - startTime;
                return new ExecutionResult(-1, truncate(stdout.toString()), 
                    truncate(stderr.toString()), true, duration);
            } finally {
                executor.shutdownNow();
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            return new ExecutionResult(
                exitCode != null ? exitCode : -1,
                truncate(stdout.toString(StandardCharsets.UTF_8)),
                truncate(stderr.toString(StandardCharsets.UTF_8)),
                false,
                duration
            );
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }
    
    @Override
    public boolean isAlive() {
        if (!alive || session == null) {
            return false;
        }
        return session.isConnected();
    }
    
    @Override
    public String getType() {
        return "ssh";
    }
    
    @Override
    public void cleanup() {
        alive = false;
        if (session != null && session.isConnected()) {
            session.disconnect();
            logger.info("SSH disconnected from {}@{}:{}", user, host, port);
        }
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
    
    private String escapeShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    
    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, (int) MAX_OUTPUT_CHARS) + 
               "\n... [truncated " + (text.length() - MAX_OUTPUT_CHARS) + " chars]";
    }
}
