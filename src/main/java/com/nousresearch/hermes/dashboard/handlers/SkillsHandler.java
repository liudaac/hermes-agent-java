package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handler for skill-related API endpoints.
 */
public class SkillsHandler {
    private static final Logger logger = LoggerFactory.getLogger(SkillsHandler.class);

    private final Path skillsDir;
    private final Map<String, SkillInfo> skills = new HashMap<>();

    public SkillsHandler() {
        this.skillsDir = Path.of(System.getProperty("user.home"), ".openclaw", "skills");
        loadSkills();
    }

    /**
     * Load skills from filesystem.
     */
    private void loadSkills() {
        skills.clear();

        // Built-in skills
        addBuiltinSkills();

        // User skills
        try {
            if (Files.exists(skillsDir)) {
                try (Stream<Path> stream = Files.list(skillsDir)) {
                    List<Path> skillDirs = stream
                        .filter(Files::isDirectory)
                        .collect(Collectors.toList());

                    for (Path dir : skillDirs) {
                        loadSkillFromDirectory(dir);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error loading skills: {}", e.getMessage());
        }
    }

    private void addBuiltinSkills() {
        addSkill("web_search", "Web search with multiple backends", "web", true, true);
        addSkill("terminal", "Execute terminal commands", "system", true, true);
        addSkill("file_operations", "Read and write files", "system", true, true);
        addSkill("browser", "Browse websites with Playwright", "web", true, true);
        addSkill("git", "Git version control", "development", true, true);
        addSkill("code_execution", "Execute Python and JavaScript", "development", true, true);
        addSkill("vision", "Analyze images", "ai", true, true);
        addSkill("tts", "Text-to-speech", "voice", true, true);
        addSkill("image_generation", "Generate images", "ai", true, true);
        addSkill("memory", "Store and retrieve memories", "memory", true, true);
        addSkill("cron", "Schedule tasks", "automation", true, true);
        addSkill("skills", "Manage skills", "system", true, true);
    }

    private void addSkill(String name, String description, String category, boolean enabled, boolean builtin) {
        SkillInfo skill = new SkillInfo();
        skill.name = name;
        skill.description = description;
        skill.category = category;
        skill.enabled = enabled;
        skill.builtin = builtin;
        skills.put(name, skill);
    }

    private void loadSkillFromDirectory(Path dir) {
        try {
            String name = dir.getFileName().toString();
            Path skillFile = dir.resolve("SKILL.md");

            if (Files.exists(skillFile)) {
                String content = Files.readString(skillFile);

                SkillInfo skill = new SkillInfo();
                skill.name = name;
                skill.description = extractDescription(content);
                skill.category = "user";
                skill.enabled = true;
                skill.builtin = false;
                skill.path = dir.toString();

                skills.put(name, skill);
            }
        } catch (Exception e) {
            logger.warn("Error loading skill from {}: {}", dir, e.getMessage());
        }
    }

    private String extractDescription(String content) {
        // Try to extract description from SKILL.md
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("description:") || line.startsWith("# ")) {
                return line.replace("description:", "")
                           .replace("#", "")
                           .trim();
            }
        }
        return "User skill";
    }

    /**
     * GET /api/skills - Get list of skills
     */
    public void getSkills(Context ctx) {
        try {
            List<Map<String, Object>> result = skills.values().stream()
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", s.name);
                    map.put("description", s.description);
                    map.put("category", s.category);
                    map.put("enabled", s.enabled);
                    map.put("builtin", s.builtin);
                    if (s.path != null) {
                        map.put("path", s.path);
                    }
                    return map;
                })
                .collect(Collectors.toList());

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error getting skills: {}", e.getMessage());
            ctx.status(500).result("Error getting skills");
        }
    }

    /**
     * PUT /api/skills/toggle - Enable or disable a skill
     */
    public void toggleSkill(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String name = body.getString("name");
            boolean enabled = body.getBooleanValue("enabled");

            if (name == null || name.isEmpty()) {
                ctx.status(400).result("Missing skill name");
                return;
            }

            SkillInfo skill = skills.get(name);
            if (skill == null) {
                ctx.status(404).result("Skill not found: " + name);
                return;
            }

            skill.enabled = enabled;

            ctx.json(Map.of("ok", true, "name", name, "enabled", enabled));
        } catch (Exception e) {
            logger.error("Error toggling skill: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    // Data class
    public static class SkillInfo {
        public String name;
        public String description;
        public String category;
        public boolean enabled;
        public boolean builtin;
        public String path;
    }
}
