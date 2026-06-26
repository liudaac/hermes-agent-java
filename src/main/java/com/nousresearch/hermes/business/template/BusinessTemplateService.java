package com.nousresearch.hermes.business.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Façade over {@link AgentTemplateLoader} that exposes agent/scenario templates
 * to the Business Portal. Eagerly loads on construction and supports
 * {@link #reload()} for hot updates.
 *
 * <p>By default also scans {@code ~/.hermes/business-templates/} for
 * user-uploaded templates (M4 external ecosystem), with classpath shipped
 * templates taking precedence and user templates layered on top.
 */
public class BusinessTemplateService {
    private static final Logger logger = LoggerFactory.getLogger(BusinessTemplateService.class);

    public static Path defaultUserRoot() {
        return Paths.get(System.getProperty("user.home"), ".hermes", "business-templates");
    }

    private final AgentTemplateLoader loader;
    private final Path userRoot;

    public BusinessTemplateService() {
        this(defaultUserRoot());
    }

    public BusinessTemplateService(Path userRoot) {
        this(new AgentTemplateLoader(userRoot), userRoot);
    }

    public BusinessTemplateService(AgentTemplateLoader loader, Path userRoot) {
        this.loader = loader;
        this.userRoot = userRoot;
        try {
            loader.reload();
        } catch (Exception ex) {
            logger.warn("Failed to initial-load business templates: {}", ex.getMessage());
        }
    }

    public List<AgentTemplate> listAgents() { return loader.listAgents(); }
    public List<AgentTemplate> listAgents(String category) { return loader.listAgents(category); }
    public Optional<AgentTemplate> getAgent(String templateId) { return loader.getAgent(templateId); }
    public List<ScenarioTemplate> listScenarios() { return loader.listScenarios(); }
    public List<ScenarioTemplate> listScenarios(String category) { return loader.listScenarios(category); }
    public Optional<ScenarioTemplate> getScenario(String templateId) { return loader.getScenario(templateId); }
    public void reload() { loader.reload(); }
    public AgentTemplateLoader getLoader() { return loader; }
    public Path getUserRoot() { return userRoot; }
}
