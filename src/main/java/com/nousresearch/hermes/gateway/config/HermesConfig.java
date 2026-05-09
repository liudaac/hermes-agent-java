package com.nousresearch.hermes.gateway.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Main configuration class for Hermes Agent.
 * Loaded from config.json or environment variables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HermesConfig {
    private static final Logger logger = LoggerFactory.getLogger(HermesConfig.class);

    @JsonProperty("version")
    private String version = "1.0.0";

    @JsonProperty("default_model")
    private String defaultModel = "claude-3-5-sonnet";

    @JsonProperty("models")
    private List<ModelConfig> models = new ArrayList<>();

    @JsonProperty("gateway")
    private GatewayConfig gateway = new GatewayConfig();

    @JsonProperty("skills")
    private SkillsConfig skills = new SkillsConfig();

    @JsonProperty("sandbox")
    private SandboxConfig sandbox = new SandboxConfig();

    @JsonProperty("memory")
    private MemoryConfig memory = new MemoryConfig();

    @JsonProperty("tools")
    private ToolsConfig tools = new ToolsConfig();

    // Tenant quota settings
    @JsonProperty("max_tokens_per_tenant")
    private long maxTokensPerTenant = 1000000L;

    @JsonProperty("max_requests_per_day")
    private long maxRequestsPerDay = 10000L;

    @JsonProperty("max_concurrent_sessions")
    private int maxConcurrentSessions = 100;

    @JsonProperty("requests_per_second_per_tenant")
    private double requestsPerSecondPerTenant = 10.0;

    public static HermesConfig load(String configPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        File configFile = new File(configPath);
        if (configFile.exists()) {
            return mapper.readValue(configFile, HermesConfig.class);
        }
        logger.warn("Config file not found: {}, using defaults", configPath);
        return new HermesConfig();
    }

    public static HermesConfig loadFromEnv() {
        HermesConfig config = new HermesConfig();
        // TODO: Load from environment variables
        return config;
    }

    // Getters
    public String getVersion() {
        return version;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public List<ModelConfig> getModels() {
        return models;
    }

    public GatewayConfig getGateway() {
        return gateway;
    }

    public SkillsConfig getSkills() {
        return skills;
    }

    public SandboxConfig getSandbox() {
        return sandbox;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    public ToolsConfig getTools() {
        return tools;
    }

    // Tenant quota getters
    public long getMaxTokensPerTenant() {
        return maxTokensPerTenant;
    }

    public long getMaxRequestsPerDay() {
        return maxRequestsPerDay;
    }

    public int getMaxConcurrentSessions() {
        return maxConcurrentSessions;
    }

    public double getRequestsPerSecondPerTenant() {
        return requestsPerSecondPerTenant;
    }

    // Sub-config classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GatewayConfig {
        @JsonProperty("port")
        private int port = 8080;

        @JsonProperty("host")
        private String host = "0.0.0.0";

        @JsonProperty("api_key")
        private String apiKey = "";

        public int getPort() { return port; }
        public String getHost() { return host; }
        public String getApiKey() { return apiKey; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillsConfig {
        @JsonProperty("auto_load")
        private boolean autoLoad = true;

        @JsonProperty("directory")
        private String directory = "skills";

        public boolean isAutoLoad() { return autoLoad; }
        public String getDirectory() { return directory; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("max_file_size")
        private long maxFileSize = 10485760; // 10MB

        @JsonProperty("allowed_paths")
        private List<String> allowedPaths = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public long getMaxFileSize() { return maxFileSize; }
        public List<String> getAllowedPaths() { return allowedPaths; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("max_entries")
        private int maxEntries = 1000;

        @JsonProperty("retention_days")
        private int retentionDays = 30;

        public boolean isEnabled() { return enabled; }
        public int getMaxEntries() { return maxEntries; }
        public int getRetentionDays() { return retentionDays; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolsConfig {
        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 30;

        @JsonProperty("max_concurrent")
        private int maxConcurrent = 10;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public int getMaxConcurrent() { return maxConcurrent; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelConfig {
        @JsonProperty("name")
        private String name = "";

        @JsonProperty("provider")
        private String provider = "openrouter";

        @JsonProperty("api_key")
        private String apiKey = "";

        @JsonProperty("base_url")
        private String baseUrl = "";

        public String getName() { return name; }
        public String getProvider() { return provider; }
        public String getApiKey() { return apiKey; }
        public String getBaseUrl() { return baseUrl; }
    }
}
