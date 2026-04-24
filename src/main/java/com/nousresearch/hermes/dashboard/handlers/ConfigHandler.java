package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Handler for configuration-related API endpoints.
 */
public class ConfigHandler {
    private static final Logger logger = LoggerFactory.getLogger(ConfigHandler.class);

    private final HermesConfig config;
    private final Path configPath;
    private final Yaml yaml;

    // Default configuration values
    private final Map<String, Object> defaultConfig;

    // Schema overrides for UI fields
    private final Map<String, Map<String, Object>> schemaOverrides;
    private final Map<String, String> categoryMerge;
    private final List<String> categoryOrder;

    public ConfigHandler(HermesConfig config) {
        this.config = config;
        this.configPath = Path.of(System.getProperty("user.home"), ".hermes", "config.yaml");
        this.yaml = new Yaml();

        // Initialize default configuration
        this.defaultConfig = createDefaultConfig();

        // Initialize schema overrides
        this.schemaOverrides = createSchemaOverrides();
        this.categoryMerge = createCategoryMerge();
        this.categoryOrder = List.of(
            "general", "agent", "terminal", "display", "delegation",
            "memory", "compression", "security", "browser", "voice",
            "tts", "stt", "logging", "discord", "auxiliary"
        );
    }

    /**
     * Create default configuration matching Python version.
     */
    private Map<String, Object> createDefaultConfig() {
        Map<String, Object> defaults = new LinkedHashMap<>();

        // General
        defaults.put("model", "anthropic/claude-sonnet-4");
        defaults.put("model_context_length", 0);
        defaults.put("toolsets", List.of("default"));
        defaults.put("timezone", "UTC");

        // Agent settings
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("max_turns", 90);
        agent.put("checkpoint_interval", 10);
        agent.put("service_tier", "");
        defaults.put("agent", agent);

        // Terminal settings
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("backend", "local");
        terminal.put("modal_mode", "sandbox");
        terminal.put("timeout", 300);
        terminal.put("sandbox", Map.of("image", "ubuntu:22.04"));
        defaults.put("terminal", terminal);

        // Display settings
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("skin", "default");
        display.put("personality", "kawaii");
        display.put("theme", "default");
        display.put("busy_input_mode", "queue");
        display.put("resume_display", "minimal");
        defaults.put("display", display);

        // Delegation settings
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("reasoning_effort", "");
        defaults.put("delegation", delegation);

        // Memory settings
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("provider", "builtin");
        defaults.put("memory", memory);

        // Compression settings
        Map<String, Object> compression = new LinkedHashMap<>();
        compression.put("engine", "default");
        compression.put("threshold", 8000);
        defaults.put("compression", compression);

        // Security settings
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("approvals_mode", "ask");
        defaults.put("security", security);

        // Browser settings
        Map<String, Object> browser = new LinkedHashMap<>();
        browser.put("headless", true);
        browser.put("timeout", 30000);
        defaults.put("browser", browser);

        // Voice settings
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("tts_provider", "edge");
        voice.put("stt_provider", "local");
        defaults.put("voice", voice);

        // Logging settings
        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("level", "INFO");
        logging.put("file", "agent.log");
        defaults.put("logging", logging);

        return defaults;
    }

    private Map<String, Map<String, Object>> createSchemaOverrides() {
        Map<String, Map<String, Object>> overrides = new HashMap<>();

        overrides.put("model", Map.of(
            "type", "string",
            "description", "Default model (e.g. anthropic/claude-sonnet-4)",
            "category", "general"
        ));

        overrides.put("model_context_length", Map.of(
            "type", "number",
            "description", "Context window override (0 = auto-detect)",
            "category", "general"
        ));

        overrides.put("terminal.backend", Map.of(
            "type", "select",
            "description", "Terminal execution backend",
            "options", List.of("local", "docker", "ssh", "modal", "daytona"),
            "category", "terminal"
        ));

        overrides.put("terminal.modal_mode", Map.of(
            "type", "select",
            "description", "Modal sandbox mode",
            "options", List.of("sandbox", "function"),
            "category", "terminal"
        ));

        overrides.put("voice.tts_provider", Map.of(
            "type", "select",
            "description", "Text-to-speech provider",
            "options", List.of("edge", "elevenlabs", "openai"),
            "category", "voice"
        ));

        overrides.put("voice.stt_provider", Map.of(
            "type", "select",
            "description", "Speech-to-text provider",
            "options", List.of("local", "openai", "mistral"),
            "category", "voice"
        ));

        overrides.put("display.skin", Map.of(
            "type", "select",
            "description", "CLI visual theme",
            "options", List.of("default", "ares", "mono", "slate"),
            "category", "display"
        ));

        overrides.put("display.theme", Map.of(
            "type", "select",
            "description", "Web dashboard visual theme",
            "options", List.of("default", "midnight", "ember", "mono", "cyberpunk"),
            "category", "display"
        ));

        overrides.put("display.resume_display", Map.of(
            "type", "select",
            "description", "How resumed sessions display history",
            "options", List.of("minimal", "full", "off"),
            "category", "display"
        ));

        overrides.put("display.busy_input_mode", Map.of(
            "type", "select",
            "description", "Input behavior while agent is running",
            "options", List.of("queue", "interrupt", "block"),
            "category", "display"
        ));

        overrides.put("memory.provider", Map.of(
            "type", "select",
            "description", "Memory provider plugin",
            "options", List.of("builtin", "honcho"),
            "category", "memory"
        ));

        overrides.put("security.approvals_mode", Map.of(
            "type", "select",
            "description", "Dangerous command approval mode",
            "options", List.of("ask", "yolo", "deny"),
            "category", "security"
        ));

        overrides.put("compression.engine", Map.of(
            "type", "select",
            "description", "Context management engine",
            "options", List.of("default", "custom"),
            "category", "compression"
        ));

        overrides.put("logging.level", Map.of(
            "type", "select",
            "description", "Log level for agent.log",
            "options", List.of("DEBUG", "INFO", "WARNING", "ERROR"),
            "category", "logging"
        ));

        overrides.put("agent.service_tier", Map.of(
            "type", "select",
            "description", "API service tier",
            "options", List.of("", "auto", "default", "flex"),
            "category", "agent"
        ));

        overrides.put("delegation.reasoning_effort", Map.of(
            "type", "select",
            "description", "Reasoning effort for delegated subagents",
            "options", List.of("", "low", "medium", "high"),
            "category", "delegation"
        ));

        return overrides;
    }

    private Map<String, String> createCategoryMerge() {
        Map<String, String> merge = new HashMap<>();
        merge.put("privacy", "security");
        merge.put("context", "agent");
        merge.put("skills", "agent");
        merge.put("cron", "agent");
        merge.put("network", "agent");
        merge.put("checkpoints", "agent");
        merge.put("approvals", "security");
        merge.put("human_delay", "display");
        merge.put("dashboard", "display");
        merge.put("code_execution", "agent");
        return merge;
    }

    /**
     * GET /api/config - Get current configuration
     */
    public void getConfig(Context ctx) {
        try {
            Map<String, Object> currentConfig = loadConfigFromFile();
            ctx.json(currentConfig);
        } catch (Exception e) {
            logger.error("Error loading config: {}", e.getMessage());
            ctx.json(defaultConfig);
        }
    }

    /**
     * PUT /api/config - Update configuration
     */
    public void updateConfig(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            Map<String, Object> newConfig = body.getJSONObject("config").toJavaObject(Map.class);

            // Merge with existing config
            Map<String, Object> currentConfig = loadConfigFromFile();
            deepMerge(currentConfig, newConfig);

            // Save to file
            saveConfigToFile(currentConfig);

            ctx.json(Map.of("status", "updated"));
        } catch (Exception e) {
            logger.error("Error updating config: {}", e.getMessage());
            ctx.status(400).result("Invalid config: " + e.getMessage());
        }
    }

    /**
     * GET /api/config/defaults - Get default configuration
     */
    public void getDefaults(Context ctx) {
        ctx.json(defaultConfig);
    }

    /**
     * GET /api/config/schema - Get configuration schema for UI
     */
    public void getSchema(Context ctx) {
        Map<String, Map<String, Object>> schema = buildSchemaFromConfig(defaultConfig);

        Map<String, Object> result = new HashMap<>();
        result.put("fields", schema);
        result.put("category_order", categoryOrder);

        ctx.json(result);
    }

    /**
     * GET /api/config/raw - Get raw YAML config
     */
    public void getRawConfig(Context ctx) {
        try {
            String yamlContent;
            if (Files.exists(configPath)) {
                yamlContent = Files.readString(configPath);
            } else {
                yamlContent = yaml.dump(defaultConfig);
            }
            ctx.json(Map.of("yaml", yamlContent));
        } catch (Exception e) {
            logger.error("Error reading raw config: {}", e.getMessage());
            ctx.status(500).result("Error reading config");
        }
    }

    /**
     * PUT /api/config/raw - Update raw YAML config
     */
    public void updateRawConfig(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String yamlText = body.getString("yaml_text");

            // Validate YAML
            Map<String, Object> parsed = yaml.load(yamlText);

            // Save to file
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, yamlText);

            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            logger.error("Error updating raw config: {}", e.getMessage());
            ctx.status(400).result("Invalid YAML: " + e.getMessage());
        }
    }

    /**
     * GET /api/model/info - Get model information
     */
    public void getModelInfo(Context ctx) {
        Map<String, Object> modelInfo = new HashMap<>();
        modelInfo.put("model", "anthropic/claude-sonnet-4");
        modelInfo.put("provider", "anthropic");
        modelInfo.put("auto_context_length", 200000);
        modelInfo.put("config_context_length", 0);
        modelInfo.put("effective_context_length", 200000);

        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("supports_tools", true);
        capabilities.put("supports_vision", true);
        capabilities.put("supports_reasoning", false);
        capabilities.put("context_window", 200000);
        capabilities.put("max_output_tokens", 8192);
        capabilities.put("model_family", "claude");
        modelInfo.put("capabilities", capabilities);

        ctx.json(modelInfo);
    }

    /**
     * Build schema from default config for UI rendering.
     */
    private Map<String, Map<String, Object>> buildSchemaFromConfig(Map<String, Object> config) {
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        buildSchemaRecursive(config, "", schema);
        return schema;
    }

    private void buildSchemaRecursive(Map<String, Object> config, String prefix,
                                      Map<String, Map<String, Object>> schema) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;

            // Skip internal/version keys
            if (fullKey.equals("_config_version")) {
                continue;
            }

            // Determine category
            String category;
            if (!prefix.isEmpty()) {
                category = prefix.split("\\.")[0];
            } else if (value instanceof Map) {
                category = key;
            } else {
                category = "general";
            }

            if (value instanceof Map) {
                // Recurse into nested maps
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                buildSchemaRecursive(nestedMap, fullKey, schema);
            } else {
                Map<String, Object> fieldSchema = new HashMap<>();
                fieldSchema.put("type", inferType(value));
                fieldSchema.put("description", fullKey.replace(".", " → ").replace("_", " "));
                fieldSchema.put("category", categoryMerge.getOrDefault(category, category));

                // Apply overrides
                if (schemaOverrides.containsKey(fullKey)) {
                    fieldSchema.putAll(schemaOverrides.get(fullKey));
                }

                schema.put(fullKey, fieldSchema);
            }
        }
    }

    private String inferType(Object value) {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer || value instanceof Long) return "number";
        if (value instanceof Double || value instanceof Float) return "number";
        if (value instanceof List) return "list";
        if (value instanceof Map) return "object";
        return "string";
    }

    private Map<String, Object> loadConfigFromFile() {
        try {
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                Map<String, Object> loaded = yaml.load(content);
                if (loaded != null) {
                    // Merge with defaults
                    Map<String, Object> merged = new LinkedHashMap<>(defaultConfig);
                    deepMerge(merged, loaded);
                    return merged;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load config file, using defaults: {}", e.getMessage());
        }
        return new LinkedHashMap<>(defaultConfig);
    }

    private void saveConfigToFile(Map<String, Object> config) throws IOException {
        Files.createDirectories(configPath.getParent());
        String yamlContent = yaml.dump(config);
        Files.writeString(configPath, yamlContent);
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map && target.get(key) instanceof Map) {
                deepMerge((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, value);
            }
        }
    }
}
