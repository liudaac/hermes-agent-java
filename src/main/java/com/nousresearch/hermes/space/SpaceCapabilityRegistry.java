package com.nousresearch.hermes.space;

import java.util.*;

/**
 * Space-level capability registry - the skills, tools, and templates
 * that a team has installed and shares among its members.
 *
 * <p>This is the <b>space layer</b> of the three-layer main line.
 * Capabilities here are the team foundation; users layer their own
 * personal capabilities on top at runtime.</p>
 */
public class SpaceCapabilityRegistry {

    private final Set<String> installedSkills;      // skill IDs available in this space
    private final Set<String> enabledTools;          // tool names enabled for this space
    private final Map<String, String> toolConfigs;   // tool name -> config JSON
    private final Set<String> availableTemplates;    // scenario template IDs

    public SpaceCapabilityRegistry() {
        this.installedSkills = new LinkedHashSet<>();
        this.enabledTools = new LinkedHashSet<>();
        this.toolConfigs = new LinkedHashMap<>();
        this.availableTemplates = new LinkedHashSet<>();
    }

    public Set<String> installedSkills() { return installedSkills; }
    public Set<String> enabledTools() { return enabledTools; }
    public Map<String, String> toolConfigs() { return toolConfigs; }
    public Set<String> availableTemplates() { return availableTemplates; }

    public void installSkill(String skillId) { installedSkills.add(skillId); }
    public void uninstallSkill(String skillId) { installedSkills.remove(skillId); }
    public void enableTool(String toolName) { enabledTools.add(toolName); }
    public void disableTool(String toolName) { enabledTools.remove(toolName); }
    public void addTemplate(String templateId) { availableTemplates.add(templateId); }
    public void removeTemplate(String templateId) { availableTemplates.remove(templateId); }

    public void setToolConfig(String toolName, String configJson) {
        toolConfigs.put(toolName, configJson);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installedSkills", new ArrayList<>(installedSkills));
        m.put("enabledTools", new ArrayList<>(enabledTools));
        m.put("toolConfigs", new LinkedHashMap<>(toolConfigs));
        m.put("availableTemplates", new ArrayList<>(availableTemplates));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SpaceCapabilityRegistry fromMap(Map<String, Object> m) {
        if (m == null) return new SpaceCapabilityRegistry();
        SpaceCapabilityRegistry r = new SpaceCapabilityRegistry();
        r.installedSkills.addAll((List<String>) m.getOrDefault("installedSkills", List.of()));
        r.enabledTools.addAll((List<String>) m.getOrDefault("enabledTools", List.of()));
        r.toolConfigs.putAll((Map<String, String>) m.getOrDefault("toolConfigs", Map.of()));
        r.availableTemplates.addAll((List<String>) m.getOrDefault("availableTemplates", List.of()));
        return r;
    }
}
