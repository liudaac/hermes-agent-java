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

    /**
     * Private constructor for loading from file.
     */
    private HermesConfig(Path configPath) {
        this.configPath = configPath;
        this.config = new HashMap<>();
    }

    /**
     * Create empty config with defaults (for fallback use).
     * Matches Python: load_config() returns DEFAULT_CONFIG when file doesn't exist.
     */
    public HermesConfig() {
        this.configPath = null;
        this.config = createDefaultConfig();
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

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = getValue(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
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

    /**
     * Get a raw nested config value by dot-separated path.
     * Public access for plugin system and other consumers.
     */
    @SuppressWarnings("unchecked")
    public Object getConfigValue(String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = config;
        
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                return null;
            }
            current = (Map<String, Object>) next;
        }
        
        return current.get(parts[parts.length - 1]);
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
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.contains(".")) {
            return getNested(key, null);
        }
        return config.get(key);
    }

    // ============ S1-1: Channel Override Support ============
    // (S1-1 code above)

    // ============ S1-3: Model Routes Support ============

    /**
     * S1-3: 从配置解析 model_routes 列表。
     */
    @SuppressWarnings("unchecked")
    public List<ModelRoute> getModelRoutes() {
        Object raw = config.get("model_routes");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<ModelRoute> routes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String alias = getStr(map, "alias");
            String model = getStr(map, "model");
            String provider = getStr(map, "provider");
            String baseUrl = getStr(map, "base-url");
            if (alias != null && model != null) {
                routes.add(new ModelRoute(alias, model, provider, baseUrl));
            }
        }
        return routes;
    }

    /**
     * S1-3: 通过别名解析 ModelRoute。
     * 优先级：session /model > model_routes alias > global default
     *
     * @param alias 请求中的 model 字段（可能是别名）
     * @param sessionModelOverride 会话级 /model 覆盖
     * @return 匹配的 ModelRoute，或 null（无匹配，用 global default）
     */
    public ModelRoute resolveModelRoute(String alias, String sessionModelOverride) {
        // 1. session /model 最高优先
        if (sessionModelOverride != null && !sessionModelOverride.isBlank()) {
            // 如果 session override 本身是个别名，也尝试解析
            for (ModelRoute route : getModelRoutes()) {
                if (route.getAlias().equalsIgnoreCase(sessionModelOverride)) {
                    return route;
                }
            }
            // 不是别名，直接用 session override 的值作为 model
            return new ModelRoute(sessionModelOverride, sessionModelOverride, null, null);
        }
        // 2. model_routes 别名查找
        if (alias != null) {
            for (ModelRoute route : getModelRoutes()) {
                if (route.getAlias().equalsIgnoreCase(alias)) {
                    return route;
                }
            }
        }
        // 3. 无匹配 → null（调用方用 global default）
        return null;
    }

    // ============ S1-1: Channel Override (continued) ============

    /**
     * S1-1: 从配置解析 channel-overrides 列表。
     *
     * <p>配置格式（application-*.yaml）：</p>
     * <pre>{@code
     * channel-overrides:
     *   - channel: feishu
     *     channel-id: "ou_xxx"      # 可选
     *     model: "doubao-pro-32k"
     *     base-url: "https://ark.cn-beijing.volces.com/api/v3"
     *     system-prompt-suffix: "你是一个飞书助手。"
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public List<ChannelOverride> getChannelOverrides() {
        Object raw = config.get("channel-overrides");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<ChannelOverride> overrides = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String channel = getStr(map, "channel");
            String channelId = getStr(map, "channel-id");
            String model = getStr(map, "model");
            String baseUrl = getStr(map, "base-url");
            String suffix = getStr(map, "system-prompt-suffix");
            if (channel != null && model != null) {
                overrides.add(new ChannelOverride(channel, channelId, model, baseUrl, suffix));
            }
        }
        return overrides;
    }

    /**
     * S1-1: 解析模型 — 4 层优先级。
     *
     * <p>优先级顺序（高 → 低）：</p>
     * <ol>
     *   <li>session /model 命令（modelOverride 字段）</li>
     *   <li>channel override（匹配 channel + channelId）</li>
     *   <li>tenant default（暂未实现，预留）</li>
     *   <li>global default（config model.model）</li>
     * </ol>
     *
     * @param channel 消息来源 channel（如 "feishu"），可为 null
     * @param channelId 消息来源 channel ID，可为 null
     * @param sessionModelOverride 会话级 /model 覆盖，可为 null
     * @return 解析后的模型名
     */
    public String resolveModel(String channel, String channelId, String sessionModelOverride) {
        // 1. session /model 最高优先
        if (sessionModelOverride != null && !sessionModelOverride.isBlank()) {
            return sessionModelOverride;
        }
        // 2. channel override
        if (channel != null) {
            for (ChannelOverride override : getChannelOverrides()) {
                if (override.matches(channel, channelId)) {
                    return override.getModel();
                }
            }
        }
        // 3. tenant default — 预留
        // 4. global default
        return getCurrentModel();
    }

    /**
     * S1-1: 解析 base URL — 同样的 4 层优先级。
     */
    public String resolveBaseUrl(String channel, String channelId, String sessionModelOverride) {
        // 1. session override
        if (baseUrlOverride != null) return baseUrlOverride;
        // 2. channel override
        if (channel != null) {
            for (ChannelOverride override : getChannelOverrides()) {
                if (override.matches(channel, channelId) && override.getBaseUrl() != null) {
                    return override.getBaseUrl();
                }
            }
        }
        // 3. global default
        return getBaseUrl();
    }

    /**
     * S1-1: 解析 system prompt suffix — channel override 提供。
     */
    public String resolveSystemPromptSuffix(String channel, String channelId) {
        if (channel == null) return null;
        for (ChannelOverride override : getChannelOverrides()) {
            if (override.matches(channel, channelId)) {
                return override.getSystemPromptSuffix();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String getStr(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    // ============ ModelConfig Support for ModelClient ============

    /**
     * Get model configuration for ModelClient.
     */
    public ModelConfig getModelConfig() {
        Map<String, Object> model = getNested("model", new HashMap<>());
        String provider = (String) model.getOrDefault("provider", "openrouter");
        String modelName = (String) model.getOrDefault("model", "anthropic/claude-3.5-sonnet");
        String apiKey = getApiKey();
        String baseUrl = (String) model.getOrDefault("base_url", getDefaultBaseUrl(provider));
        return new ModelConfig(provider, modelName, apiKey, baseUrl);
    }

    private String getDefaultBaseUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai" -> "https://api.openai.com/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "https://openrouter.ai/api/v1";
        };
    }

    /**
     * Model configuration class for ModelClient compatibility.
     */
    public static class ModelConfig {
        private final String provider;
        private final String name;
        private final String apiKey;
        private final String baseUrl;

        public ModelConfig(String provider, String name, String apiKey, String baseUrl) {
            this.provider = provider;
            this.name = name;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }

        public String getProvider() { return provider; }
        public String getName() { return name; }
        public String getApiKey() { return apiKey; }
        public String getBaseUrl() { return baseUrl; }
    }
}
