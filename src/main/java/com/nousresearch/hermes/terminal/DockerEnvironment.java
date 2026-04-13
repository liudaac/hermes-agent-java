package com.nousresearch.hermes.terminal;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Docker terminal environment - executes commands in a Docker container.
 */
public class DockerEnvironment implements TerminalEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(DockerEnvironment.class);
    private static final long MAX_OUTPUT_CHARS = 50000;
    
    private final DockerClient dockerClient;
    private final String containerId;
    private final String imageName;
    private Path workingDirectory;
    private volatile boolean alive = false;
    
    public DockerEnvironment(String imageName, Path cwd, List<String> volumes) throws Exception {
        this.imageName = imageName != null ? imageName : "hermes-sandbox:latest";
        this.workingDirectory = cwd != null ? cwd : Paths.get("/workspace");
        
        // Create Docker client
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("unix:///var/run/docker.sock")
            .build();
        
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
        
        // Create and start container
        this.containerId = createContainer(volumes);
        this.alive = true;
        
        logger.info("Docker environment created: {} (image: {})", containerId.substring(0, 12), this.imageName);
    }
    
    private String createContainer(List<String> volumes) throws Exception {
        HostConfig hostConfig = new HostConfig()
            .withAutoRemove(true)
            .withNetworkMode("bridge");
        
        // Add volume mounts
        List<Bind> binds = new ArrayList<>();
        if (volumes != null) {
            for (String vol : volumes) {
                String[] parts = vol.split(":");
                if (parts.length == 2) {
                    binds.add(new Bind(parts[0], new Volume(parts[1])));
                }
            }
        }
        
        // Always mount current directory to /workspace
        binds.add(new Bind(workingDirectory.toString(), new Volume("/workspace")));
        hostConfig.withBinds(binds);
        
        // Resource limits
        hostConfig.withMemory(2L * 1024 * 1024 * 1024); // 2GB
        hostConfig.withCpuQuota(100000L); // 1 CPU
        
        CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
            .withHostConfig(hostConfig)
            .withWorkingDir("/workspace")
            .withCmd("tail", "-f", "/dev/null") // Keep container running
            .exec();
        
        dockerClient.startContainerCmd(container.getId()).exec();
        
        return container.getId();
    }
    
    @Override
    public ExecutionResult execute(String command, Path cwd, int timeoutSeconds, 
                                   Map<String, String> env) throws Exception {
        long startTime = System.currentTimeMillis();
        
        String effectiveCwd = cwd != null ? cwd.toString() : "/workspace";
        
        // Build exec command
        String[] cmd = {"/bin/bash", "-c", command};
        
        logger.info("Docker exec [{}]: {}", containerId.substring(0, 12), command);
        
        // Create exec
        var execCreateCmd = dockerClient.execCreateCmd(containerId)
            .withCmd(cmd)
            .withWorkingDir(effectiveCwd)
            .withAttachStdout(true)
            .withAttachStderr(true);
        
        if (env != null && !env.isEmpty()) {
            List<String> envList = new ArrayList<>();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                envList.add(entry.getKey() + "=" + entry.getValue());
            }
            execCreateCmd.withEnv(envList);
        }
        
        var execResponse = execCreateCmd.exec();
        String execId = execResponse.getId();
        
        // Execute with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ExecutionResult> future = executor.submit(() -> {
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            
            dockerClient.execStartCmd(execId)
                .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                        String data = new String(frame.getPayload(), StandardCharsets.UTF_8);
                        switch (frame.getStreamType()) {
                            case STDOUT -> stdout.append(data);
                            case STDERR -> stderr.append(data);
                        }
                    }
                }).awaitCompletion();
            
            // Get exit code
            var inspectResponse = dockerClient.inspectExecCmd(execId).exec();
            int exitCode = inspectResponse.getExitCodeLong() != null 
                ? inspectResponse.getExitCodeLong().intValue() 
                : -1;
            
            long duration = System.currentTimeMillis() - startTime;
            return new ExecutionResult(exitCode, truncate(stdout.toString()), 
                                      truncate(stderr.toString()), false, duration);
        });
        
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = System.currentTimeMillis() - startTime;
            return new ExecutionResult(-1, "", "Command timed out after " + timeoutSeconds + " seconds", 
                                      true, duration);
        } finally {
            executor.shutdownNow();
        }
    }
    
    @Override
    public boolean isAlive() {
        if (!alive) return false;
        
        try {
            var container = dockerClient.inspectContainerCmd(containerId).exec();
            return container.getState().getRunning();
        } catch (Exception e) {
            alive = false;
            return false;
        }
    }
    
    @Override
    public String getType() {
        return "docker";
    }
    
    @Override
    public void cleanup() {
        alive = false;
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            logger.info("Docker container stopped: {}", containerId.substring(0, 12));
        } catch (Exception e) {
            logger.warn("Error stopping container: {}", e.getMessage());
        }
        
        try {
            dockerClient.close();
        } catch (Exception e) {
            logger.warn("Error closing Docker client: {}", e.getMessage());
        }
    }
    
    private String truncate(String output) {
        if (output.length() > MAX_OUTPUT_CHARS) {
            return output.substring(0, (int) MAX_OUTPUT_CHARS) + 
                   "\n... [truncated, total: " + output.length() + " chars]";
        }
        return output;
    }
    
    @Override
    public Path getWorkingDirectory() {
        return workingDirectory;
    }
    
    @Override
    public void setWorkingDirectory(Path cwd) {
        this.workingDirectory = cwd;
    }
}