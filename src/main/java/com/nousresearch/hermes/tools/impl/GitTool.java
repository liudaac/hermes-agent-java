package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Git version control tool.
 * Mirrors Python's git tool functionality.
 */
public class GitTool {
    private static final Logger logger = LoggerFactory.getLogger(GitTool.class);
    private static final int DEFAULT_TIMEOUT = 30;
    
    public static void register(ToolRegistry registry) {
        GitTool instance = new GitTool();
        instance.registerInstance(registry);
    }
    
    public void registerInstance(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("git_status")
            .toolset("git")
            .schema(Map.of("description", "Get git status",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string", "description", "Repository path")),
                    "required", List.of("path"))))
            .handler(this::gitStatus).emoji("📊").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_add")
            .toolset("git")
            .schema(Map.of("description", "Stage files",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"), "files", Map.of("type", "string")),
                    "required", List.of("path", "files"))))
            .handler(this::gitAdd).emoji("➕").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_commit")
            .toolset("git")
            .schema(Map.of("description", "Commit changes",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"), "message", Map.of("type", "string")),
                    "required", List.of("path", "message"))))
            .handler(this::gitCommit).emoji("💾").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_push")
            .toolset("git")
            .schema(Map.of("description", "Push to remote",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"), "remote", Map.of("type", "string", "default", "origin"), "branch", Map.of("type", "string")),
                    "required", List.of("path"))))
            .handler(this::gitPush).emoji("🚀").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_pull")
            .toolset("git")
            .schema(Map.of("description", "Pull from remote",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string")),
                    "required", List.of("path"))))
            .handler(this::gitPull).emoji("⬇️").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_log")
            .toolset("git")
            .schema(Map.of("description", "View commit history",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer", "default", 10)),
                    "required", List.of("path"))))
            .handler(this::gitLog).emoji("📜").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_branch")
            .toolset("git")
            .schema(Map.of("description", "List or create branches",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("path", Map.of("type", "string"), "create", Map.of("type", "string"), "switch", Map.of("type", "string")),
                    "required", List.of("path"))))
            .handler(this::gitBranch).emoji("🌿").build());
        
        registry.register(new ToolEntry.Builder()
            .name("git_clone")
            .toolset("git")
            .schema(Map.of("description", "Clone a repository",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("url", Map.of("type", "string"), "path", Map.of("type", "string")),
                    "required", List.of("url", "path"))))
            .handler(this::gitClone).emoji("📥").build());
    }
    
    private String gitStatus(Map<String, Object> args) {
        return runGit((String) args.get("path"), "status", "-sb");
    }
    
    private String gitAdd(Map<String, Object> args) {
        return runGit((String) args.get("path"), "add", (String) args.get("files"));
    }
    
    private String gitCommit(Map<String, Object> args) {
        return runGit((String) args.get("path"), "commit", "-m", (String) args.get("message"));
    }
    
    private String gitPush(Map<String, Object> args) {
        String remote = (String) args.getOrDefault("remote", "origin");
        String branch = (String) args.get("branch");
        if (branch != null) {
            return runGit((String) args.get("path"), "push", remote, branch);
        }
        return runGit((String) args.get("path"), "push", remote);
    }
    
    private String gitPull(Map<String, Object> args) {
        return runGit((String) args.get("path"), "pull");
    }
    
    private String gitLog(Map<String, Object> args) {
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 10;
        return runGit((String) args.get("path"), "log", "--oneline", "-" + limit);
    }
    
    private String gitBranch(Map<String, Object> args) {
        String path = (String) args.get("path");
        String create = (String) args.get("create");
        String switchBranch = (String) args.get("switch");
        
        if (create != null) {
            return runGit(path, "checkout", "-b", create);
        }
        if (switchBranch != null) {
            return runGit(path, "checkout", switchBranch);
        }
        return runGit(path, "branch", "-a");
    }
    
    private String gitClone(Map<String, Object> args) {
        String url = (String) args.get("url");
        String path = (String) args.get("path");
        return runGit(null, "clone", url, path);
    }
    
    private String runGit(String cwd, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (cwd != null) {
                pb.directory(new File(cwd));
            }
            
            Process process = pb.start();
            
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<String> stdout = executor.submit(() -> readStream(process.getInputStream()));
            Future<String> stderr = executor.submit(() -> readStream(process.getErrorStream()));
            
            boolean finished = process.waitFor(DEFAULT_TIMEOUT, TimeUnit.SECONDS);
            executor.shutdownNow();
            
            if (!finished) {
                process.destroyForcibly();
                return ToolRegistry.toolError("Git command timed out");
            }
            
            int exitCode = process.exitValue();
            String out = stdout.get();
            String err = stderr.get();
            
            if (exitCode != 0) {
                return ToolRegistry.toolError(err + "\n" + out);
            }
            
            return ToolRegistry.toolResult(Map.of("output", out, "stderr", err));
        } catch (Exception e) {
            return ToolRegistry.toolError("Git error: " + e.getMessage());
        }
    }
    
    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
