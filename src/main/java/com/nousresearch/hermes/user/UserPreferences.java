package com.nousresearch.hermes.user;

import java.util.*;

/**
 * User-level preferences — language, response style, approval habits,
 * and other personal behavioural settings.
 *
 * <p>Preferences are advisory (experience layer). They never override
 * space or org policies.</p>
 */
public class UserPreferences {

    private String language;           // e.g. "zh-CN", "en"
    private String responseStyle;      // "concise" | "detailed" | "structured"
    private String tone;               // "formal" | "casual" | "technical"
    private boolean autoApproveSafe;   // user willing to auto-approve safe ops
    private int maxContextChars;       // user's preferred context window
    private Map<String, Object> extra; // extensible

    public UserPreferences() {
        this.language = "zh-CN";
        this.responseStyle = "concise";
        this.tone = "casual";
        this.autoApproveSafe = true;
        this.maxContextChars = 400_000;
        this.extra = new LinkedHashMap<>();
    }

    public String language() { return language; }
    public String responseStyle() { return responseStyle; }
    public String tone() { return tone; }
    public boolean autoApproveSafe() { return autoApproveSafe; }
    public int maxContextChars() { return maxContextChars; }
    public Map<String, Object> extra() { return extra; }

    public void setLanguage(String language) { this.language = language; }
    public void setResponseStyle(String responseStyle) { this.responseStyle = responseStyle; }
    public void setTone(String tone) { this.tone = tone; }
    public void setAutoApproveSafe(boolean v) { this.autoApproveSafe = v; }
    public void setMaxContextChars(int n) { this.maxContextChars = n; }
    public void setExtra(String key, Object value) { extra.put(key, value); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", language);
        m.put("responseStyle", responseStyle);
        m.put("tone", tone);
        m.put("autoApproveSafe", autoApproveSafe);
        m.put("maxContextChars", maxContextChars);
        m.put("extra", new LinkedHashMap<>(extra));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static UserPreferences fromMap(Map<String, Object> m) {
        if (m == null) return new UserPreferences();
        UserPreferences p = new UserPreferences();
        p.language = (String) m.getOrDefault("language", "zh-CN");
        p.responseStyle = (String) m.getOrDefault("responseStyle", "concise");
        p.tone = (String) m.getOrDefault("tone", "casual");
        p.autoApproveSafe = (Boolean) m.getOrDefault("autoApproveSafe", true);
        p.maxContextChars = ((Number) m.getOrDefault("maxContextChars", 400_000)).intValue();
        p.extra = new LinkedHashMap<>((Map<String, Object>) m.getOrDefault("extra", Map.of()));
        return p;
    }
}
