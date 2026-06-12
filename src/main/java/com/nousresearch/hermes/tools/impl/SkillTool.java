package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.skills.SkillManager;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill management tools.
 * Create, search, and manage self-evolving skills.
 */
public class SkillTool {
    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);
    private static final SkillManager skillManager = new SkillManager();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> KIMI_WEBBRIDGE_ACTIONS = List.of(
        "status", "navigate", "find_tab", "snapshot", "click", "fill", "evaluate", "screenshot",
        "network", "upload", "save_as_pdf", "list_tabs", "close_tab", "close_session"
    );

    /**
     * Register skill tools.
     */
    public static void register(ToolRegistry registry) {
        // skill_create
        registry.register(new ToolEntry.Builder()
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
        registry.register(new ToolEntry.Builder()
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
        registry.register(new ToolEntry.Builder()
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
        registry.register(new ToolEntry.Builder()
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
        registry.register(new ToolEntry.Builder()
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


        // skill_invoke
        registry.register(new ToolEntry.Builder()
            .name("skill_invoke")
            .toolset("skills")
            .schema(Map.of(
                "description", "Invoke a skill-backed capability through an explicit adapter. Currently supports kimi-webbridge via its local daemon without duplicating the skill instructions in BrowserBridge core.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "skill_name", Map.of("type", "string", "description", "Skill name, e.g. kimi-webbridge"),
                        "action", Map.of("type", "string", "description", "Skill-backed action to invoke"),
                        "args", Map.of("type", "object", "description", "Action arguments"),
                        "session", Map.of("type", "string", "description", "Session id for stateful skills"),
                        "endpoint", Map.of("type", "string", "description", "Optional local daemon endpoint override"),
                        "timeout_ms", Map.of("type", "integer", "default", 10000)
                    ),
                    "required", List.of("skill_name", "action")
                )
            ))
            .handler(SkillTool::invokeSkill)
            .risk(com.nousresearch.hermes.approval.ToolRisk.MEDIUM)
            .emoji("🎯")
            .build());

        // skill_list
        registry.register(new ToolEntry.Builder()
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

    private static Map<String, Object> skillSummaryMap(SkillManager.Skill s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", s.name);
        map.put("description", s.description != null ? s.description : "");
        map.put("tags", s.tags != null ? s.tags : List.of());
        map.put("version", s.version);
        map.put("usage_count", s.usageCount);
        if (s.source != null) map.put("source", s.source);
        if (s.path != null) map.put("path", s.path);
        return map;
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
                .map(SkillTool::skillSummaryMap)
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

            Map<String, Object> result = skillSummaryMap(skill);
            result.put("content", skill.content);
            result.put("created_at", skill.createdAt != null ? skill.createdAt.toString() : null);
            result.put("updated_at", skill.updatedAt != null ? skill.updatedAt.toString() : null);
            return ToolRegistry.toolResult(result);

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
     * Invoke a skill-backed capability. This is intentionally explicit: natural-language
     * skills remain instructions, while selected skills can expose a narrow adapter.
     */
    private static String invokeSkill(Map<String, Object> args) {
        String skillName = String.valueOf(args.getOrDefault("skill_name", "")).trim();
        String action = String.valueOf(args.getOrDefault("action", "")).trim();
        if (skillName.isEmpty()) return ToolRegistry.toolError("skill_name is required");
        if (action.isEmpty()) return ToolRegistry.toolError("action is required");

        SkillManager.Skill skill = skillManager.loadSkill(skillName);
        if (skill == null) {
            return ToolRegistry.toolError("Skill not found: " + skillName);
        }
        if ("kimi-webbridge".equalsIgnoreCase(skillName) || "webbridge".equalsIgnoreCase(skillName)) {
            return invokeKimiWebBridge(skill, action, args);
        }
        return ToolRegistry.toolError("No skill-backed invocation adapter registered for skill: " + skillName);
    }

    @SuppressWarnings("unchecked")
    private static String invokeKimiWebBridge(SkillManager.Skill skill, String action, Map<String, Object> input) {
        String normalizedAction = action.trim();
        if (!KIMI_WEBBRIDGE_ACTIONS.contains(normalizedAction)) {
            return ToolRegistry.toolError("Unsupported kimi-webbridge action: " + action + ". Supported: " + KIMI_WEBBRIDGE_ACTIONS);
        }
        String endpoint = String.valueOf(input.getOrDefault("endpoint", "http://127.0.0.1:10086")).trim();
        int timeoutMs = input.get("timeout_ms") instanceof Number n ? n.intValue() : 10000;
        timeoutMs = Math.max(1000, timeoutMs);
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
            if ("status".equals(normalizedAction)) {
                HttpRequest request = HttpRequest.newBuilder(resolve(endpoint, "/status"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return kimiResult(skill, normalizedAction, endpoint, response.statusCode(), response.body());
            }

            Map<String, Object> actionArgs = input.get("args") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new))
                : new LinkedHashMap<>();
            String session = String.valueOf(input.getOrDefault("session", "hermes-java"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", normalizedAction);
            body.put("args", actionArgs);
            body.put("session", session);

            HttpRequest request = HttpRequest.newBuilder(resolve(endpoint, "/command"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return kimiResult(skill, normalizedAction, endpoint, response.statusCode(), response.body());
        } catch (Exception e) {
            logger.warn("kimi-webbridge skill invocation failed: {}", e.getMessage());
            return ToolRegistry.toolError("kimi-webbridge invocation failed: " + e.getMessage());
        }
    }

    private static String kimiResult(SkillManager.Skill skill, String action, String endpoint, int statusCode, String body) throws Exception {
        Object parsed = parseJsonOrText(body);
        return ToolRegistry.toolResult(Map.of(
            "skill", skill.name,
            "skill_path", skill.path != null ? skill.path : "",
            "source", skill.source != null ? skill.source : "",
            "adapter", "kimi-webbridge",
            "action", action,
            "endpoint", endpoint,
            "http_status", statusCode,
            "response", parsed
        ));
    }

    private static Object parseJsonOrText(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return body;
        }
    }

    private static URI resolve(String endpoint, String path) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(base + path);
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
                .map(SkillTool::skillSummaryMap)
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
