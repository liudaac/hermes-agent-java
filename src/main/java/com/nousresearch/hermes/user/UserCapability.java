package com.nousresearch.hermes.user;

import java.util.*;

/**
 * User-level capability set — skills and tools the user has personally
 * installed or frequently uses, independent of any space.
 *
 * <p>At runtime, the effective capability set is the union of:
 * {@code userCapabilities ∪ spaceCapabilities ∪ orgCatalog}.</p>
 */
public class UserCapability {

    private final Set<String> personalSkills;      // skill IDs installed by this user
    private final Set<String> frequentTools;        // tool names this user uses often
    private final Map<String, String> shortcuts;    // alias -> tool/skill invocation
    private final Set<String> hiddenCapabilities;   // space/org capabilities this user hides

    public UserCapability() {
        this.personalSkills = new LinkedHashSet<>();
        this.frequentTools = new LinkedHashSet<>();
        this.shortcuts = new LinkedHashMap<>();
        this.hiddenCapabilities = new LinkedHashSet<>();
    }

    public UserCapability(Set<String> personalSkills,
                          Set<String> frequentTools,
                          Map<String, String> shortcuts,
                          Set<String> hiddenCapabilities) {
        this.personalSkills = personalSkills != null ? personalSkills : new LinkedHashSet<>();
        this.frequentTools = frequentTools != null ? frequentTools : new LinkedHashSet<>();
        this.shortcuts = shortcuts != null ? shortcuts : new LinkedHashMap<>();
        this.hiddenCapabilities = hiddenCapabilities != null ? hiddenCapabilities : new LinkedHashSet<>();
    }

    public Set<String> personalSkills() { return personalSkills; }
    public Set<String> frequentTools() { return frequentTools; }
    public Map<String, String> shortcuts() { return shortcuts; }
    public Set<String> hiddenCapabilities() { return hiddenCapabilities; }

    public void addPersonalSkill(String skillId) { personalSkills.add(skillId); }
    public void removePersonalSkill(String skillId) { personalSkills.remove(skillId); }
    public void addFrequentTool(String toolName) { frequentTools.add(toolName); }
    public void addShortcut(String alias, String invocation) { shortcuts.put(alias, invocation); }
    public void removeShortcut(String alias) { shortcuts.remove(alias); }
    public void hide(String capabilityId) { hiddenCapabilities.add(capabilityId); }
    public void unhide(String capabilityId) { hiddenCapabilities.remove(capabilityId); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("personalSkills", new ArrayList<>(personalSkills));
        m.put("frequentTools", new ArrayList<>(frequentTools));
        m.put("shortcuts", new LinkedHashMap<>(shortcuts));
        m.put("hiddenCapabilities", new ArrayList<>(hiddenCapabilities));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static UserCapability fromMap(Map<String, Object> m) {
        if (m == null) return new UserCapability();
        return new UserCapability(
            new LinkedHashSet<>((List<String>) m.getOrDefault("personalSkills", List.of())),
            new LinkedHashSet<>((List<String>) m.getOrDefault("frequentTools", List.of())),
            new LinkedHashMap<>((Map<String, String>) m.getOrDefault("shortcuts", Map.of())),
            new LinkedHashSet<>((List<String>) m.getOrDefault("hiddenCapabilities", List.of()))
        );
    }
}
