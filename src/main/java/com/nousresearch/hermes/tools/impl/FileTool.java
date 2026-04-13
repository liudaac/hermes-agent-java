package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * File operations tool.
 * Read, write, and search files.
 */
public class FileTool {
    private static final Logger logger = LoggerFactory.getLogger(FileTool.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_RESULTS = 100;
    
    /**
     * Register file tools.
     */
    public static void register(ToolRegistry registry) {
        // read_file
        registry.register(new ToolEntry.Builder()
            .name("read_file")
            .toolset("file_operations")
            .schema(Map.of(
                "description", "Read contents of a file",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "File path"
                        ),
                        "offset", Map.of(
                            "type", "integer",
                            "description", "Start line (1-indexed)"
                        ),
                        "limit", Map.of(
                            "type", "integer",
                            "description", "Max lines to read"
                        )
                    ),
                    "required", List.of("path")
                )
            ))
            .handler(FileTool::readFile)
            .emoji("📄")
            .build());
        
        // write_file
        registry.register(new ToolEntry.Builder()
            .name("write_file")
            .toolset("file_operations")
            .schema(Map.of(
                "description", "Write content to a file",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of(
                            "type", "string",
                            "description", "File path"
                        ),
                        "content", Map.of(
                            "type", "string",
                            "description", "Content to write"
                        ),
                        "append", Map.of(
                            "type", "boolean",
                            "description", "Append instead of overwrite"
                        )
                    ),
                    "required", List.of("path", "content")
                )
            ))
            .handler(FileTool::writeFile)
            .emoji("✏️")
            .build());
        
        // search_files
        registry.register(new ToolEntry.Builder()
            .name("search_files")
            .toolset("file_operations")
            .schema(Map.of(
                "description", "Search for files by name pattern",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pattern", Map.of(
                            "type", "string",
                            "description", "Search pattern (glob)"
                        ),
                        "path", Map.of(
                            "type", "string",
                            "description", "Directory to search (default: .)"
                        )
                    ),
                    "required", List.of("pattern")
                )
            ))
            .handler(FileTool::searchFiles)
            .emoji("🔍")
            .build());
        
        // grep_files
        registry.register(new ToolEntry.Builder()
            .name("grep_files")
            .toolset("file_operations")
            .schema(Map.of(
                "description", "Search file contents for pattern",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pattern", Map.of(
                            "type", "string",
                            "description", "Regex pattern to search"
                        ),
                        "path", Map.of(
                            "type", "string",
                            "description", "File or directory to search"
                        ),
                        "file_pattern", Map.of(
                            "type", "string",
                            "description", "Filter files by pattern"
                        )
                    ),
                    "required", List.of("pattern", "path")
                )
            ))
            .handler(FileTool::grepFiles)
            .emoji("🔎")
            .build());
    }
    
    /**
     * Read file contents.
     */
    private static String readFile(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 1;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 1000;
        
        try {
            Path path = Paths.get(pathStr).toAbsolutePath().normalize();
            
            if (!isPathAllowed(path)) {
                return ToolRegistry.toolError("Access denied: " + path);
            }
            
            long size = Files.size(path);
            if (size > MAX_FILE_SIZE) {
                return ToolRegistry.toolError("File too large: " + size + " bytes (max: " + MAX_FILE_SIZE + ")");
            }
            
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, offset - 1);
            int end = Math.min(lines.size(), start + limit);
            
            List<String> result = lines.subList(start, end);
            String content = String.join("\n", result);
            
            return ToolRegistry.toolResult(Map.of(
                "path", path.toString(),
                "content", content,
                "total_lines", lines.size(),
                "returned_lines", result.size(),
                "offset", start + 1,
                "truncated", end < lines.size()
            ));
            
        } catch (NoSuchFileException e) {
            return ToolRegistry.toolError("File not found: " + pathStr);
        } catch (Exception e) {
            logger.error("Read file failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Read failed: " + e.getMessage());
        }
    }
    
    /**
     * Write file contents.
     */
    private static String writeFile(Map<String, Object> args) {
        String pathStr = (String) args.get("path");
        String content = (String) args.get("content");
        boolean append = args.containsKey("append") && (Boolean) args.get("append");
        
        try {
            Path path = Paths.get(pathStr).toAbsolutePath().normalize();
            
            if (!isPathAllowed(path)) {
                return ToolRegistry.toolError("Access denied: " + path);
            }
            
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            
            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8, 
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            
            return ToolRegistry.toolResult(Map.of(
                "path", path.toString(),
                "bytes_written", content.getBytes(StandardCharsets.UTF_8).length,
                "append", append,
                "success", true
            ));
            
        } catch (Exception e) {
            logger.error("Write file failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Write failed: " + e.getMessage());
        }
    }
    
    /**
     * Search files by pattern.
     */
    private static String searchFiles(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String pathStr = (String) args.getOrDefault("path", ".");
        
        try {
            Path root = Paths.get(pathStr).toAbsolutePath().normalize();
            
            if (!isPathAllowed(root)) {
                return ToolRegistry.toolError("Access denied: " + root);
            }
            
            List<String> results = new ArrayList<>();
            
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (FileSystems.getDefault()
                            .getPathMatcher("glob:" + pattern)
                            .matches(Path.of(fileName))) {
                        results.add(file.toString());
                        if (results.size() >= MAX_RESULTS) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            
            return ToolRegistry.toolResult(Map.of(
                "pattern", pattern,
                "path", root.toString(),
                "results", results,
                "count", results.size(),
                "truncated", results.size() >= MAX_RESULTS
            ));
            
        } catch (Exception e) {
            logger.error("Search files failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Grep files for pattern.
     */
    private static String grepFiles(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String pathStr = (String) args.get("path");
        String filePattern = (String) args.getOrDefault("file_pattern", "*");
        
        try {
            Path root = Paths.get(pathStr).toAbsolutePath().normalize();
            
            if (!isPathAllowed(root)) {
                return ToolRegistry.toolError("Access denied: " + root);
            }
            
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            List<Map<String, Object>> results = new ArrayList<>();
            
            if (Files.isRegularFile(root)) {
                grepFile(root, regex, results);
            } else {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName().toString();
                        if (FileSystems.getDefault()
                                .getPathMatcher("glob:" + filePattern)
                                .matches(Path.of(fileName))) {
                            grepFile(file, regex, results);
                            if (results.size() >= MAX_RESULTS) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            
            return ToolRegistry.toolResult(Map.of(
                "pattern", pattern,
                "path", root.toString(),
                "results", results,
                "count", results.size(),
                "truncated", results.size() >= MAX_RESULTS
            ));
            
        } catch (Exception e) {
            logger.error("Grep files failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Grep failed: " + e.getMessage());
        }
    }
    
    /**
     * Grep a single file.
     */
    private static void grepFile(Path file, java.util.regex.Pattern pattern, 
                                  List<Map<String, Object>> results) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    results.add(Map.of(
                        "file", file.toString(),
                        "line", i + 1,
                        "content", lines.get(i).trim()
                    ));
                    if (results.size() >= MAX_RESULTS) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("Could not read file: {}", file);
        }
    }
    
    /**
     * Check if path is allowed.
     */
    private static boolean isPathAllowed(Path path) {
        String str = path.toString().toLowerCase();
        List<String> blocked = List.of("/etc/shadow", "/etc/passwd", ".ssh/id_rsa", ".ssh/id_ed25519");
        for (String b : blocked) {
            if (str.contains(b)) {
                return false;
            }
        }
        return true;
    }
}
