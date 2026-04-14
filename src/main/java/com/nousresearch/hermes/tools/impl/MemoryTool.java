package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory management tools - aligned with Python Hermes.
 * 
 * Two parallel memory systems:
 * - MEMORY.md: Environment, system state, learned patterns
 * - USER.md: User preferences, personal info
 * 
 * Entry delimiter: § (section sign)
 */
public class MemoryTool {
    private static final Logger logger = LoggerFactory.getLogger(MemoryTool.class);
    private static final MemoryManager memoryManager = new MemoryManager();
    
    /**
     * Register memory tools.
     */
    public static void register(ToolRegistry registry) {
        // memory_save - save to MEMORY.md or USER.md
        registry.register(new ToolEntry.Builder()
            .name("memory_save")
            .toolset("memory")
            .schema(Map.of(
                "description", "Save a durable fact to memory. Use category='memory' for environment/learned info, 'user' for user preferences.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "category", Map.of(
                            "type", "string",
                            "enum", List.of("memory", "user"),
                            "description", "Memory category: 'memory' for environment/learned info, 'user' for user preferences"
                        ),
                        "content", Map.of(
                            "type", "string",
                            "description", "The fact to remember"
                        )
                    ),
                    "required", List.of("category", "content")
                )
            ))
            .handler(MemoryTool::saveMemory)
            .emoji("🧠")
            .build());
        
        // memory_search - search both MEMORY.md and USER.md
        registry.register(new ToolEntry.Builder()
            .name("memory_search")
            .toolset("memory")
            .schema(Map.of(
                "description", "Search memory for relevant facts",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of(
                            "type", "string",
                            "description", "Search query"
                        ),
                        "limit", Map.of(
                            "type", "integer",
                            "description", "Max results",
                            "default", 5
                        )
                    ),
                    "required", List.of("query")
                )
            ))
            .handler(MemoryTool::searchMemory)
            .emoji("🔍")
            .build());
        
        // memory_get - get memories by category
        registry.register(new ToolEntry.Builder()
            .name("memory_get")
            .toolset("memory")
            .schema(Map.of(
                "description", "Get memories by category",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "category", Map.of(
                            "type", "string",
                            "enum", List.of("memory", "user"),
                            "description", "Category to retrieve"
                        ),
                        "limit", Map.of(
                            "type", "integer",
                            "description", "Max results",
                            "default", 10
                        )
                    ),
                    "required", List.of("category")
                )
            ))
            .handler(MemoryTool::getMemory)
            .emoji("📋")
            .build());
        
        // memory_delete - delete by substring match
        registry.register(new ToolEntry.Builder()
            .name("memory_delete")
            .toolset("memory")
            .schema(Map.of(
                "description", "Delete a memory entry by substring match",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "category", Map.of(
                            "type", "string",
                            "enum", List.of("memory", "user"),
                            "description", "Category to delete from"
                        ),
                        "substring", Map.of(
                            "type", "string",
                            "description", "Substring to match for deletion"
                        )
                    ),
                    "required", List.of("category", "substring")
                )
            ))
            .handler(MemoryTool::deleteMemory)
            .emoji("🗑️")
            .build());
        
        // memory_replace - replace by substring match
        registry.register(new ToolEntry.Builder()
            .name("memory_replace")
            .toolset("memory")
            .schema(Map.of(
                "description", "Replace a memory entry by substring match",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "category", Map.of(
                            "type", "string",
                            "enum", List.of("memory", "user"),
                            "description", "Category to replace in"
                        ),
                        "old_substring", Map.of(
                            "type", "string",
                            "description", "Substring to match for replacement"
                        ),
                        "new_content", Map.of(
                            "type", "string",
                            "description", "New content to replace with"
                        )
                    ),
                    "required", List.of("category", "old_substring", "new_content")
                )
            ))
            .handler(MemoryTool::replaceMemory)
            .emoji("✏️")
            .build());
    }
    
    /**
     * Save memory to MEMORY.md or USER.md.
     */
    private static String saveMemory(Map<String, Object> args) {
        String category = (String) args.get("category");
        String content = (String) args.get("content");
        
        if (category == null || category.trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required (memory or user)");
        }
        
        if (content == null || content.trim().isEmpty()) {
            return ToolRegistry.toolError("Content is required");
        }
        
        try {
            boolean success;
            if ("user".equals(category)) {
                success = memoryManager.addUser(content);
            } else {
                success = memoryManager.addMemory(content);
            }
            
            if (!success) {
                return ToolRegistry.toolError("Failed to save memory (security check may have blocked it)");
            }
            
            return ToolRegistry.toolResult(Map.of(
                "success", true,
                "category", category,
                "content_preview", content.substring(0, Math.min(100, content.length()))
            ));
            
        } catch (Exception e) {
            logger.error("Failed to save memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Save failed: " + e.getMessage());
        }
    }
    
    /**
     * Search memory.
     */
    private static String searchMemory(Map<String, Object> args) {
        String query = (String) args.get("query");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
        
        if (query == null || query.trim().isEmpty()) {
            return ToolRegistry.toolError("Query is required");
        }
        
        try {
            List<String> results = memoryManager.search(query, limit);
            
            return ToolRegistry.toolResult(Map.of(
                "query", query,
                "results", results,
                "count", results.size()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to search memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Get memories by category.
     */
    private static String getMemory(Map<String, Object> args) {
        String category = (String) args.get("category");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 10;
        
        if (category == null || category.trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }
        
        try {
            List<String> results = memoryManager.getByCategory(category, limit);
            
            return ToolRegistry.toolResult(Map.of(
                "category", category,
                "results", results,
                "count", results.size()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to get memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Get failed: " + e.getMessage());
        }
    }
    
    /**
     * Delete memory by substring match.
     */
    private static String deleteMemory(Map<String, Object> args) {
        String category = (String) args.get("category");
        String substring = (String) args.get("substring");
        
        if (category == null || category.trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }
        
        if (substring == null || substring.trim().isEmpty()) {
            return ToolRegistry.toolError("Substring is required");
        }
        
        try {
            boolean deleted = memoryManager.delete(category, substring);
            
            return ToolRegistry.toolResult(Map.of(
                "success", deleted,
                "category", category,
                "substring", substring
            ));
            
        } catch (Exception e) {
            logger.error("Failed to delete memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Delete failed: " + e.getMessage());
        }
    }
    
    /**
     * Replace memory by substring match.
     */
    private static String replaceMemory(Map<String, Object> args) {
        String category = (String) args.get("category");
        String oldSubstring = (String) args.get("old_substring");
        String newContent = (String) args.get("new_content");
        
        if (category == null || category.trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }
        
        if (oldSubstring == null || oldSubstring.trim().isEmpty()) {
            return ToolRegistry.toolError("Old substring is required");
        }
        
        if (newContent == null || newContent.trim().isEmpty()) {
            return ToolRegistry.toolError("New content is required");
        }
        
        try {
            boolean replaced = memoryManager.replace(category, oldSubstring, newContent);
            
            return ToolRegistry.toolResult(Map.of(
                "success", replaced,
                "category", category,
                "old_substring", oldSubstring
            ));
            
        } catch (Exception e) {
            logger.error("Failed to replace memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Replace failed: " + e.getMessage());
        }
    }
}
