package com.nousresearch.hermes.plugin.model;

import java.util.Map;

/**
 * Describes a required or optional environment variable for plugin setup.
 * Mirrors Python manifest's requires_env / optional_env items.
 */
public class EnvVarRequirement {
    private String name;
    private String description;
    private String prompt;
    private String url;
    private boolean password;

    public EnvVarRequirement() {}

    public EnvVarRequirement(String name, String description, String prompt, String url, boolean password) {
        this.name = name;
        this.description = description;
        this.prompt = prompt;
        this.url = url;
        this.password = password;
    }

    @SuppressWarnings("unchecked")
    public static EnvVarRequirement fromMap(Object obj) {
        if (obj instanceof String s) {
            return new EnvVarRequirement(s, "", s, "", false);
        }
        if (obj instanceof Map<?, ?> map) {
            EnvVarRequirement e = new EnvVarRequirement();
            e.name = stringValue(map, "name");
            e.description = stringValue(map, "description");
            e.prompt = stringValue(map, "prompt");
            e.url = stringValue(map, "url");
            e.password = booleanValue(map, "password");
            return e;
        }
        return null;
    }

    private static String stringValue(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private static boolean booleanValue(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on");
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public boolean isPassword() { return password; }
    public void setPassword(boolean password) { this.password = password; }
}
