package com.nousresearch.hermes.business.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business-facing scenario template loaded from YAML under
 * {@code resources/business-templates/scenarios/*.yaml}. Bundles the team
 * blueprint, prompt assets and scenario metadata needed to clone an
 * industry-ready setup into a workspace.
 */
public class ScenarioTemplate {

    public enum Status {
        STABLE, BETA, EXPERIMENTAL
    }

    private String templateId;
    private String name;
    private String category;
    private Status status = Status.STABLE;
    private String industryTag;
    private String icon;
    private String color;
    private String summary;
    private String description;

    private List<AgentTemplate.Metric> metrics = new ArrayList<>();
    private List<InvolvedAgent> involvedAgents = new ArrayList<>();
    private CloneBlueprint cloneBlueprint = new CloneBlueprint();
    private List<TimelineEntry> workflowTimeline = new ArrayList<>();

    private Map<String, Object> raw = new LinkedHashMap<>();

    public String getTemplateId() { return templateId; }
    public ScenarioTemplate setTemplateId(String v) { this.templateId = v; return this; }
    public String getName() { return name; }
    public ScenarioTemplate setName(String v) { this.name = v; return this; }
    public String getCategory() { return category; }
    public ScenarioTemplate setCategory(String v) { this.category = v; return this; }
    public Status getStatus() { return status; }
    public ScenarioTemplate setStatus(Status v) { this.status = v == null ? Status.STABLE : v; return this; }
    public String getIndustryTag() { return industryTag; }
    public ScenarioTemplate setIndustryTag(String v) { this.industryTag = v; return this; }
    public String getIcon() { return icon; }
    public ScenarioTemplate setIcon(String v) { this.icon = v; return this; }
    public String getColor() { return color; }
    public ScenarioTemplate setColor(String v) { this.color = v; return this; }
    public String getSummary() { return summary; }
    public ScenarioTemplate setSummary(String v) { this.summary = v; return this; }
    public String getDescription() { return description; }
    public ScenarioTemplate setDescription(String v) { this.description = v; return this; }
    public List<AgentTemplate.Metric> getMetrics() { return metrics; }
    public ScenarioTemplate setMetrics(List<AgentTemplate.Metric> v) { this.metrics = v != null ? v : new ArrayList<>(); return this; }
    public List<InvolvedAgent> getInvolvedAgents() { return involvedAgents; }
    public ScenarioTemplate setInvolvedAgents(List<InvolvedAgent> v) { this.involvedAgents = v != null ? v : new ArrayList<>(); return this; }
    public CloneBlueprint getCloneBlueprint() { return cloneBlueprint; }
    public ScenarioTemplate setCloneBlueprint(CloneBlueprint v) { this.cloneBlueprint = v != null ? v : new CloneBlueprint(); return this; }
    public List<TimelineEntry> getWorkflowTimeline() { return workflowTimeline; }
    public ScenarioTemplate setWorkflowTimeline(List<TimelineEntry> v) { this.workflowTimeline = v != null ? v : new ArrayList<>(); return this; }
    public Map<String, Object> getRaw() { return raw; }
    public ScenarioTemplate setRaw(Map<String, Object> v) { this.raw = v != null ? v : new LinkedHashMap<>(); return this; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", templateId);
        m.put("name", name);
        m.put("category", category);
        m.put("status", status != null ? status.name() : null);
        m.put("industryTag", industryTag);
        m.put("icon", icon);
        m.put("color", color);
        m.put("summary", summary);
        m.put("description", description);
        m.put("metrics", metrics.stream().map(AgentTemplate.Metric::toMap).toList());
        m.put("involvedAgents", involvedAgents.stream().map(InvolvedAgent::toMap).toList());
        m.put("cloneBlueprint", cloneBlueprint.toMap());
        m.put("workflowTimeline", workflowTimeline.stream().map(TimelineEntry::toMap).toList());
        return m;
    }

    public static class InvolvedAgent {
        private String templateId;
        private String roleInScenario;
        public String getTemplateId() { return templateId; }
        public InvolvedAgent setTemplateId(String v) { this.templateId = v; return this; }
        public String getRoleInScenario() { return roleInScenario; }
        public InvolvedAgent setRoleInScenario(String v) { this.roleInScenario = v; return this; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", templateId);
            m.put("roleInScenario", roleInScenario);
            return m;
        }
    }

    public static class CloneBlueprint {
        private TeamSpec team;
        private List<PromptAssetSpec> promptAssets = new ArrayList<>();
        private ScenarioSpec scenario;

        public TeamSpec getTeam() { return team; }
        public CloneBlueprint setTeam(TeamSpec v) { this.team = v; return this; }
        public List<PromptAssetSpec> getPromptAssets() { return promptAssets; }
        public CloneBlueprint setPromptAssets(List<PromptAssetSpec> v) { this.promptAssets = v != null ? v : new ArrayList<>(); return this; }
        public ScenarioSpec getScenario() { return scenario; }
        public CloneBlueprint setScenario(ScenarioSpec v) { this.scenario = v; return this; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (team != null) m.put("team", team.toMap());
            m.put("promptAssets", promptAssets.stream().map(PromptAssetSpec::toMap).toList());
            if (scenario != null) m.put("scenario", scenario.toMap());
            return m;
        }
    }

    public static class TeamSpec {
        private String name;
        private String description;
        public String getName() { return name; }
        public TeamSpec setName(String v) { this.name = v; return this; }
        public String getDescription() { return description; }
        public TeamSpec setDescription(String v) { this.description = v; return this; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            return m;
        }
    }

    public static class PromptAssetSpec {
        private String assetId;
        private String name;
        private String purpose;
        private String content;
        public String getAssetId() { return assetId; }
        public PromptAssetSpec setAssetId(String v) { this.assetId = v; return this; }
        public String getName() { return name; }
        public PromptAssetSpec setName(String v) { this.name = v; return this; }
        public String getPurpose() { return purpose; }
        public PromptAssetSpec setPurpose(String v) { this.purpose = v; return this; }
        public String getContent() { return content; }
        public PromptAssetSpec setContent(String v) { this.content = v; return this; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assetId", assetId);
            m.put("name", name);
            m.put("purpose", purpose);
            m.put("content", content);
            return m;
        }
    }

    public static class ScenarioSpec {
        private String name;
        private String description;
        private String collaborationPattern;
        private List<String> successCriteria = new ArrayList<>();
        public String getName() { return name; }
        public ScenarioSpec setName(String v) { this.name = v; return this; }
        public String getDescription() { return description; }
        public ScenarioSpec setDescription(String v) { this.description = v; return this; }
        public String getCollaborationPattern() { return collaborationPattern; }
        public ScenarioSpec setCollaborationPattern(String v) { this.collaborationPattern = v; return this; }
        public List<String> getSuccessCriteria() { return successCriteria; }
        public ScenarioSpec setSuccessCriteria(List<String> v) { this.successCriteria = v != null ? v : new ArrayList<>(); return this; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            m.put("collaborationPattern", collaborationPattern);
            m.put("successCriteria", successCriteria);
            return m;
        }
    }

    public static class TimelineEntry {
        private String t;
        private String actor;
        private String action;
        public String getT() { return t; }
        public TimelineEntry setT(String v) { this.t = v; return this; }
        public String getActor() { return actor; }
        public TimelineEntry setActor(String v) { this.actor = v; return this; }
        public String getAction() { return action; }
        public TimelineEntry setAction(String v) { this.action = v; return this; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", t);
            m.put("actor", actor);
            m.put("action", action);
            return m;
        }
    }
}
