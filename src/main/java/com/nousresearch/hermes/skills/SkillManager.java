package com.nousresearch.hermes.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nousresearch.hermes.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill management system - aligned with Python Hermes.
 *
 * Directory Structure:
 * ~/.hermes/skills/
 * ├── my-skill/
 * │   ├── SKILL.md           # Main instructions (required)
 * │   ├── references/        # Supporting documentation
 * │   │   ├── api.md
 * │   │   └── examples.md
 * │   ├── templates/         # Templates for output
 * │   │   └── template.md
 * │   └── assets/            # Supplementary files
 * └── category/
 *     └── another-skill/
 *         └── SKILL.md
 *
 * SKILL.md Format (YAML Frontmatter, agentskills.io compatible):
 * ---
 * name: skill-name
 * description: Brief description
 * version: 1.0.0
 * tags: [tag1, tag2]
 * ---
 *
 * # Skill Title
 * Full instructions here...
 */
public class SkillManager {
    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // Excluded directories
    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", ".github", ".hub");

    private final Path skillsDir;
    private final Path builtinSkillsDir;
    private final List<Path> externalSkillDirs;

    public SkillManager() {
        this.skillsDir = Constants.getHermesHome().resolve("skills");
        this.builtinSkillsDir = Path.of("skills"); // Relative to working dir
        this.externalSkillDirs = resolveExternalSkillDirs();

        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            logger.error("Failed to create skills directory: {}", e.getMessage());
        }
    }

    /**
     * Create a new skill from a successful workflow.
     * Creates directory structure: ~/.hermes/skills/{name}/SKILL.md
     */
    public Skill createSkill(String name, String description, String content,
                            List<String> tags, Map<String, Object> metadata) {
        try {
            // Validate name
            String safeName = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");

            Skill skill = new Skill();
            skill.name = safeName;
            skill.description = description;
            skill.content = content;
            skill.tags = tags != null ? tags : new ArrayList<>();
            skill.metadata = metadata != null ? metadata : new HashMap<>();
            skill.createdAt = Instant.now();
            skill.updatedAt = Instant.now();
            skill.version = 1;
            skill.usageCount = 0;

            // Create skill directory
            Path skillDir = skillsDir.resolve(safeName);
            Files.createDirectories(skillDir);

            // Create subdirectories
            Files.createDirectories(skillDir.resolve("references"));
            Files.createDirectories(skillDir.resolve("templates"));
            Files.createDirectories(skillDir.resolve("assets"));

            // Save main SKILL.md
            Path skillFile = skillDir.resolve("SKILL.md");
            saveSkillToFile(skill, skillFile);

            logger.info("Created skill: {} at {}", safeName, skillDir);
            return skill;

        } catch (Exception e) {
            logger.error("Failed to create skill: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Load a skill by name.
     * Searches ~/.hermes/skills/{name}/SKILL.md
     */
    public Skill loadSkill(String name) {
        String safeName = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");

        // Check user skills first
        Path userSkillDir = skillsDir.resolve(safeName);
        Path userSkillFile = userSkillDir.resolve("SKILL.md");
        if (Files.exists(userSkillFile)) {
            return loadSkillFromFile(userSkillFile, "hermes");
        }

        // Check external skill directories (OpenClaw, workspace, configured paths)
        for (Path dir : externalSkillDirs) {
            Path externalSkillFile = dir.resolve(safeName).resolve("SKILL.md");
            if (Files.exists(externalSkillFile)) {
                return loadSkillFromFile(externalSkillFile, "external");
            }
        }

        // Check builtin skills
        Path builtinSkillDir = builtinSkillsDir.resolve(safeName);
        Path builtinSkillFile = builtinSkillDir.resolve("SKILL.md");
        if (Files.exists(builtinSkillFile)) {
            return loadSkillFromFile(builtinSkillFile, "builtin");
        }

        return null;
    }

    /**
     * List all available skills.
     */
    public List<Skill> listSkills() {
        List<Skill> skills = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        // Load user skills
        if (Files.exists(skillsDir)) {
            try (Stream<Path> paths = Files.list(skillsDir)) {
                paths.filter(p -> Files.isDirectory(p))
                    .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        Path skillFile = p.resolve("SKILL.md");
                        if (Files.exists(skillFile)) {
                            Skill skill = loadSkillFromFile(skillFile, "hermes");
                            if (skill != null) {
                                skills.add(skill);
                                seenNames.add(skill.name);
                            }
                        }
                    });
            } catch (Exception e) {
                logger.debug("Failed to list user skills: {}", e.getMessage());
            }
        }

        // Load external skills (OpenClaw, workspace, configured paths)
        for (Path dir : externalSkillDirs) {
            loadSkillsFromDirectory(dir, "external", skills, seenNames);
        }

        // Load builtin skills
        loadSkillsFromDirectory(builtinSkillsDir, "builtin", skills, seenNames);

        return skills.stream()
            .sorted((a, b) -> b.updatedAt.compareTo(a.updatedAt))
            .collect(Collectors.toList());
    }

    /**
     * Update an existing skill.
     */
    public boolean updateSkill(String name, String content, String reason) {
        Skill skill = loadSkill(name);
        if (skill == null) {
            return false;
        }

        try {
            skill.content = content;
            skill.updatedAt = Instant.now();
            skill.version++;

            if (skill.metadata == null) {
                skill.metadata = new HashMap<>();
            }
            skill.metadata.put("last_patch_reason", reason);
            skill.metadata.put("last_patch_time", Instant.now().toString());

            Path skillDir = skillsDir.resolve(skill.name);
            Path skillFile = skillDir.resolve("SKILL.md");
            saveSkillToFile(skill, skillFile);

            logger.info("Updated skill: {} (v{})", skill.name, skill.version);
            return true;

        } catch (Exception e) {
            logger.error("Failed to update skill: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Delete a skill.
     */
    public boolean deleteSkill(String name) {
        try {
            String safeName = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
            Path skillDir = skillsDir.resolve(safeName);
            if (Files.exists(skillDir)) {
                deleteDirectory(skillDir);
                logger.info("Deleted skill: {}", safeName);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error("Failed to delete skill: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Search skills by tags or content.
     */
    public List<Skill> searchSkills(String query) {
        String lowerQuery = query.toLowerCase();

        return listSkills().stream()
            .filter(s ->
                s.name.toLowerCase().contains(lowerQuery) ||
                (s.description != null && s.description.toLowerCase().contains(lowerQuery)) ||
                s.tags.stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery)) ||
                s.content.toLowerCase().contains(lowerQuery)
            )
            .collect(Collectors.toList());
    }

    /**
     * Get skills relevant to a task.
     */
    public List<Skill> getRelevantSkills(String taskDescription, int limit) {
        String lowerTask = taskDescription.toLowerCase();

        return listSkills().stream()
            .sorted((a, b) -> {
                int scoreA = scoreRelevance(a, lowerTask);
                int scoreB = scoreRelevance(b, lowerTask);
                return Integer.compare(scoreB, scoreA);
            })
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Increment usage count for a skill.
     */
    public void recordUsage(String name) {
        Skill skill = loadSkill(name);
        if (skill != null) {
            skill.usageCount++;
            skill.metadata.put("last_used", Instant.now().toString());

            try {
                Path skillDir = skillsDir.resolve(skill.name);
                Path skillFile = skillDir.resolve("SKILL.md");
                saveSkillToFile(skill, skillFile);
            } catch (Exception e) {
                logger.debug("Failed to update usage count: {}", e.getMessage());
            }
        }
    }

    // Helper methods

    private void saveSkillToFile(Skill skill, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.name).append("\n");
        sb.append("description: ").append(skill.description).append("\n");
        sb.append("version: ").append(skill.version).append("\n");
        sb.append("created_at: ").append(skill.createdAt).append("\n");
        sb.append("updated_at: ").append(skill.updatedAt).append("\n");
        sb.append("tags: [").append(String.join(", ", skill.tags)).append("]\n");
        sb.append("usage_count: ").append(skill.usageCount).append("\n");
        if (skill.metadata != null && !skill.metadata.isEmpty()) {
            sb.append("metadata:\n");
            for (Map.Entry<String, Object> entry : skill.metadata.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        sb.append("---\n\n");
        sb.append(skill.content);

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private void loadSkillsFromDirectory(Path dir, String source, List<Skill> skills, Set<String> seenNames) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(Files::isDirectory)
                .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
                .forEach(p -> {
                    Path skillFile = p.resolve("SKILL.md");
                    if (Files.exists(skillFile)) {
                        Skill skill = loadSkillFromFile(skillFile, source);
                        if (skill != null && seenNames.add(skill.name)) {
                            skills.add(skill);
                        }
                    }
                });
        } catch (Exception e) {
            logger.debug("Failed to list {} skills from {}: {}", source, dir, e.getMessage());
        }
    }

    private Skill loadSkillFromFile(Path file) {
        return loadSkillFromFile(file, "unknown");
    }

    private Skill loadSkillFromFile(Path file, String source) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Parse frontmatter
            Pattern pattern = Pattern.compile("^---\\n(.*?)\\n---\\n\\n(.*)$", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(content);

            if (!matcher.find()) {
                // No frontmatter, treat entire content as skill content
                Skill skill = new Skill();
                skill.name = file.getParent().getFileName().toString();
                skill.content = content;
                skill.path = file.getParent().toAbsolutePath().normalize().toString();
                skill.source = source;
                skill.createdAt = Files.getLastModifiedTime(file).toInstant();
                skill.updatedAt = skill.createdAt;
                return skill;
            }

            String frontmatter = matcher.group(1);
            String skillContent = matcher.group(2);

            Skill skill = new Skill();
            skill.content = skillContent;
            skill.path = file.getParent().toAbsolutePath().normalize().toString();
            skill.source = source;

            // Parse frontmatter lines
            for (String line : frontmatter.split("\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();

                    switch (key) {
                        case "name": skill.name = value; break;
                        case "description": skill.description = value; break;
                        case "version": skill.version = parseVersion(value); break;
                        case "created_at": skill.createdAt = parseInstantOrNull(value); break;
                        case "updated_at": skill.updatedAt = parseInstantOrNull(value); break;
                        case "tags":
                            skill.tags = Arrays.asList(value.replace("[", "").replace("]", "").split(",\\s*"));
                            break;
                        case "usage_count": skill.usageCount = parseVersion(value); break;
                    }
                }
            }
            if (skill.name == null || skill.name.isBlank()) skill.name = file.getParent().getFileName().toString();
            if (skill.description == null) skill.description = "";
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            if (skill.createdAt == null) skill.createdAt = modified;
            if (skill.updatedAt == null) skill.updatedAt = modified;

            return skill;

        } catch (Exception e) {
            logger.error("Failed to load skill from {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseVersion(String value) {
        if (value == null || value.isBlank()) return 1;
        try {
            String numeric = value.trim().replaceAll("^[^0-9]*", "").replaceAll("[^0-9].*$", "");
            return numeric.isBlank() ? 1 : Integer.parseInt(numeric);
        } catch (Exception e) {
            return 1;
        }
    }

    private List<Path> resolveExternalSkillDirs() {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        addConfiguredSkillPaths(dirs, System.getProperty("hermes.skills.paths"));
        addConfiguredSkillPaths(dirs, System.getenv("HERMES_SKILLS_PATHS"));
        addConfiguredSkillPaths(dirs, System.getenv("HERMES_EXTRA_SKILLS_DIRS"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            dirs.add(Paths.get(home, ".openclaw", "skills"));
            dirs.add(Paths.get(home, ".openclaw", "workspace", "skills"));
        }
        return dirs.stream()
            .map(p -> p.toAbsolutePath().normalize())
            .filter(p -> !p.equals(skillsDir.toAbsolutePath().normalize()))
            .collect(Collectors.toList());
    }

    private static void addConfiguredSkillPaths(Set<Path> dirs, String value) {
        if (value == null || value.isBlank()) return;
        for (String part : value.split(Pattern.quote(File.pathSeparator))) {
            if (part != null && !part.isBlank()) {
                dirs.add(Paths.get(part.trim()));
            }
        }
    }

    public List<Path> getSearchPaths() {
        List<Path> paths = new ArrayList<>();
        paths.add(skillsDir.toAbsolutePath().normalize());
        paths.addAll(externalSkillDirs);
        paths.add(builtinSkillsDir.toAbsolutePath().normalize());
        return Collections.unmodifiableList(paths);
    }

    private int scoreRelevance(Skill skill, String task) {
        int score = 0;

        // Name match
        if (task.contains(skill.name.toLowerCase())) {
            score += 10;
        }

        // Description match
        if (skill.description != null && task.contains(skill.description.toLowerCase())) {
            score += 5;
        }

        // Tag match
        for (String tag : skill.tags) {
            if (task.contains(tag.toLowerCase())) {
                score += 3;
            }
        }

        // Content match
        if (skill.content.toLowerCase().contains(task)) {
            score += 2;
        }

        // Usage bonus
        score += Math.min(skill.usageCount / 10, 5);

        return score;
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         logger.warn("Failed to delete: {}", p);
                     }
                 });
        }
    }

    // Data class
    public static class Skill {
        public String name;
        public String description;
        public String content;
        public List<String> tags = new ArrayList<>();
        public Map<String, Object> metadata = new HashMap<>();
        public Instant createdAt;
        public Instant updatedAt;
        public int version = 1;
        public int usageCount = 0;
        public String path;
        public String source;

        // S5-1: 来源追溯 + 生命周期管理
        /** Skill 来源（USER / AGENT / IMPORT / BUNDLED） */
        public SkillProvenance provenance = SkillProvenance.USER;
        /** 最近使用时间（用于 stale/archived 判断） */
        public Instant lastUsedAt;
        /** 是否被 pin（pinned skill 跳过所有生命周期转换） */
        public boolean pinned = false;
        /** 生命周期状态（active / stale / archived） */
        public SkillLifecycleStatus lifecycleStatus = SkillLifecycleStatus.ACTIVE;
    }
}
