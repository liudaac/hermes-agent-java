package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Memory management tools.
 * Save, search, and manage persistent memory.
 */
public class MemoryTool {
    private static final Logger logger = LoggerFactory.getLogger(MemoryTool.class);
    private static final MemoryManager memoryManager = new MemoryManager();
    
    /**
     * Register memory tools.
     */
    public static void register(ToolRegistry registry) {
        // memory_save
        registry.register(new ToolRegistry.Builder()
            .name("memory_save")
            .toolset("memory")
            .schema(Map.of(
                "description", "Save a durable fact to memory",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "category", Map.of(
                            "type", "string",
                            "description", "Memory category (e.g., user_preferences, environment, learned)"
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
        
        // memory_search
        registry.register(new ToolRegistry.Builder()
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
        
        // session_search
        registry.register(new ToolRegistry.Builder()
            .name("session_search")
            .toolset("memory")
            .schema(Map.of(
                "description", "Search past conversation sessions",
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
            .handler(MemoryTool::searchSessions)
            .emoji("📜")
            .build());
        
        // memory_delete
        registry.register(new ToolRegistry.Builder()
            .name("memory_delete")
            .toolset("memory")
            .schema(Map.of(
                "description", "Delete a memory entry",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "id", Map.of(
                            "type", "string",
                            "description", "Memory ID to delete"
                        )
                    ),
                    "required", List.of("id")
                )
            ))
            .handler(MemoryTool::deleteMemory)
            .emoji("🗑️")
            .build());
    }
    
    /**
     * Save memory.
     */
    private static String saveMemory(Map<String, Object> args) {
        String category = (String) args.get("category");
        String content = (String) args.get("content");
        
        if (category == null || category.trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }
        
        if (content == null || content.trim().isEmpty()) {
            return ToolRegistry.toolError("Content is required");
        }
        
        try {
            memoryManager.save(category, content, Map.of("source", "tool"));
            
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
            List<MemoryManager.MemoryEntry> results = memoryManager.search(query, limit);
            
            List<Map<String, Object>> formatted = results.stream()
                .map(m -> Map.of(
                    "id", m.id,
                    "category", m.category,
                    "content", m.content,
                    "timestamp", m.timestamp.toString()
                ))
                .toList();
            
            return ToolRegistry.toolResult(Map.of(
                "query", query,
                "results", formatted,
                "count", formatted.size()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to search memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Search sessions.
     */
    private static String searchSessions(Map<String, Object> args) {
        String query = (String) args.get("query");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
        
        if (query == null || query.trim().isEmpty()) {
            return ToolRegistry.toolError("Query is required");
        }
        
        try {
            List<MemoryManager.SessionSearchResult> results = memoryManager.searchSessions(query, limit);
            
            List<Map<String, Object>> formatted = results.stream()
                .map(r -> Map.of(
                    "session_id", r.sessionId,
                    "role", r.role,
                    "content_preview", r.content.substring(0, Math.min(200, r.content.length())),
                    "timestamp", r.timestamp.toString()
                ))
                .toList();
            
            return ToolRegistry.toolResult(Map.of(
                "query", query,
                "results", formatted,
                "count", formatted.size()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to search sessions: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Delete memory.
     */
    private static String deleteMemory(Map<String, Object> args) {
        String id = (String) args.get("id");
        
        if (id == null || id.trim().isEmpty()) {
            return ToolRegistry.toolError("ID is required");
        }
        
        try {
            boolean deleted = memoryManager.delete(id);
            
            return ToolRegistry.toolResult(Map.of(
                "success", deleted,
                "id", id
            ));
            
        } catch (Exception e) {
            logger.error("Failed to delete memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Delete failed: " + e.getMessage());
        }
    }
}
