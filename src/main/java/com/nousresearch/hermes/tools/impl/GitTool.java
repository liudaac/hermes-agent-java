package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git version control tools.
 */
public class GitTool {
    private static final Logger logger = LoggerFactory.getLogger(GitTool.class);
    
    /**
     * Register Git tools.
     */
    public static void register(ToolRegistry registry) {
        // git_status
        registry.register(new ToolRegistry.Builder()
            .name("git_status")
            .toolset("git")
            .schema(Map.of(
                "description", "Check git repository status",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "Repository path (default: current)"
                        )
                    )
                )
            ))
            .handler(GitTool::gitStatus)
            .emoji("📊")
            .build());
        
        // git_log
        registry.register(new ToolRegistry.Builder()
            .name("git_log")
            .toolset("git")
            .schema(Map.of(
                "description", "View git commit history",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "Repository path"
                        ),
                        "limit", Map.of(
                            "type", "integer",
                            "description", "Number of commits",
                            "default", 10
                        )
                    )
                )
            ))
            .handler(GitTool::gitLog)
            .emoji("📜")
            .build());
        
        // git_diff
        registry.register(new ToolRegistry.Builder()
            .name("git_diff")
            .toolset("git")
            .schema(Map.of(
                "description", "Show git diff",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "Repository path"
                        ),
                        "file", Map.of(
                            "type", "string",
                            "description", "Specific file to diff"
                        )
                    )
                )
            ))
            .handler(GitTool::gitDiff)
            .emoji("📋")
            .build());
        
        // git_commit
        registry.register(new ToolRegistry.Builder()
            .name("git_commit")
            .toolset("git")
            .schema(Map.of(
                "description", "Commit changes",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "Repository path"
                        ),
                        "message", Map.of(
                            "type", "string",
                            "description", "Commit message"
                        ),
                        "files", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Files to commit (default: all)"
                        )
                    ),
                    "required", List.of("message")
                )
            ))
            .handler(GitTool::gitCommit)
            .emoji("💾")
            .build());
        
        // git_branch
        registry.register(new ToolRegistry.Builder()
            .name("git_branch")
            .toolset("git")
            .schema(Map.of(
                "description", "List or create branches",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "Repository path"
                        ),
                        "create", Map.of(
                            "type", "string",
                            "description", "Create new branch with this name"
                        ),
                        "switch", Map.of(
                            "type", "string",
                            "description", "Switch to branch"
                        )
                    )
                )
            ))
            .handler(GitTool::gitBranch)
            .emoji("🌿")
            .build());
    }
    
    private static String gitStatus(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        return runGitCommand(path, "status");
    }
    
    private static String gitLog(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 10;
        return runGitCommand(path, "log", "--oneline", "-" + limit);
    }
    
    private static String gitDiff(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        String file = (String) args.get("file");
        
        if (file != null && !file.isEmpty()) {
            return runGitCommand(path, "diff", file);
        }
        return runGitCommand(path, "diff");
    }
    
    @SuppressWarnings("unchecked")
    private static String gitCommit(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        String message = (String) args.get("message");
        List<String> files = (List<String>) args.get("files");
        
        if (message == null || message.isEmpty()) {
            return ToolRegistry.toolError("Commit message is required");
        }
        
        try {
            // Add files
            if (files != null && !files.isEmpty()) {
                for (String file : files) {
                    runGitCommand(path, "add", file);
                }
            } else {
                runGitCommand(path, "add", ".");
            }
            
            // Commit
            return runGitCommand(path, "commit", "-m", message);
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Commit failed: " + e.getMessage());
        }
    }
    
    private static String gitBranch(Map<String, Object> args) {
        String path = (String) args.getOrDefault("path", ".");
        String create = (String) args.get("create");
        String switchBranch = (String) args.get("switch");
        
        if (create != null && !create.isEmpty()) {
            return runGitCommand(path, "checkout", "-b", create);
        }
        
        if (switchBranch != null && !switchBranch.isEmpty()) {
            return runGitCommand(path, "checkout", switchBranch);
        }
        
        return runGitCommand(path, "branch", "-a");
    }
    
    private static String runGitCommand(String path, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;
            
            return ToolRegistry.toolResult(Map.of(
                "command", String.join(" ", args),
                "output", output.toString(),
                "exit_code", exitCode,
                "success", exitCode == 0
            ));
            
        } catch (Exception e) {
            logger.error("Git command failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Git command failed: " + e.getMessage());
        }
    }
}
