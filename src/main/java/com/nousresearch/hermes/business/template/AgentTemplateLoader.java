package com.nousresearch.hermes.business.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Scans {@code resources/business-templates/agents/**.yaml} and
 * {@code resources/business-templates/scenarios/**.yaml} on the classpath
 * (and optionally an override directory on disk) and parses them into
 * {@link AgentTemplate} and {@link ScenarioTemplate} objects.
 *
 * <p>Supports hot reload via {@link #reload()}.
 */
public class AgentTemplateLoader {
    private static final Logger logger = LoggerFactory.getLogger(AgentTemplateLoader.class);

    private static final String CLASSPATH_ROOT = "business-templates";
    private static final String AGENTS_DIR = "agents";
    private static final String SCENARIOS_DIR = "scenarios";

    private final Yaml yaml = new Yaml();
    private final Path overrideRoot;

    private final Map<String, AgentTemplate> agentTemplates = new LinkedHashMap<>();
    private final Map<String, ScenarioTemplate> scenarioTemplates = new LinkedHashMap<>();

    public AgentTemplateLoader() {
        this(null);
    }

    /**
     * @param overrideRoot optional filesystem directory that overrides
     *                     classpath templates (useful for dev/runtime
     *                     content updates without redeploy).
     */
    public AgentTemplateLoader(Path overrideRoot) {
        this.overrideRoot = overrideRoot;
    }

    public synchronized void reload() {
        agentTemplates.clear();
        scenarioTemplates.clear();
        loadFromClasspath();
        if (overrideRoot != null && Files.isDirectory(overrideRoot)) {
            loadFromFilesystem(overrideRoot);
        }
        logger.info("Loaded {} agent templates and {} scenario templates",
            agentTemplates.size(), scenarioTemplates.size());
    }

    public List<AgentTemplate> listAgents() {
        return new ArrayList<>(agentTemplates.values());
    }

    public List<AgentTemplate> listAgents(String category) {
        if (category == null || category.isBlank()) return listAgents();
        return agentTemplates.values().stream()
            .filter(a -> category.equalsIgnoreCase(a.getCategory()))
            .toList();
    }

    public Optional<AgentTemplate> getAgent(String templateId) {
        return Optional.ofNullable(agentTemplates.get(templateId));
    }

    public List<ScenarioTemplate> listScenarios() {
        return new ArrayList<>(scenarioTemplates.values());
    }

    public List<ScenarioTemplate> listScenarios(String category) {
        if (category == null || category.isBlank()) return listScenarios();
        return scenarioTemplates.values().stream()
            .filter(s -> category.equalsIgnoreCase(s.getCategory()))
            .toList();
    }

    public Optional<ScenarioTemplate> getScenario(String templateId) {
        return Optional.ofNullable(scenarioTemplates.get(templateId));
    }

    // ─── classpath loading ──────────────────────────────────────────────

    private void loadFromClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = AgentTemplateLoader.class.getClassLoader();
        try {
            Enumeration<URL> roots = cl.getResources(CLASSPATH_ROOT);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                try {
                    scanClasspathRoot(url);
                } catch (Exception e) {
                    logger.warn("Failed to scan template root {}: {}", url, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot enumerate classpath roots for {}: {}", CLASSPATH_ROOT, e.getMessage());
        }
    }

    private void scanClasspathRoot(URL url) throws Exception {
        if ("file".equals(url.getProtocol())) {
            Path root = Paths.get(url.toURI());
            loadFromFilesystem(root);
            return;
        }
        if ("jar".equals(url.getProtocol())) {
            String spec = url.getPath();
            int sep = spec.indexOf("!/");
            if (sep < 0) return;
            URI jarUri = URI.create("jar:" + spec.substring(0, sep));
            String inner = spec.substring(sep + 1);
            FileSystem fs;
            try {
                fs = FileSystems.newFileSystem(jarUri, Map.of());
            } catch (FileSystemAlreadyExistsException ex) {
                fs = FileSystems.getFileSystem(jarUri);
            }
            try {
                Path root = fs.getPath(inner);
                loadFromFilesystem(root);
            } finally {
                // do not close shared filesystem
            }
            return;
        }
        logger.debug("Skipping unsupported classpath root protocol {}", url);
    }

    private void loadFromFilesystem(Path root) {
        Path agentsDir = root.resolve(AGENTS_DIR);
        Path scenariosDir = root.resolve(SCENARIOS_DIR);
        if (Files.isDirectory(agentsDir)) {
            loadAgentsRecursively(agentsDir);
        }
        if (Files.isDirectory(scenariosDir)) {
            loadScenariosRecursively(scenariosDir);
        }
    }

    private void loadAgentsRecursively(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(this::isYaml).forEach(this::loadAgentFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadScenariosRecursively(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(this::isYaml).forEach(this::loadScenarioFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean isYaml(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    @SuppressWarnings("unchecked")
    private void loadAgentFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object data = yaml.load(in);
            if (!(data instanceof Map)) {
                logger.warn("Template {} is not a mapping, skipping", file);
                return;
            }
            AgentTemplate template = AgentTemplateMapper.fromMap((Map<String, Object>) data);
            if (template.getTemplateId() == null || template.getTemplateId().isBlank()) {
                logger.warn("Template {} missing template_id, skipping", file);
                return;
            }
            agentTemplates.put(template.getTemplateId(), template);
        } catch (Exception e) {
            logger.warn("Failed to parse agent template {}: {}", file, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadScenarioFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object data = yaml.load(in);
            if (!(data instanceof Map)) {
                logger.warn("Scenario template {} is not a mapping, skipping", file);
                return;
            }
            ScenarioTemplate template = ScenarioTemplateMapper.fromMap((Map<String, Object>) data);
            if (template.getTemplateId() == null || template.getTemplateId().isBlank()) {
                logger.warn("Scenario template {} missing template_id, skipping", file);
                return;
            }
            scenarioTemplates.put(template.getTemplateId(), template);
        } catch (Exception e) {
            logger.warn("Failed to parse scenario template {}: {}", file, e.getMessage());
        }
    }
}
