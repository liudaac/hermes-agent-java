package com.nousresearch.hermes.business.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business-facing agent role template loaded from YAML under
 * {@code resources/business-templates/agents/<category>/<id>.yaml}.
 *
 * <p>Maps to the schema documented in {@code SCHEMA.md}. Intentionally a
 * lenient bean (not a record) so we can extend without binary breakage and
 * preserve unknown metadata.
 */
public class AgentTemplate {

    /** Lifecycle status for templates surfaced in the Business Portal. */
    public enum Status {
        STABLE, BETA, EXPERIMENTAL
    }

    private String templateId;
    private String name;
    private String role;
    private String category;
    private Status status = Status.STABLE;

    // Display
    private String icon;
    private String color;
    private String mission;
    private String description;

    // Capability surface
    private List<String> skills = new ArrayList<>();
    private List<Metric> metrics = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> allowedSkills = new ArrayList<>();
    private String instructions;

    // Handoff & risk
    private HandoffPolicy handoffPolicy;
    private RiskPolicy riskPolicy = new RiskPolicy();

    // Demo
    private List<WorkflowStep> demoWorkflow = new ArrayList<>();

    // Untyped passthrough (forward-compatible)
    private Map<String, Object> raw = new LinkedHashMap<>();

    public String getTemplateId() { return templateId; }
    public AgentTemplate setTemplateId(String v) { this.templateId = v; return this; }
    public String getName() { return name; }
    public AgentTemplate setName(String v) { this.name = v; return this; }
    public String getRole() { return role; }
    public AgentTemplate setRole(String v) { this.role = v; return this; }
    public String getCategory() { return category; }
    public AgentTemplate setCategory(String v) { this.category = v; return this; }
    public Status getStatus() { return status; }
    public AgentTemplate setStatus(Status v) { this.status = v == null ? Status.STABLE : v; return this; }
    public String getIcon() { return icon; }
    public AgentTemplate setIcon(String v) { this.icon = v; return this; }
    public String getColor() { return color; }
    public AgentTemplate setColor(String v) { this.color = v; return this; }
    public String getMission() { return mission; }
    public AgentTemplate setMission(String v) { this.mission = v; return this; }
    public String getDescription() { return description; }
    public AgentTemplate setDescription(String v) { this.description = v; return this; }
    public List<String> getSkills() { return skills; }
    public AgentTemplate setSkills(List<String> v) { this.skills = v != null ? v : new ArrayList<>(); return this; }
    public List<Metric> getMetrics() { return metrics; }
    public AgentTemplate setMetrics(List<Metric> v) { this.metrics = v != null ? v : new ArrayList<>(); return this; }
    public List<String> getAllowedTools() { return allowedTools; }
    public AgentTemplate setAllowedTools(List<String> v) { this.allowedTools = v != null ? v : new ArrayList<>(); return this; }
    public List<String> getAllowedSkills() { return allowedSkills; }
    public AgentTemplate setAllowedSkills(List<String> v) { this.allowedSkills = v != null ? v : new ArrayList<>(); return this; }
    public String getInstructions() { return instructions; }
    public AgentTemplate setInstructions(String v) { this.instructions = v; return this; }
    public HandoffPolicy getHandoffPolicy() { return handoffPolicy; }
    public AgentTemplate setHandoffPolicy(HandoffPolicy v) { this.handoffPolicy = v; return this; }
    public RiskPolicy getRiskPolicy() { return riskPolicy; }
    public AgentTemplate setRiskPolicy(RiskPolicy v) { this.riskPolicy = v != null ? v : new RiskPolicy(); return this; }
    public List<WorkflowStep> getDemoWorkflow() { return demoWorkflow; }
    public AgentTemplate setDemoWorkflow(List<WorkflowStep> v) { this.demoWorkflow = v != null ? v : new ArrayList<>(); return this; }
    public Map<String, Object> getRaw() { return raw; }
    public AgentTemplate setRaw(Map<String, Object> v) { this.raw = v != null ? v : new LinkedHashMap<>(); return this; }

    /** Convert to a JSON-friendly map for API responses. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", templateId);
        m.put("name", name);
        m.put("role", role);
        m.put("category", category);
        m.put("status", status != null ? status.name() : null);
        m.put("icon", icon);
        m.put("color", color);
        m.put("mission", mission);
        m.put("description", description);
        m.put("skills", skills);
        m.put("metrics", metrics.stream().map(Metric::toMap).toList());
        m.put("allowedTools", allowedTools);
        m.put("allowedSkills", allowedSkills);
        m.put("instructions", instructions);
        if (handoffPolicy != null) m.put("handoffPolicy", handoffPolicy.toMap());
        m.put("riskPolicy", riskPolicy.toMap());
        m.put("demoWorkflow", demoWorkflow.stream().map(WorkflowStep::toMap).toList());
        return m;
    }

    /** Single metric ("label/value/unit"). */
    public static class Metric {
        private String label;
        private String value;
        private String unit;

        public Metric() {}
        public Metric(String label, String value, String unit) {
            this.label = label; this.value = value; this.unit = unit;
        }
        public String getLabel() { return label; }
        public Metric setLabel(String v) { this.label = v; return this; }
        public String getValue() { return value; }
        public Metric setValue(String v) { this.value = v; return this; }
        public String getUnit() { return unit; }
        public Metric setUnit(String v) { this.unit = v; return this; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("value", value);
            if (unit != null) m.put("unit", unit);
            return m;
        }
    }

    /** Handoff policy (where to pass the work next). */
    public static class HandoffPolicy {
        private String defaultTarget;
        private List<HandoffTrigger> triggers = new ArrayList<>();

        public String getDefaultTarget() { return defaultTarget; }
        public HandoffPolicy setDefaultTarget(String v) { this.defaultTarget = v; return this; }
        public List<HandoffTrigger> getTriggers() { return triggers; }
        public HandoffPolicy setTriggers(List<HandoffTrigger> v) { this.triggers = v != null ? v : new ArrayList<>(); return this; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("defaultTarget", defaultTarget);
            m.put("triggers", triggers.stream().map(HandoffTrigger::toMap).toList());
            return m;
        }
    }

    public static class HandoffTrigger {
        private String condition;
        private String target;
        public String getCondition() { return condition; }
        public HandoffTrigger setCondition(String v) { this.condition = v; return this; }
        public String getTarget() { return target; }
        public HandoffTrigger setTarget(String v) { this.target = v; return this; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("condition", condition);
            m.put("target", target);
            return m;
        }
    }

    /** Risk classification list (low/medium/high). */
    public static class RiskPolicy {
        private List<String> high = new ArrayList<>();
        private List<String> medium = new ArrayList<>();
        private List<String> low = new ArrayList<>();

        public List<String> getHigh() { return high; }
        public RiskPolicy setHigh(List<String> v) { this.high = v != null ? v : new ArrayList<>(); return this; }
        public List<String> getMedium() { return medium; }
        public RiskPolicy setMedium(List<String> v) { this.medium = v != null ? v : new ArrayList<>(); return this; }
        public List<String> getLow() { return low; }
        public RiskPolicy setLow(List<String> v) { this.low = v != null ? v : new ArrayList<>(); return this; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("high", high);
            m.put("medium", medium);
            m.put("low", low);
            return m;
        }
    }

    /** One step in the demo workflow timeline. */
    public static class WorkflowStep {
        private int step;
        private String actor;
        private String action;
        private String duration;

        public int getStep() { return step; }
        public WorkflowStep setStep(int v) { this.step = v; return this; }
        public String getActor() { return actor; }
        public WorkflowStep setActor(String v) { this.actor = v; return this; }
        public String getAction() { return action; }
        public WorkflowStep setAction(String v) { this.action = v; return this; }
        public String getDuration() { return duration; }
        public WorkflowStep setDuration(String v) { this.duration = v; return this; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step);
            m.put("actor", actor);
            m.put("action", action);
            m.put("duration", duration);
            return m;
        }
    }
}
