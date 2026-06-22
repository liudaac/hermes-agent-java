package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.approval.ToolRisk;
import java.util.*;

/**
 * Organizational role definition for an AI agent.
 * Transforms a TenantContext from a session container into a
 * job position with skills, responsibilities, and reporting lines.
 */

public class AgentRuntimeProfile {
    
    public enum Level { JUNIOR, MID, SENIOR, LEAD }
    
    private final String roleName;
    private final String description;
    private final Level level;
    
    // Capabilities (grown from skill system)
    private final Set<String> skills = new LinkedHashSet<>();
    private final Set<String> responsibilities = new LinkedHashSet<>();
    
    // Organization structure
    private String reportsTo;
    private final Set<String> collaborators = new LinkedHashSet<>();
    private final Set<String> manages = new LinkedHashSet<>();
    
    // Permission boundaries
    private final Set<String> allowedTools = new LinkedHashSet<>();
    private final Set<String> deniedTools = new LinkedHashSet<>();
    private final Set<String> toolApprovalRules = new LinkedHashSet<>();
    private final Set<String> restrictedPaths = new LinkedHashSet<>();
    private ToolRisk maxAutoRisk = ToolRisk.MEDIUM;
    
    // KPIs
    private final Map<String, Double> kpis = new LinkedHashMap<>();
    private double minTaskScore = 0.5;
    private int maxConsecutiveFailures = 3;
    
    // Dynamic metrics (populated at runtime)
    private final Map<String, Object> metrics = new LinkedHashMap<>();
    
    public AgentRuntimeProfile(String roleName, String description, Level level) {
        this.roleName = roleName;
        this.description = description;
        this.level = level;
    }
    
    // Fluent setters
    public AgentRuntimeProfile skills(String... names) { Collections.addAll(skills, names); return this; }
    public AgentRuntimeProfile responsibilities(String... items) { Collections.addAll(responsibilities, items); return this; }
    public AgentRuntimeProfile reportsTo(String id) { this.reportsTo = id; return this; }
    public AgentRuntimeProfile collaborators(String... ids) { Collections.addAll(collaborators, ids); return this; }
    public AgentRuntimeProfile manages(String... ids) { Collections.addAll(manages, ids); return this; }
    public AgentRuntimeProfile allowedTools(String... tools) { Collections.addAll(allowedTools, tools); return this; }
    public AgentRuntimeProfile deniedTools(String... tools) { Collections.addAll(deniedTools, tools); return this; }
    public AgentRuntimeProfile toolApprovalRules(String... rules) { Collections.addAll(toolApprovalRules, rules); return this; }
    public AgentRuntimeProfile restrictedPaths(String... paths) { Collections.addAll(restrictedPaths, paths); return this; }
    public AgentRuntimeProfile maxAutoRisk(ToolRisk risk) { this.maxAutoRisk = risk; return this; }
    public AgentRuntimeProfile kpi(String name, double target) { kpis.put(name, target); return this; }
    public AgentRuntimeProfile minTaskScore(double score) { this.minTaskScore = score; return this; }
    public AgentRuntimeProfile maxConsecutiveFailures(int n) { this.maxConsecutiveFailures = n; return this; }
    
    // Dynamic metric updates
    public void updateMetric(String key, Object value) { metrics.put(key, value); }
    public void removeMetric(String key) { metrics.remove(key); }
    public void addSkill(String skill) { skills.add(skill); }

    public AgentRuntimeProfile disabled(boolean disabled) { updateMetric("manual_disabled", disabled); return this; }
    public AgentRuntimeProfile deprioritize(double penalty) { updateMetric("manual_penalty", penalty); return this; }
    
    // Getters
    public String getRoleName() { return roleName; }
    public String getDescription() { return description; }
    public Level getLevel() { return level; }
    public Set<String> getSkills() { return Collections.unmodifiableSet(skills); }
    public Set<String> getResponsibilities() { return Collections.unmodifiableSet(responsibilities); }
    public String getReportsTo() { return reportsTo; }
    public Set<String> getCollaborators() { return Collections.unmodifiableSet(collaborators); }
    public Set<String> getManages() { return Collections.unmodifiableSet(manages); }
    public Set<String> getAllowedTools() { return Collections.unmodifiableSet(allowedTools); }
    public Set<String> getDeniedTools() { return Collections.unmodifiableSet(deniedTools); }
    public Set<String> getToolApprovalRules() { return Collections.unmodifiableSet(toolApprovalRules); }
    public Set<String> getRestrictedPaths() { return Collections.unmodifiableSet(restrictedPaths); }
    public ToolRisk getMaxAutoRisk() { return maxAutoRisk; }
    public Map<String, Double> getKpis() { return Collections.unmodifiableMap(kpis); }
    public double getMinTaskScore() { return minTaskScore; }
    public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public Map<String, Object> getMetrics() { return Collections.unmodifiableMap(metrics); }
    
    /** Check if this role can auto-approve an operation of given risk. */
    public boolean canAutoApprove(ToolRisk risk) {
        return risk.ordinal() <= maxAutoRisk.ordinal();
    }
    
    /** Determine if this role should escalate to a human/senior. */
    public boolean shouldEscalate(double taskScore, int consecutiveFailures) {
        return taskScore < minTaskScore || consecutiveFailures >= maxConsecutiveFailures;
    }
    
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", roleName);
        m.put("description", description);
        m.put("level", level.name());
        m.put("skills", new ArrayList<>(skills));
        m.put("responsibilities", new ArrayList<>(responsibilities));
        m.put("reports_to", reportsTo);
        m.put("collaborators", new ArrayList<>(collaborators));
        m.put("manages", new ArrayList<>(manages));
        m.put("max_auto_risk", maxAutoRisk.name());
        m.put("min_task_score", minTaskScore);
        m.put("max_consecutive_failures", maxConsecutiveFailures);
        m.put("metrics", new LinkedHashMap<>(metrics));
        return m;
    }
}