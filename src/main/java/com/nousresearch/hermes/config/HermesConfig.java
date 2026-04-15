package com.nousresearch.hermes.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Configuration management for Hermes Agent.
 * Loads from ~/.hermes/config.yaml with environment variable overrides.
 */
public class HermesConfig {
    private static final Logger logger = LoggerFactory.getLogger(HermesConfig.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    
    private final Path configPath;
    private Map<String, Object> config;
    
    // Runtime overrides
    private String modelOverride;
    private String baseUrlOverride;
    private String apiKeyOverride;

    private HermesConfig(Path configPath) {
        this.configPath = configPath;
        this.config = new HashMap<>();
    }
    
    /**
     * Create config with explicit values (for programmatic use).
     */
    public HermesConfig(String apiKey, String baseUrl, String model) {
        this.configPath = null;
        this.config = new HashMap<>();
        
        Map<String, Object> modelConfig = new HashMap<>();
        modelConfig.put("api_key", apiKey);
        modelConfig.put("base_url", baseUrl);
        modelConfig.put("model", model);
        this.config.put("model", modelConfig);
        
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("max_turns", 90);
        this.config.put("agent", agentConfig);
    }

    /**
     * Get the default config file path.
     */
    public static Path getConfigPath() {
        return Constants.getHermesHome().resolve("config.yaml");
    }
    
    /**
     * Get the default env file path.
     */
    public static Path getEnvPath() {
        return Constants.getHermesHome().resolve(".env");
    }

    /**
     * Load configuration from default location.
     */
    public static HermesConfig load() throws IOException {
        Path configPath = getConfigPath();
        
        HermesConfig cfg = new HermesConfig(configPath);
        
        if (Files.exists(configPath)) {
            logger.debug("Loading config from: {}", configPath);
            cfg.config = yamlMapper.readValue(configPath.toFile(), Map.class);
        } else {
            logger.info("Config file not found, using defaults");
            cfg.config = createDefaultConfig();
            cfg.save();
        }
        
        // Apply environment variable overrides
        cfg.applyEnvOverrides();
        
        return cfg;
    }

    /**
     * Create default configuration.
     */
    private static Map<String, Object> createDefaultConfig() {
        Map<String, Object> cfg = new HashMap<>();
        
        // Model configuration
        Map<String, Object> model = new HashMap<>();
        model.put("provider", "openrouter");
        model.put("model", "anthropic/claude-3.5-sonnet");
        cfg.put("model", model);
        
        // Agent configuration
        Map<String, Object> agent = new HashMap<>();
        agent.put("max_turns", Constants.DEFAULT_MAX_ITERATIONS);
        agent.put("gateway_timeout", Constants.DEFAULT_TIMEOUT_SECONDS);
        cfg.put("agent", agent);
        
        // Tools configuration
        Map<String, Object> tools = new HashMap<>();
        tools.put("enabled", Arrays.asList("web_search", "terminal", "file_operations"));
        cfg.put("tools", tools);
        
        // Display configuration
        Map<String, Object> display = new HashMap<>();
        display.put("busy_input_mode", "queue");
        cfg.put("display", display);
        
        return cfg;
    }

    /**
     * Apply environment variable overrides.
     */
    private void applyEnvOverrides() {
        // API keys
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey != null) {
            setNested("model.api_key", apiKey);
        }
        
        // Model overrides from env
        String model = System.getenv("HERMES_MODEL");
        if (model != null) {
            setNested("model.model", model);
        }
        
        String provider = System.getenv("HERMES_PROVIDER");
        if (provider != null) {
            setNested("model.provider", provider);
        }
        
        String baseUrl = System.getenv("HERMES_BASE_URL");
        if (baseUrl != null) {
            setNested("model.base_url", baseUrl);
        }
    }

    /**
     * Save configuration to file.
     */
    public void save() throws IOException {
        Files.createDirectories(configPath.getParent());
        yamlMapper.writeValue(configPath.toFile(), config);
        logger.debug("Saved config to: {}", configPath);
    }

    // Getters
    public String getCurrentModel() {
        if (modelOverride != null) return modelOverride;
        return getNested("model.model", "anthropic/claude-3.5-sonnet");
    }

    public String getProvider() {
        return getNested("model.provider", "openrouter");
    }

    public String getBaseUrl() {
        if (baseUrlOverride != null) return baseUrlOverride;
        String baseUrl = getNested("model.base_url", null);
        if (baseUrl != null) return baseUrl;
        
        // Default based on provider
        String provider = getProvider();
        return switch (provider.toLowerCase()) {
            case "openai" -> "https://api.openai.com/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "openrouter" -> Constants.OPENROUTER_BASE_URL;
            case "nous" -> Constants.NOUS_API_BASE_URL;
            default -> Constants.OPENROUTER_BASE_URL;
        };
    }

    public String getApiKey() {
        if (apiKeyOverride != null) return apiKeyOverride;
        return getNested("model.api_key", System.getenv("OPENROUTER_API_KEY"));
    }

    public int getMaxTurns() {
        return getNested("agent.max_turns", Constants.DEFAULT_MAX_ITERATIONS);
    }

    public int getGatewayTimeout() {
        return getNested("agent.gateway_timeout", Constants.DEFAULT_TIMEOUT_SECONDS);
    }

    @SuppressWarnings("unchecked")
    public List<String> getEnabledTools() {
        Object tools = config.get("tools");
        if (tools instanceof Map) {
            Object enabled = ((Map<String, Object>) tools).get("enabled");
            if (enabled instanceof List) {
                return (List<String>) enabled;
            }
        }
        return Arrays.asList("web_search", "terminal", "file_operations");
    }

    // Setters
    public void setModelOverride(String model) {
        this.modelOverride = model;
    }

    public void setBaseUrlOverride(String baseUrl) {
        this.baseUrlOverride = baseUrl;
    }

    public void setApiKeyOverride(String apiKey) {
        this.apiKeyOverride = apiKey;
    }

    public void enableToolset(String toolset) {
        List<String> enabled = getEnabledTools();
        if (!enabled.contains(toolset)) {
            enabled = new ArrayList<>(enabled);
            enabled.add(toolset);
            setNested("tools.enabled", enabled);
        }
    }

    public void disableToolset(String toolset) {
        List<String> enabled = getEnabledTools();
        if (enabled.contains(toolset)) {
            enabled = new ArrayList<>(enabled);
            enabled.remove(toolset);
            setNested("tools.enabled", enabled);
        }
    }
    
    // Auto-skill configuration
    
    /**
     * Get auto-loaded skills for a channel/chat.
     * @param channelId The channel/chat identifier
     * @return List of skill names to auto-load
     */
    @SuppressWarnings("unchecked")
    public List<String> getAutoSkills(String channelId) {
        Map<String, Object> autoSkills = getNested("auto_skill", null);
        if (autoSkills == null) {
            return List.of();
        }
        
        Object skills = autoSkills.get(channelId);
        if (skills instanceof List) {
            return (List<String>) skills;
        } else if (skills instanceof String) {
            return List.of((String) skills);
        }
        
        // Check for default auto-skills
        Object defaultSkills = autoSkills.get("default");
        if (defaultSkills instanceof List) {
            return (List<String>) defaultSkills;
        } else if (defaultSkills instanceof String) {
            return List.of((String) defaultSkills);
        }
        
        return List.of();
    }
    
    /**
     * Set auto-loaded skills for a channel/chat.
     */
    public void setAutoSkills(String channelId, List<String> skills) {
        setNested("auto_skill." + channelId, skills);
    }
    
    /**
     * Set auto-loaded skill for a channel/chat (single skill).
     */
    public void setAutoSkill(String channelId, String skill) {
        setNested("auto_skill." + channelId, skill);
    }
    
    /**
     * Remove auto-loaded skills for a channel/chat.
     */
    public void removeAutoSkills(String channelId) {
        Map<String, Object> autoSkills = getNested("auto_skill", null);
        if (autoSkills != null) {
            autoSkills.remove(channelId);
        }
    }

    // Generic getters/setters
    public String get(String key) {
        Object value = getValue(key);
        return value != null ? value.toString() : null;
    }
    
    public String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }
    
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object value = getValue(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return Collections.emptyList();
    }

    public void set(String key, String value) {
        setNested(key, value);
    }
    
    /**
     * Set a secret value in the environment file.
     */
    public void setSecret(String key, String value) {
        try {
            Path envPath = getEnvPath();
            Map<String, String> env = new HashMap<>();
            
            // Load existing env file if exists
            if (Files.exists(envPath)) {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        env.put(k, v);
                    }
                }
            }
            
            // Update or add the secret
            env.put(key, value);
            
            // Write back
            List<String> newLines = new ArrayList<>();
            newLines.add("# Hermes Environment Variables");
            newLines.add("# Generated by hermes config");
            newLines.add("");
            for (Map.Entry<String, String> entry : env.entrySet()) {
                newLines.add(entry.getKey() + "=" + entry.getValue());
            }
            
            Files.createDirectories(envPath.getParent());
            Files.write(envPath, newLines);
        } catch (Exception e) {
            logger.error("Failed to set secret: {}", e.getMessage());
            throw new RuntimeException("Failed to set secret", e);
        }
    }

    public void printAll() {
        System.out.println("Configuration:");
        printMap(config, "");
    }

    private void printMap(Map<String, Object> map, String indent) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                System.out.println(indent + entry.getKey() + ":");
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                printMap(nested, indent + "  ");
            } else {
                // Mask sensitive values
                String display = entry.getKey().contains("key") || entry.getKey().contains("secret")
                    ? "***"
                    : String.valueOf(value);
                System.out.println(indent + entry.getKey() + ": " + display);
            }
        }
    }

    // Helper methods for nested access
    @SuppressWarnings("unchecked")
    private <T> T getNested(String path, T defaultValue) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = config;
        
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                return defaultValue;
            }
            current = (Map<String, Object>) next;
        }
        
        Object value = current.get(parts[parts.length - 1]);
        if (value == null) return defaultValue;
        
        @SuppressWarnings("unchecked")
        T result = (T) value;
        return result;
    }

    @SuppressWarnings("unchecked")
    private void setNested(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = config;
        
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new HashMap<>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        
        current.put(parts[parts.length - 1], value);
    }

    private Object getValue(String key) {
        return config.get(key);
    }
}
