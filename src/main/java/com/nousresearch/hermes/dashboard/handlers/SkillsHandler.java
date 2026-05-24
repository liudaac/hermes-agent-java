package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handler for global workspace skill API endpoints.
 *
 * /api/skills intentionally exposes only the shared/global workspace view.  Tenant
 * scoped skills are exposed from TenantDashboardIntegration via
 * /api/tenants/{tenantId}/skills and are backed by TenantSkillManager.
 */
public class SkillsHandler {
    private static final Logger logger = LoggerFactory.getLogger(SkillsHandler.class);

    private final Path skillsDir;
    private final Map<String, Boolean> enabledOverrides = new HashMap<>();

    public SkillsHandler() {
        this(resolveGlobalSkillsDir());
    }

    public SkillsHandler(Path skillsDir) {
        this.skillsDir = skillsDir.toAbsolutePath().normalize();
    }

    private static Path resolveGlobalSkillsDir() {
        String explicitSkillsDir = System.getenv("HERMES_SKILLS_DIR");
        if (explicitSkillsDir != null && !explicitSkillsDir.isBlank()) {
            return Path.of(explicitSkillsDir);
        }

        String workspace = System.getenv("HERMES_WORKSPACE");
        if (workspace == null || workspace.isBlank()) {
            workspace = Path.of(System.getProperty("user.home"), ".openclaw", "workspace").toString();
        }

        return Path.of(workspace).resolve("skills");
    }

    /**
     * GET /api/skills - Get global workspace skills.
     */
    public void getSkills(Context ctx) {
        try {
            List<Map<String, Object>> result = loadGlobalSkills().stream()
                .map(SkillsHandler::toMap)
                .collect(Collectors.toList());

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error getting global skills: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Error getting global skills: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/skills/toggle - Enable or disable a global workspace skill for this dashboard process.
     */
    public void toggleSkill(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String name = body.getString("name");
            boolean enabled = body.getBooleanValue("enabled");

            if (name == null || name.isBlank()) {
                ctx.status(400).json(Map.of("error", "Missing skill name"));
                return;
            }

            boolean exists = loadGlobalSkills().stream().anyMatch(s -> s.name.equals(name));
            if (!exists) {
                ctx.status(404).json(Map.of("error", "Global workspace skill not found: " + name));
                return;
            }

            enabledOverrides.put(name, enabled);

            ctx.json(Map.of(
                "ok", true,
                "name", name,
                "enabled", enabled,
                "scope", "global",
                "source", "workspace",
                "skillsDir", skillsDir.toString()
            ));
        } catch (Exception e) {
            logger.error("Error toggling global skill: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private List<SkillInfo> loadGlobalSkills() {
        if (!Files.exists(skillsDir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(skillsDir)) {
            return stream
                .filter(Files::isDirectory)
                .map(this::loadSkillFromDirectory)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(s -> s.name))
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Error loading global skills from {}: {}", skillsDir, e.getMessage());
            return List.of();
        }
    }

    private Optional<SkillInfo> loadSkillFromDirectory(Path dir) {
        try {
            String directoryName = dir.getFileName().toString();
            Path skillFile = dir.resolve("SKILL.md");

            if (!Files.exists(skillFile)) {
                return Optional.empty();
            }

            String content = Files.readString(skillFile);
            Map<String, String> frontmatter = parseFrontmatter(content);

            SkillInfo skill = new SkillInfo();
            skill.name = firstNonBlank(frontmatter.get("name"), directoryName);
            skill.description = firstNonBlank(frontmatter.get("description"), extractMarkdownTitle(content), "Global workspace skill");
            skill.category = firstNonBlank(frontmatter.get("category"), "workspace");
            skill.enabled = enabledOverrides.getOrDefault(skill.name, true);
            skill.builtin = false;
            skill.path = dir.toAbsolutePath().normalize().toString();
            skill.scope = "global";
            skill.source = "workspace";
            skill.skillsDir = skillsDir.toString();
            return Optional.of(skill);
        } catch (Exception e) {
            logger.warn("Error loading global skill from {}: {}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new HashMap<>();
        if (!content.startsWith("---\n")) {
            return result;
        }

        int end = content.indexOf("\n---", 4);
        if (end < 0) {
            return result;
        }

        String frontmatter = content.substring(4, end);
        for (String line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                result.put(key, stripQuotes(value));
            }
        }
        return result;
    }

    private static String extractMarkdownTitle(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Map<String, Object> toMap(SkillInfo s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", s.name);
        map.put("description", s.description);
        map.put("category", s.category);
        map.put("enabled", s.enabled);
        map.put("builtin", s.builtin);
        map.put("scope", s.scope);
        map.put("source", s.source);
        map.put("path", s.path);
        map.put("skillsDir", s.skillsDir);
        return map;
    }

    public static class SkillInfo {
        public String name;
        public String description;
        public String category;
        public boolean enabled;
        public boolean builtin;
        public String path;
        public String scope;
        public String source;
        public String skillsDir;
    }
}
