package com.nousresearch.hermes.business.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * M4 user template repository — accepts uploads of agent/scenario templates as
 * YAML, persists them under {@link BusinessTemplateService#defaultUserRoot()}
 * and asks the {@link BusinessTemplateService} to reload.
 *
 * <p>Templates are validated against the same schema as shipped templates.
 * Author and provenance metadata is captured under {@code _meta} in the
 * stored YAML.
 */
public class UserTemplateRepository {
    private static final Logger logger = LoggerFactory.getLogger(UserTemplateRepository.class);

    private final BusinessTemplateService templateService;
    private final Path root;
    private final Yaml yaml = new Yaml();

    public UserTemplateRepository(BusinessTemplateService templateService) {
        this(templateService, templateService.getUserRoot());
    }

    public UserTemplateRepository(BusinessTemplateService templateService, Path root) {
        this.templateService = templateService;
        this.root = root;
        try {
            Files.createDirectories(root.resolve("agents"));
            Files.createDirectories(root.resolve("scenarios"));
        } catch (IOException e) {
            logger.warn("Failed to ensure user template root {}: {}", root, e.getMessage());
        }
    }

    public Path getRoot() { return root; }

    /** Upload a new agent template; returns the parsed object. */
    public AgentTemplate uploadAgent(String yamlBody, String author) {
        Map<String, Object> parsed = parseYaml(yamlBody);
        validateAgent(parsed);

        String templateId = String.valueOf(parsed.get("template_id"));
        String category = String.valueOf(parsed.getOrDefault("category", "general"));
        Path categoryDir = root.resolve("agents").resolve(safeSlug(category));
        try {
            Files.createDirectories(categoryDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        annotateMetadata(parsed, author, "user");

        Path file = categoryDir.resolve(safeSlug(templateId) + ".yaml");
        write(file, parsed);
        templateService.reload();
        logger.info("User agent template uploaded: {} by {}", templateId, author);
        return AgentTemplateMapper.fromMap(parsed);
    }

    /** Upload a new scenario template; returns the parsed object. */
    public ScenarioTemplate uploadScenario(String yamlBody, String author) {
        Map<String, Object> parsed = parseYaml(yamlBody);
        validateScenario(parsed);

        String templateId = String.valueOf(parsed.get("template_id"));
        String category = String.valueOf(parsed.getOrDefault("category", "general"));
        Path categoryDir;
        if ("cross-domain".equalsIgnoreCase(category)) {
            categoryDir = root.resolve("scenarios").resolve("cross-domain");
        } else {
            categoryDir = root.resolve("scenarios");
        }
        try {
            Files.createDirectories(categoryDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        annotateMetadata(parsed, author, "user");

        Path file = categoryDir.resolve(safeSlug(templateId) + ".yaml");
        write(file, parsed);
        templateService.reload();
        logger.info("User scenario template uploaded: {} by {}", templateId, author);
        return ScenarioTemplateMapper.fromMap(parsed);
    }

    /** List all user-uploaded files (agents + scenarios) with basic metadata. */
    public List<Map<String, Object>> listUserTemplates() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        collect(root.resolve("agents"), "agent", out);
        collect(root.resolve("scenarios"), "scenario", out);
        return out;
    }

    /** Delete a user template by templateId (returns true if removed). */
    public boolean deleteByTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) return false;
        boolean removed = removeMatching(root.resolve("agents"), templateId)
            | removeMatching(root.resolve("scenarios"), templateId);
        if (removed) {
            templateService.reload();
        }
        return removed;
    }

    // ─── internals ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Empty template body");
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            Object data = yaml.load(in);
            if (!(data instanceof Map)) {
                throw new IllegalArgumentException("Template must be a YAML mapping");
            }
            return new LinkedHashMap<>((Map<String, Object>) data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void validateAgent(Map<String, Object> data) {
        require(data, "template_id");
        require(data, "name");
        require(data, "role");
        require(data, "category");
        Object skills = data.get("skills");
        if (!(skills instanceof List) || ((List<?>) skills).size() < 3) {
            throw new IllegalArgumentException("Agent template must define ≥ 3 skills");
        }
        // template_id collision with built-in is allowed (override), but warn.
        String id = String.valueOf(data.get("template_id"));
        if (templateService.getAgent(id).isPresent()) {
            logger.info("User agent template {} overrides an existing template", id);
        }
    }

    private void validateScenario(Map<String, Object> data) {
        require(data, "template_id");
        require(data, "name");
        require(data, "category");
        String id = String.valueOf(data.get("template_id"));
        if (templateService.getScenario(id).isPresent()) {
            logger.info("User scenario template {} overrides an existing template", id);
        }
    }

    private void require(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null || (v instanceof String && ((String) v).isBlank())) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private void annotateMetadata(Map<String, Object> data, String author, String source) {
        Map<String, Object> meta;
        Object existing = data.get("_meta");
        if (existing instanceof Map) {
            meta = new LinkedHashMap<>((Map<String, Object>) existing);
        } else {
            meta = new LinkedHashMap<>();
        }
        meta.put("source", source);
        meta.put("author", author == null || author.isBlank() ? "anonymous" : author);
        meta.put("uploadedAt", Instant.now().toString());
        data.put("_meta", meta);
    }

    private void write(Path file, Map<String, Object> data) {
        try {
            Files.writeString(file, yaml.dumpAsMap(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void collect(Path dir, String type, List<Map<String, Object>> out) {
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> Files.isRegularFile(p)
                && (p.getFileName().toString().endsWith(".yaml")
                || p.getFileName().toString().endsWith(".yml")))
                .forEach(p -> {
                    try {
                        Map<String, Object> parsed = parseYaml(Files.readString(p));
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", type);
                        item.put("templateId", parsed.get("template_id"));
                        item.put("name", parsed.get("name"));
                        item.put("category", parsed.get("category"));
                        item.put("path", root.relativize(p).toString());
                        if (parsed.get("_meta") instanceof Map) {
                            item.put("meta", parsed.get("_meta"));
                        }
                        out.add(item);
                    } catch (Exception ex) {
                        logger.warn("Failed to read user template {}: {}", p, ex.getMessage());
                    }
                });
        } catch (IOException ignored) {
        }
    }

    private boolean removeMatching(Path dir, String templateId) {
        if (!Files.isDirectory(dir)) return false;
        boolean any = false;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                try {
                    Map<String, Object> parsed = parseYaml(Files.readString(p));
                    Object id = parsed.get("template_id");
                    if (templateId.equals(String.valueOf(id))) {
                        Files.deleteIfExists(p);
                        any = true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return any;
    }

    private String safeSlug(String raw) {
        if (raw == null) return "untitled";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
    }
}
