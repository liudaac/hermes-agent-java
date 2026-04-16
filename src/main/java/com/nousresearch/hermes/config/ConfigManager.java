package com.nousresearch.hermes.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced configuration manager with environment variable bridging,
 * nested access, and type-safe getters.
 * Mirrors Python's config.yaml + env var bridging system.
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    
    private static ConfigManager instance;
    
    private Path configPath;
    private ObjectNode config;
    private final Map<String, String> envOverrides = new HashMap<>();
    private final Set<String> sensitiveKeys = Set.of(
        "api_key", "api_secret", "secret", "password", "token", "key"
    );
    
    private ConfigManager() {}
    
    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }
    
    /**
     * Load configuration from default location.
     */
    public void load() throws IOException {
        load(Constants.getHermesHome().resolve("config.yaml"));
    }
    
    /**
     * Load configuration from specific path.
     */
    public void load(Path path) throws IOException {
        this.configPath = path;
        
        if (Files.exists(path)) {
            logger.debug("Loading config from: {}", path);
            config = (ObjectNode) yamlMapper.readTree(path.toFile());
            // Expand environment variables
            expandEnvVars(config);
        } else {
            logger.info("Config file not found, creating default");
            config = createDefaultConfig();
            save();
        }
        
        // Bridge config values to environment variables
        bridgeToEnv();
    }
    
    /**
     * Create default configuration matching Python version.
     */
    private ObjectNode createDefaultConfig() {
        ObjectNode cfg = jsonMapper.createObjectNode();
        
        // Model configuration
        ObjectNode model = cfg.putObject("model");
        model.put("provider", "openrouter");
        model.put("model", "anthropic/claude-3.5-sonnet");
        model.put("base_url", "");
        model.put("api_key", "");
        
        // Agent configuration
        ObjectNode agent = cfg.putObject("agent");
        agent.put("max_turns", Constants.DEFAULT_MAX_ITERATIONS);
        agent.put("gateway_timeout", Constants.DEFAULT_TIMEOUT_SECONDS);
        agent.put("gateway_timeout_warning", 300);
        agent.put("restart_drain_timeout", 60);
        agent.put("compression_threshold", 4000);
        agent.put("compression_target", 2000);
        
        // Tools configuration
        ObjectNode tools = cfg.putObject("tools");
        tools.putArray("enabled").add("web_search").add("terminal").add("file_operations");
        
        // Terminal configuration
        ObjectNode terminal = cfg.putObject("terminal");
        terminal.put("backend", "local");
        terminal.put("cwd", "");
        terminal.put("timeout", 300);
        terminal.put("lifetime_seconds", 3600);
        terminal.put("docker_image", "hermes-sandbox:latest");
        terminal.put("ssh_host", "");
        terminal.put("ssh_user", "");
        terminal.put("ssh_port", 22);
        terminal.put("persistent_shell", true);
        
        // Display configuration
        ObjectNode display = cfg.putObject("display");
        display.put("busy_input_mode", "queue");
        display.put("show_thinking", false);
        display.put("streaming", true);
        
        // Web configuration
        ObjectNode web = cfg.putObject("web");
        web.put("backend", "firecrawl");
        web.put("cache_ttl", 3600);
        
        // Compression configuration
        ObjectNode compression = cfg.putObject("compression");
        compression.put("enabled", true);
        compression.put("provider", "openrouter");
        compression.put("model", "google/gemini-flash-1.5");
        
        // Auxiliary tasks configuration
        ObjectNode auxiliary = cfg.putObject("auxiliary");
        
        ObjectNode vision = auxiliary.putObject("vision");
        vision.put("provider", "auto");
        vision.put("model", "");
        
        ObjectNode webExtract = auxiliary.putObject("web_extract");
        webExtract.put("provider", "auto");
        webExtract.put("model", "");
        
        // Memory configuration (aligned with Python Hermes)
        ObjectNode memory = cfg.putObject("memory");
        memory.put("memory_enabled", false);
        memory.put("user_profile_enabled", false);
        memory.put("nudge_interval", 10);           // Nudge every 10 user turns
        memory.put("flush_min_turns", 6);
        memory.put("memory_char_limit", 2200);
        memory.put("user_char_limit", 1375);
        memory.put("provider", "");                  // External memory provider (e.g., "honcho")
        
        // Skills configuration (aligned with Python Hermes)
        ObjectNode skills = cfg.putObject("skills");
        skills.put("creation_nudge_interval", 10);   // Nudge every 10 tool iterations
        skills.putArray("auto_load");                // Skills to auto-load per channel
        
        return cfg;
    }
    
    /**
     * Expand ${ENV_VAR} references in config values.
     */
    private void expandEnvVars(ObjectNode node) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            
            if (value.isTextual()) {
                String text = value.asText();
                Matcher matcher = ENV_VAR_PATTERN.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String varName = matcher.group(1);
                    String varValue = System.getenv(varName);
                    if (varValue == null) {
                        varValue = "";
                    }
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(varValue));
                }
                matcher.appendTail(sb);
                if (!sb.toString().equals(text)) {
                    node.put(entry.getKey(), sb.toString());
                }
            } else if (value.isObject()) {
                expandEnvVars((ObjectNode) value);
            }
        }
    }
    
    /**
     * Bridge config values to environment variables.
     * Mirrors Python's env bridging in gateway/run.py.
     */
    private void bridgeToEnv() {
        // Terminal config bridging
        Map<String, String> terminalEnvMap = Map.of(
            "backend", "TERMINAL_ENV",
            "cwd", "TERMINAL_CWD",
            "timeout", "TERMINAL_TIMEOUT",
            "lifetime_seconds", "TERMINAL_LIFETIME_SECONDS",
            "docker_image", "TERMINAL_DOCKER_IMAGE",
            "ssh_host", "TERMINAL_SSH_HOST",
            "ssh_user", "TERMINAL_SSH_USER",
            "ssh_port", "TERMINAL_SSH_PORT",
            "persistent_shell", "TERMINAL_PERSISTENT_SHELL"
        );
        
        ObjectNode terminal = (ObjectNode) config.get("terminal");
        if (terminal != null) {
            for (Map.Entry<String, String> entry : terminalEnvMap.entrySet()) {
                JsonNode value = terminal.get(entry.getKey());
                if (value != null && !value.isNull()) {
                    String envVar = entry.getValue();
                    if (System.getenv(envVar) == null) {
                        envOverrides.put(envVar, value.asText());
                    }
                }
            }
        }
        
        // Agent config bridging
        if (config.has("agent")) {
            ObjectNode agent = (ObjectNode) config.get("agent");
            if (agent.has("max_turns")) {
                envOverrides.putIfAbsent("HERMES_MAX_ITERATIONS", agent.get("max_turns").asText());
            }
            if (agent.has("gateway_timeout")) {
                envOverrides.putIfAbsent("HERMES_AGENT_TIMEOUT", agent.get("gateway_timeout").asText());
            }
        }
        
        // Display config bridging
        if (config.has("display")) {
            ObjectNode display = (ObjectNode) config.get("display");
            if (display.has("busy_input_mode")) {
                envOverrides.putIfAbsent("HERMES_GATEWAY_BUSY_INPUT_MODE", display.get("busy_input_mode").asText());
            }
        }
    }
    
    /**
     * Save configuration to file.
     */
    public void save() throws IOException {
        Files.createDirectories(configPath.getParent());
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
        logger.debug("Saved config to: {}", configPath);
    }
    
    // ==================== Type-safe Getters ====================
    
    /**
     * Get string value at path.
     */
    public String getString(String path, String defaultValue) {
        JsonNode node = getNodeAtPath(path);
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        return defaultValue;
    }
    
    /**
     * Get string value at path.
     */
    public String getString(String path) {
        return getString(path, null);
    }
    
    /**
     * Get integer value at path.
     */
    public int getInt(String path, int defaultValue) {
        JsonNode node = getNodeAtPath(path);
        if (node != null && node.isNumber()) {
            return node.asInt();
        }
        return defaultValue;
    }
    
    /**
     * Get boolean value at path.
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        JsonNode node = getNodeAtPath(path);
        if (node != null && node.isBoolean()) {
            return node.asBoolean();
        }
        return defaultValue;
    }
    
    /**
     * Get string list at path.
     */
    public List<String> getStringList(String path) {
        JsonNode node = getNodeAtPath(path);
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }
    
    /**
     * Get object at path as Map.
     */
    public Map<String, Object> getMap(String path) {
        JsonNode node = getNodeAtPath(path);
        if (node != null && node.isObject()) {
            return jsonMapper.convertValue(node, Map.class);
        }
        return new HashMap<>();
    }
    
    /**
     * Check if path exists.
     */
    public boolean hasPath(String path) {
        return getNodeAtPath(path) != null;
    }
    
    /**
     * Set value at path.
     */
    public void set(String path, Object value) {
        String[] parts = path.split("\\.");
        ObjectNode current = config;
        
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = current.get(parts[i]);
            if (child == null || !child.isObject()) {
                child = current.putObject(parts[i]);
            }
            current = (ObjectNode) child;
        }
        
        String lastKey = parts[parts.length - 1];
        if (value == null) {
            current.remove(lastKey);
        } else if (value instanceof String) {
            current.put(lastKey, (String) value);
        } else if (value instanceof Integer) {
            current.put(lastKey, (Integer) value);
        } else if (value instanceof Boolean) {
            current.put(lastKey, (Boolean) value);
        } else if (value instanceof Double) {
            current.put(lastKey, (Double) value);
        } else {
            current.set(lastKey, jsonMapper.valueToTree(value));
        }
    }
    
    /**
     * Get environment override value.
     */
    public String getEnvOverride(String name) {
        // First check actual env, then our overrides
        String value = System.getenv(name);
        if (value != null) {
            return value;
        }
        return envOverrides.get(name);
    }
    
    // ==================== Convenience Methods ====================
    
    public String getModelProvider() {
        return getString("model.provider", "openrouter");
    }
    
    public String getModelName() {
        return getString("model.model", "anthropic/claude-3.5-sonnet");
    }
    
    public String getApiKey() {
        String key = getString("model.api_key");
        if (key == null || key.isEmpty()) {
            key = System.getenv("OPENROUTER_API_KEY");
        }
        return key;
    }
    
    public String getBaseUrl() {
        String url = getString("model.base_url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        String provider = getModelProvider().toLowerCase();
        return switch (provider) {
            case "openai" -> "https://api.openai.com/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "openrouter" -> Constants.OPENROUTER_BASE_URL;
            case "nous" -> Constants.NOUS_API_BASE_URL;
            default -> Constants.OPENROUTER_BASE_URL;
        };
    }
    
    public int getMaxTurns() {
        return getInt("agent.max_turns", Constants.DEFAULT_MAX_ITERATIONS);
    }
    
    public int getGatewayTimeout() {
        return getInt("agent.gateway_timeout", Constants.DEFAULT_TIMEOUT_SECONDS);
    }
    
    public List<String> getEnabledTools() {
        return getStringList("tools.enabled");
    }
    
    public String getTerminalBackend() {
        return getString("terminal.backend", "local");
    }
    
    public String getWebBackend() {
        return getString("web.backend", "firecrawl");
    }
    
    // ==================== Helper Methods ====================
    
    private JsonNode getNodeAtPath(String path) {
        if (config == null) {
            return null;
        }
        
        String[] parts = path.split("\\.");
        JsonNode current = config;
        
        for (String part : parts) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        
        return current;
    }
    
    /**
     * Print all configuration (with sensitive values masked).
     */
    public void printAll() {
        System.out.println("Configuration:");
        printNode(config, "", true);
    }
    
    /**
     * Print configuration without masking.
     */
    public void printAllUnmasked() {
        System.out.println("Configuration (unmasked):");
        printNode(config, "", false);
    }
    
    private void printNode(JsonNode node, String indent, boolean maskSensitive) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                
                if (value.isObject()) {
                    System.out.println(indent + key + ":");
                    printNode(value, indent + "  ", maskSensitive);
                } else {
                    String displayValue = formatValue(key, value, maskSensitive);
                    System.out.println(indent + key + ": " + displayValue);
                }
            }
        }
    }
    
    private String formatValue(String key, JsonNode value, boolean maskSensitive) {
        if (maskSensitive && isSensitiveKey(key)) {
            return value.isTextual() && !value.asText().isEmpty() ? "***" : "";
        }
        
        if (value.isTextual()) {
            return value.asText();
        } else if (value.isNumber()) {
            return value.numberValue().toString();
        } else if (value.isBoolean()) {
            return Boolean.toString(value.asBoolean());
        } else if (value.isArray()) {
            return value.toString();
        } else {
            return value.toString();
        }
    }
    
    private boolean isSensitiveKey(String key) {
        String lowerKey = key.toLowerCase();
        return sensitiveKeys.stream().anyMatch(lowerKey::contains);
    }
    
    /**
     * Get raw config node.
     */
    public ObjectNode getRawConfig() {
        return config;
    }
    
    /**
     * Reload configuration from disk.
     */
    public void reload() throws IOException {
        load(configPath);
    }
}
