package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.skills.SkillManager;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill management tools.
 * Create, search, and manage self-evolving skills.
 */
public class SkillTool {
    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);
    private static final SkillManager skillManager = new SkillManager();
    
    /**
     * Register skill tools.
     */
    public static void register(ToolRegistry registry) {
        // skill_create
        registry.register(new ToolRegistry.Builder()
            .name("skill_create")
            .toolset("skills")
            .schema(Map.of(
                "description", "Create a new skill from a successful workflow",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of(
                            "type", "string",
                            "description", "Skill name (lowercase, alphanumeric with hyphens)"
                        ),
                        "description", Map.of(
                            "type", "string",
                            "description", "Brief description of what the skill does"
                        ),
                        "content", Map.of(
                            "type", "string",
                            "description", "The skill content/instructions"
                        ),
                        "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Tags for categorization"
                        )
                    ),
                    "required", List.of("name", "description", "content")
                )
            ))
            .handler(SkillTool::createSkill)
            .emoji("📝")
            .build());
        
        // skill_search
        registry.register(new ToolRegistry.Builder()
            .name("skill_search")
            .toolset("skills")
            .schema(Map.of(
                "description", "Search for existing skills",
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
                            "default", 10
                        )
                    ),
                    "required", List.of("query")
                )
            ))
            .handler(SkillTool::searchSkills)
            .emoji("🔍")
            .build());
        
        // skill_get
        registry.register(new ToolRegistry.Builder()
            .name("skill_get")
            .toolset("skills")
            .schema(Map.of(
                "description", "Get a skill by name",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of(
                            "type", "string",
                            "description", "Skill name"
                        )
                    ),
                    "required", List.of("name")
                )
            ))
            .handler(SkillTool::getSkill)
            .emoji("📄")
            .build());
        
        // skill_update
        registry.register(new ToolRegistry.Builder()
            .name("skill_update")
            .toolset("skills")
            .schema(Map.of(
                "description", "Update an existing skill",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of(
                            "type", "string",
                            "description", "Skill name"
                        ),
                        "content", Map.of(
                            "type", "string",
                            "description", "New content"
                        ),
                        "reason", Map.of(
                            "type", "string",
                            "description", "Reason for update"
                        )
                    ),
                    "required", List.of("name", "content", "reason")
                )
            ))
            .handler(SkillTool::updateSkill)
            .emoji("✏️")
            .build());
        
        // skill_delete
        registry.register(new ToolRegistry.Builder()
            .name("skill_delete")
            .toolset("skills")
            .schema(Map.of(
                "description", "Delete a skill",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of(
                            "type", "string",
                            "description", "Skill name"
                        )
                    ),
                    "required", List.of("name")
                )
            ))
            .handler(SkillTool::deleteSkill)
            .emoji("🗑️")
            .build());
        
        // skill_list
        registry.register(new ToolRegistry.Builder()
            .name("skill_list")
            .toolset("skills")
            .schema(Map.of(
                "description", "List all available skills",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "limit", Map.of(
                            "type", "integer",
                            "description", "Max results",
                            "default", 50
                        )
                    )
                )
            ))
            .handler(SkillTool::listSkills)
            .emoji("📚")
            .build());
    }
    
    /**
     * Create a new skill.
     */
    private static String createSkill(Map<String, Object> args) {
        String name = (String) args.get("name");
        String description = (String) args.get("description");
        String content = (String) args.get("content");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) args.getOrDefault("tags", List.of());
        
        if (name == null || name.trim().isEmpty()) {
            return ToolRegistry.toolError("Name is required");
        }
        
        if (description == null || description.trim().isEmpty()) {
            return ToolRegistry.toolError("Description is required");
        }
        
        if (content == null || content.trim().isEmpty()) {
            return ToolRegistry.toolError("Content is required");
        }
        
        try {
            SkillManager.Skill skill = skillManager.createSkill(
                name, description, content, tags, Map.of()
            );
            
            if (skill == null) {
                return ToolRegistry.toolError("Failed to create skill");
            }
            
            return ToolRegistry.toolResult(Map.of(
                "success", true,
                "name", skill.name,
                "description", skill.description,
                "version", skill.version,
                "created_at", skill.createdAt.toString()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to create skill: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Create failed: " + e.getMessage());
        }
    }
    
    /**
     * Search skills.
     */
    private static String searchSkills(Map<String, Object> args) {
        String query = (String) args.get("query");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 10;
        
        if (query == null || query.trim().isEmpty()) {
            return ToolRegistry.toolError("Query is required");
        }
        
        try {
            List<SkillManager.Skill> skills = skillManager.searchSkills(query);
            
            List<Map<String, Object>> results = skills.stream()
                .limit(limit)
                .map(s -> Map.of(
                    "name", s.name,
                    "description", s.description,
                    "tags", s.tags,
                    "version", s.version,
                    "usage_count", s.usageCount
                ))
                .collect(Collectors.toList());
            
            return ToolRegistry.toolResult(Map.of(
                "query", query,
                "results", results,
                "count", results.size()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to search skills: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Get a skill.
     */
    private static String getSkill(Map<String, Object> args) {
        String name = (String) args.get("name");
        
        if (name == null || name.trim().isEmpty()) {
            return ToolRegistry.toolError("Name is required");
        }
        
        try {
            SkillManager.Skill skill = skillManager.loadSkill(name);
            
            if (skill == null) {
                return ToolRegistry.toolError("Skill not found: " + name);
            }
            
            return ToolRegistry.toolResult(Map.of(
                "name", skill.name,
                "description", skill.description,
                "content", skill.content,
                "tags", skill.tags,
                "version", skill.version,
                "created_at", skill.createdAt.toString(),
                "updated_at", skill.updatedAt.toString(),
                "usage_count", skill.usageCount
            ));
            
        } catch (Exception e) {
            logger.error("Failed to get skill: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Get failed: " + e.getMessage());
        }
    }
    
    /**
     * Update a skill.
     */
    private static String updateSkill(Map<String, Object> args) {
        String name = (String) args.get("name");
        String content = (String) args.get("content");
        String reason = (String) args.get("reason");
        
        if (name == null || name.trim().isEmpty()) {
            return ToolRegistry.toolError("Name is required");
        }
        
        if (content == null || content.trim().isEmpty()) {
            return ToolRegistry.toolError("Content is required");
        }
        
        if (reason == null || reason.trim().isEmpty()) {
            return ToolRegistry.toolError("Reason is required");
        }
        
        try {
            boolean success = skillManager.updateSkill(name, content, reason);
            
            if (!success) {
                return ToolRegistry.toolError("Skill not found or update failed");
            }
            
            return ToolRegistry.toolResult(Map.of(
                "success", true,
                "name", name,
                "reason", reason
            ));
            
        } catch (Exception e) {
            logger.error("Failed to update skill: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Update failed: " + e.getMessage());
        }
    }
    
    /**
     * Delete a skill.
     */
    private static String deleteSkill(Map<String, Object> args) {
        String name = (String) args.get("name");
        
        if (name == null || name.trim().isEmpty()) {
            return ToolRegistry.toolError("Name is required");
        }
        
        try {
            boolean success = skillManager.deleteSkill(name);
            
            if (!success) {
                return ToolRegistry.toolError("Skill not found");
            }
            
            return ToolRegistry.toolResult(Map.of(
                "success", true,
                "name", name
            ));
            
        } catch (Exception e) {
            logger.error("Failed to delete skill: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Delete failed: " + e.getMessage());
        }
    }
    
    /**
     * List all skills.
     */
    private static String listSkills(Map<String, Object> args) {
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 50;
        
        try {
            List<SkillManager.Skill> skills = skillManager.listSkills();
            
            List<Map<String, Object>> results = skills.stream()
                .limit(limit)
                .map(s -> Map.of(
                    "name", s.name,
                    "description", s.description,
                    "tags", s.tags,
                    "version", s.version,
                    "usage_count", s.usageCount
                ))
                .collect(Collectors.toList());
            
            return ToolRegistry.toolResult(Map.of(
                "results", results,
                "count", results.size(),
                "total", skills.size()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to list skills: {}", e.getMessage(), e);
            return ToolRegistry.toolError("List failed: " + e.getMessage());
        }
    }
}
