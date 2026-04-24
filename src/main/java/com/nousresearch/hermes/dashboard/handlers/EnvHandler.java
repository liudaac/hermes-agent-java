package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handler for environment variable API endpoints.
 */
public class EnvHandler {
    private static final Logger logger = LoggerFactory.getLogger(EnvHandler.class);

    private final Path envPath;
    private final Map<String, EnvVarInfo> envVars = new ConcurrentHashMap<>();
    private final Map<String, String> actualValues = new ConcurrentHashMap<>();

    // Rate limiting for reveal endpoint
    private final Map<String, List<Long>> revealTimestamps = new ConcurrentHashMap<>();
    private static final int REVEAL_MAX_PER_WINDOW = 5;
    private static final long REVEAL_WINDOW_SECONDS = 30;

    // Known environment variables with descriptions
    private final Map<String, EnvVarMetadata> knownVars;

    public EnvHandler() {
        this.envPath = Path.of(System.getProperty("user.home"), ".hermes", ".env");
        this.knownVars = createKnownVars();
        loadEnvFile();
    }

    /**
     * Define known environment variables with metadata.
     */
    private Map<String, EnvVarMetadata> createKnownVars() {
        Map<String, EnvVarMetadata> vars = new HashMap<>();

        // API Keys
        addVar(vars, "OPENROUTER_API_KEY", "OpenRouter API key", "api", true,
            "https://openrouter.ai/keys", List.of("model"));
        addVar(vars, "OPENAI_API_KEY", "OpenAI API key", "api", true,
            "https://platform.openai.com/api-keys", List.of("model"));
        addVar(vars, "ANTHROPIC_API_KEY", "Anthropic API key", "api", true,
            "https://console.anthropic.com/settings/keys", List.of("model"));
        addVar(vars, "BRAVE_API_KEY", "Brave Search API key", "api", true,
            "https://api.search.brave.com/app/keys", List.of("web_search"));
        addVar(vars, "TAVILY_API_KEY", "Tavily Search API key", "api", true,
            "https://app.tavily.com/home", List.of("web_search"));
        addVar(vars, "ELEVENLABS_API_KEY", "ElevenLabs API key", "api", true,
            "https://elevenlabs.io/app/settings/api-keys", List.of("tts"));
        addVar(vars, "STABILITY_API_KEY", "Stability AI API key", "api", true,
            "https://platform.stability.ai/account/keys", List.of("image_generation"));

        // Terminal backends
        addVar(vars, "TERMINAL_ENV", "Terminal backend (local/docker/ssh)", "terminal", false,
            null, List.of("terminal"));
        addVar(vars, "DOCKER_HOST", "Docker daemon socket", "terminal", false,
            null, List.of("terminal"));
        addVar(vars, "SSH_HOST", "SSH target host", "terminal", false,
            null, List.of("terminal"));
        addVar(vars, "SSH_USER", "SSH username", "terminal", false,
            null, List.of("terminal"));

        // Gateway platforms
        addVar(vars, "FEISHU_APP_ID", "Feishu App ID", "gateway", false,
            "https://open.feishu.cn/app", List.of("feishu"));
        addVar(vars, "FEISHU_APP_SECRET", "Feishu App Secret", "gateway", true,
            null, List.of("feishu"));
        addVar(vars, "TELEGRAM_BOT_TOKEN", "Telegram Bot Token", "gateway", true,
            "https://t.me/BotFather", List.of("telegram"));
        addVar(vars, "DISCORD_BOT_TOKEN", "Discord Bot Token", "gateway", true,
            "https://discord.com/developers/applications", List.of("discord"));

        // Home Assistant
        addVar(vars, "HA_URL", "Home Assistant URL", "integration", false,
            null, List.of("home_assistant"));
        addVar(vars, "HA_TOKEN", "Home Assistant Long-Lived Token", "integration", true,
            null, List.of("home_assistant"));

        // MCP
        addVar(vars, "MCP_SERVER_1_NAME", "MCP Server 1 Name", "mcp", false,
            null, List.of("mcp"));
        addVar(vars, "MCP_SERVER_1_URL", "MCP Server 1 URL", "mcp", false,
            null, List.of("mcp"));

        // Advanced
        addVar(vars, "DEBUG", "Enable debug mode", "advanced", false,
            null, List.of());
        addVar(vars, "HERMES_HOME", "Hermes home directory", "advanced", false,
            null, List.of());

        return vars;
    }

    private void addVar(Map<String, EnvVarMetadata> vars, String key, String description,
                        String category, boolean isPassword, String url, List<String> tools) {
        EnvVarMetadata meta = new EnvVarMetadata();
        meta.key = key;
        meta.description = description;
        meta.category = category;
        meta.isPassword = isPassword;
        meta.url = url;
        meta.tools = tools;
        meta.advanced = category.equals("advanced");
        vars.put(key, meta);
    }

    /**
     * Load environment variables from .env file and system environment.
     */
    private void loadEnvFile() {
        envVars.clear();
        actualValues.clear();

        // Load from file
        try {
            if (Files.exists(envPath)) {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        // Remove quotes if present
                        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        actualValues.put(key, value);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not load .env file: {}", e.getMessage());
        }

        // Merge with system environment
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (knownVars.containsKey(key) || actualValues.containsKey(key)) {
                actualValues.put(key, entry.getValue());
            }
        }

        // Build EnvVarInfo for all known vars
        for (Map.Entry<String, EnvVarMetadata> entry : knownVars.entrySet()) {
            String key = entry.getKey();
            EnvVarMetadata meta = entry.getValue();
            EnvVarInfo info = createEnvVarInfo(key, meta);
            envVars.put(key, info);
        }

        // Add any additional vars from file that aren't in known list
        for (String key : actualValues.keySet()) {
            if (!envVars.containsKey(key)) {
                EnvVarInfo info = new EnvVarInfo();
                info.key = key;
                info.description = key;
                info.isSet = true;
                info.redactedValue = redactValue(actualValues.get(key));
                info.category = "other";
                info.isPassword = true;
                info.tools = List.of();
                info.advanced = true;
                envVars.put(key, info);
            }
        }
    }

    private EnvVarInfo createEnvVarInfo(String key, EnvVarMetadata meta) {
        EnvVarInfo info = new EnvVarInfo();
        info.key = key;
        info.description = meta.description;
        info.isSet = actualValues.containsKey(key);
        info.redactedValue = info.isSet ? redactValue(actualValues.get(key)) : null;
        info.category = meta.category;
        info.url = meta.url;
        info.isPassword = meta.isPassword;
        info.tools = meta.tools;
        info.advanced = meta.advanced;
        return info;
    }

    private String redactValue(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /**
     * GET /api/env - Get all environment variables
     */
    public void getEnvVars(Context ctx) {
        // Convert to response format
        Map<String, JSONObject> result = new HashMap<>();
        for (Map.Entry<String, EnvVarInfo> entry : envVars.entrySet()) {
            EnvVarInfo info = entry.getValue();
            JSONObject obj = new JSONObject();
            obj.put("is_set", info.isSet);
            obj.put("redacted_value", info.redactedValue);
            obj.put("description", info.description);
            obj.put("url", info.url);
            obj.put("category", info.category);
            obj.put("is_password", info.isPassword);
            obj.put("tools", info.tools);
            obj.put("advanced", info.advanced);
            result.put(info.key, obj);
        }
        ctx.json(result);
    }

    /**
     * PUT /api/env - Set an environment variable
     */
    public void setEnvVar(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String key = body.getString("key");
            String value = body.getString("value");

            if (key == null || key.isEmpty()) {
                ctx.status(400).result("Missing key");
                return;
            }

            // Update in-memory
            actualValues.put(key, value);

            // Save to file
            saveEnvFile();

            // Reload to update EnvVarInfo
            loadEnvFile();

            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            logger.error("Error setting env var: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    /**
     * DELETE /api/env - Delete an environment variable
     */
    public void deleteEnvVar(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String key = body.getString("key");

            if (key == null || key.isEmpty()) {
                ctx.status(400).result("Missing key");
                return;
            }

            // Remove from in-memory
            actualValues.remove(key);

            // Save to file
            saveEnvFile();

            // Reload to update EnvVarInfo
            loadEnvFile();

            ctx.json(Map.of("ok", true));
        } catch (Exception e) {
            logger.error("Error deleting env var: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    /**
     * POST /api/env/reveal - Reveal actual value (rate limited)
     */
    public void revealEnvVar(Context ctx) {
        try {
            // Check rate limit
            String clientId = ctx.ip();
            if (!checkRevealRateLimit(clientId)) {
                ctx.status(429).result("Rate limit exceeded. Try again later.");
                return;
            }

            JSONObject body = JSON.parseObject(ctx.body());
            String key = body.getString("key");

            if (key == null || key.isEmpty()) {
                ctx.status(400).result("Missing key");
                return;
            }

            String value = actualValues.get(key);
            if (value == null) {
                ctx.status(404).result("Variable not set");
                return;
            }

            ctx.json(Map.of("key", key, "value", value));
        } catch (Exception e) {
            logger.error("Error revealing env var: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    private boolean checkRevealRateLimit(String clientId) {
        long now = System.currentTimeMillis() / 1000;
        List<Long> timestamps = revealTimestamps.computeIfAbsent(clientId, k -> new ArrayList<>());

        // Remove old timestamps outside window
        timestamps.removeIf(ts -> now - ts > REVEAL_WINDOW_SECONDS);

        // Check limit
        if (timestamps.size() >= REVEAL_MAX_PER_WINDOW) {
            return false;
        }

        // Add current timestamp
        timestamps.add(now);
        return true;
    }

    private void saveEnvFile() throws IOException {
        Files.createDirectories(envPath.getParent());

        List<String> lines = new ArrayList<>();
        lines.add("# Hermes Environment Variables");
        lines.add("# Generated by Dashboard");
        lines.add("");

        // Group by category
        Map<String, List<Map.Entry<String, String>>> byCategory = actualValues.entrySet().stream()
            .collect(Collectors.groupingBy(e -> {
                EnvVarMetadata meta = knownVars.get(e.getKey());
                return meta != null ? meta.category : "other";
            }));

        // Write in category order
        List<String> categoryOrder = List.of("api", "terminal", "gateway", "integration", "mcp", "other", "advanced");
        for (String category : categoryOrder) {
            List<Map.Entry<String, String>> vars = byCategory.getOrDefault(category, List.of());
            if (vars.isEmpty()) continue;

            lines.add("# " + category.toUpperCase());
            for (Map.Entry<String, String> entry : vars) {
                String value = entry.getValue();
                // Quote if contains spaces or special chars
                if (value.contains(" ") || value.contains("$") || value.contains("#")) {
                    value = "\"" + value.replace("\"", "\\\"") + "\"";
                }
                lines.add(entry.getKey() + "=" + value);
            }
            lines.add("");
        }

        Files.write(envPath, lines);
    }

    // Data classes
    private static class EnvVarMetadata {
        String key;
        String description;
        String category;
        boolean isPassword;
        String url;
        List<String> tools;
        boolean advanced;
    }

    public static class EnvVarInfo {
        public String key;
        public String description;
        public boolean isSet;
        public String redactedValue;
        public String category;
        public String url;
        public boolean isPassword;
        public List<String> tools;
        public boolean advanced;
    }
}
