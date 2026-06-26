package com.nousresearch.hermes.business.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Façade over {@link AgentTemplateLoader} that exposes agent/scenario templates
 * to the Business Portal. Eagerly loads on construction and supports
 * {@link #reload()} for hot updates.
 */
public class BusinessTemplateService {
    private static final Logger logger = LoggerFactory.getLogger(BusinessTemplateService.class);

    private final AgentTemplateLoader loader;

    public BusinessTemplateService() {
        this(new AgentTemplateLoader());
    }

    public BusinessTemplateService(AgentTemplateLoader loader) {
        this.loader = loader;
        try {
            loader.reload();
        } catch (Exception ex) {
            logger.warn("Failed to initial-load business templates: {}", ex.getMessage());
        }
    }

    public List<AgentTemplate> listAgents() {
        return loader.listAgents();
    }

    public List<AgentTemplate> listAgents(String category) {
        return loader.listAgents(category);
    }

    public Optional<AgentTemplate> getAgent(String templateId) {
        return loader.getAgent(templateId);
    }

    public List<ScenarioTemplate> listScenarios() {
        return loader.listScenarios();
    }

    public List<ScenarioTemplate> listScenarios(String category) {
        return loader.listScenarios(category);
    }

    public Optional<ScenarioTemplate> getScenario(String templateId) {
        return loader.getScenario(templateId);
    }

    public void reload() {
        loader.reload();
    }

    public AgentTemplateLoader getLoader() {
        return loader;
    }
}
