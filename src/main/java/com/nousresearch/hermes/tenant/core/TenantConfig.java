package com.nousresearch.hermes.tenant.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nousresearch.hermes.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 租户配置管理器
 * 
 * 管理租户专属的配置，包括：
 * - 模型配置 (provider, model, api_key)
 * - Agent 配置 (max_turns, timeout)
 * - 终端配置 (backend, docker settings)
 * - 工具配置 (enabled tools)
 * - 显示配置
 * - 自定义配置
 * 
 * 配置继承：租户配置 → 系统默认配置
 * 环境变量：支持 ${VAR} 占位符替换
 */
public class TenantConfig {
    private static final Logger logger = LoggerFactory.getLogger(TenantConfig.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    
    // 配置文件路径
    private static final String CONFIG_FILE = "config.yaml";
    private static final String SECRETS_FILE = "secrets.env";
    
    private final Path configDir;
    private final Path configFile;
    private final Path secretsFile;
    
    // 配置存储
    private final ConcurrentHashMap<String, Object> config;
    private final ConcurrentHashMap<String, String> secrets;
    
    // 读写锁
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 环境变量占位符模式
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    
    public TenantConfig(Path configDir, Map<String, Object> initialConfig) {
        this(configDir, initialConfig, true);
    }

    private TenantConfig(Path configDir, Map<String, Object> initialConfig, boolean autoSave) {
        this.configDir = configDir;
        this.configFile = configDir.resolve(CONFIG_FILE);
        this.secretsFile = configDir.resolve(SECRETS_FILE);
        this.config = new ConcurrentHashMap<>();
        this.secrets = new ConcurrentHashMap<>();

        // 加载系统默认值
        loadDefaults();

        // 合并初始配置
        if (initialConfig != null) {
            deepMerge(config, initialConfig);
        }

        // 保存初始配置（仅当 autoSave 为 true 时）
        if (autoSave) {
            try {
                Files.createDirectories(configDir);
                save();
            } catch (IOException e) {
                logger.error("Failed to create config directory", e);
            }
        }
    }

    /**
     * 从磁盘加载配置
     */
    public static TenantConfig load(Path configDir) {
        TenantConfig tenantConfig = new TenantConfig(configDir, null, false);
        tenantConfig.loadFromDisk();
        return tenantConfig;
    }

    /**
     * Create a TenantConfig from a ConfigRepository (DB-backed).
     * Does not touch local filesystem.
     *
     * @param tenantId   tenant ID
     * @param repo       config repository (Local or Mysql)
     * @param globalConfig  global HermesConfig for platform defaults
     */
    public static TenantConfig fromRepository(String tenantId,
                                              com.nousresearch.hermes.config.repository.ConfigRepository repo,
                                              com.nousresearch.hermes.config.HermesConfig globalConfig) {
        TenantConfig tc = new TenantConfig(null, null, false);

        // Load model config from repo
        Map<String, Object> modelConfig = repo.loadModelConfig(tenantId);
        if (modelConfig != null) {
            for (var entry : modelConfig.entrySet()) {
                if (entry.getValue() != null) {
                    tc.set("model." + entry.getKey(), entry.getValue());
                }
            }
        }

        // Load model routes from repo
        var routes = repo.loadModelRoutes(tenantId);
        if (routes != null && !routes.isEmpty()) {
            tc.set("model_routes", routes.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("alias", r.getAlias());
                    m.put("model", r.getModel());
                    m.put("provider", r.getProvider());
                    if (r.getBaseUrl() != null) m.put("base-url", r.getBaseUrl());
                    return m;
                })
                .toList());
        }

        // Load API keys from repo
        Map<String, String> apiKeys = repo.loadApiKeys(tenantId);
        if (apiKeys != null) {
            for (var entry : apiKeys.entrySet()) {
                tc.set("secrets." + entry.getKey().toUpperCase() + "_API_KEY", entry.getValue());
            }
        }

        // Load quota from repo
        var quota = repo.loadQuota(tenantId);
        if (quota != null) {
            tc.set("quota.max_requests_per_day", quota.getMaxDailyRequests());
            tc.set("quota.max_tokens_per_day", quota.getMaxDailyTokens());
        }

        // Load platform defaults if global config available
        if (globalConfig != null) {
            var platformRoutes = globalConfig.getModelRoutes();
            if (platformRoutes != null && !platformRoutes.isEmpty() && tc.get("model_routes") == null) {
                tc.set("model_routes", platformRoutes.stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("alias", r.getAlias());
                        m.put("model", r.getModel());
                        m.put("provider", r.getProvider());
                        if (r.getBaseUrl() != null) m.put("base-url", r.getBaseUrl());
                        return m;
                    })
                    .toList());
            }
        }

        return tc;
    }
    
    // ============ 默认配置 ============
    
    private void loadDefaults() {
        // Load defaults from classpath config.yaml (same file as HermesConfig)
        try (var is = getClass().getClassLoader().getResourceAsStream("config.yaml")) {
            if (is != null) {
                var yaml = new org.yaml.snakeyaml.Yaml();
                Map<String, Object> loaded = yaml.load(is);
                if (loaded != null) {
                    deepMerge(config, loaded);
                    return;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load config.yaml from classpath for tenant defaults: {}", e.getMessage());
        }
        // Fallback: minimal hardcoded defaults
        set("model.provider", "volcengine");
        set("model.model", "deepseek-v3-250324");
        set("model.base_url", "https://ark.cn-beijing.volces.com/api/v3");
        set("model.temperature", 0.7);
        set("model.max_tokens", 4096);
    }
    
    // ============ 配置存取 ============
    
    /**
     * 获取配置值（支持点号路径）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        lock.readLock().lock();
        try {
            String[] parts = key.split("\\.");
            Object current = config;
            
            for (String part : parts) {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                    if (current == null) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            
            return (T) expandEnvVars(current);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取配置值（带默认值）
     */
    public <T> T get(String key, T defaultValue) {
        T value = get(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取字符串配置
     */
    public String getString(String key) {
        Object value = get(key);
        return value != null ? value.toString() : null;
    }
    
    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取整数配置
     */
    public int getInt(String key) {
        Object value = get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return 0;
    }
    
    public int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    /**
     * 获取布尔配置
     */
    public boolean getBoolean(String key) {
        Object value = get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return false;
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
    
    /**
     * 获取列表配置
     */
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object value = get(key);
        if (value instanceof List) {
            return ((List<Object>) value).stream()
                .map(Object::toString)
                .toList();
        }
        return List.of();
    }
    
    /**
     * 设置配置值
     */
    @SuppressWarnings("unchecked")
    public void set(String key, Object value) {
        lock.writeLock().lock();
        try {
            String[] parts = key.split("\\.");
            Map<String, Object> current = config;
            
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = current.get(part);
                if (!(next instanceof Map)) {
                    next = new ConcurrentHashMap<String, Object>();
                    current.put(part, next);
                }
                current = (Map<String, Object>) next;
            }
            
            current.put(parts[parts.length - 1], value);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 检查配置是否存在
     */
    public boolean has(String key) {
        return get(key) != null;
    }
    
    /**
     * 删除配置
     */
    @SuppressWarnings("unchecked")
    public boolean remove(String key) {
        lock.writeLock().lock();
        try {
            String[] parts = key.split("\\.");
            Map<String, Object> current = config;
            
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = current.get(part);
                if (!(next instanceof Map)) {
                    return false;
                }
                current = (Map<String, Object>) next;
            }
            
            return current.remove(parts[parts.length - 1]) != null;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // ============ 密钥管理 ============
    
    /**
     * 获取密钥（敏感信息）
     */
    public String getSecret(String key) {
        lock.readLock().lock();
        try {
            return secrets.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 设置密钥
     */
    public void setSecret(String key, String value) {
        lock.writeLock().lock();
        try {
            secrets.put(key, value);
            saveSecrets();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // ============ 持久化 ============
    
    /**
     * 保存配置到磁盘
     */
    public void save() {
        lock.readLock().lock();
        try {
            // 创建配置对象，排除敏感信息
            Map<String, Object> safeConfig = new HashMap<>(config);
            safeConfig.remove("secrets");
            
            // 添加注释头
            StringBuilder yaml = new StringBuilder();
            yaml.append("# Tenant Configuration\n");
            yaml.append("# Auto-generated - Edit with caution\n\n");
            
            // 序列化为 YAML
            String yamlContent = yamlMapper.writeValueAsString(safeConfig);
            yaml.append(yamlContent);
            
            Files.writeString(configFile, yaml.toString());
            
            logger.debug("Saved tenant config to: {}", configFile);
        } catch (IOException e) {
            logger.error("Failed to save config", e);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 从磁盘加载配置
     */
    private void loadFromDisk() {
        lock.writeLock().lock();
        try {
            // 加载主配置
            if (Files.exists(configFile)) {
                String yamlContent = Files.readString(configFile);
                JsonNode root = yamlMapper.readTree(yamlContent);
                
                if (root.isObject()) {
                    Map<String, Object> loaded = jsonMapper.convertValue(root, Map.class);
                    deepMerge(config, loaded);
                }
                
                logger.debug("Loaded tenant config from: {}", configFile);
            }
            
            // 加载密钥
            loadSecrets();
            
        } catch (IOException e) {
            logger.error("Failed to load config from disk", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 保存密钥到文件
     */
    private void saveSecrets() {
        try {
            StringBuilder env = new StringBuilder();
            env.append("# Tenant Secrets\n");
            env.append("# WARNING: Keep this file secure!\n\n");
            
            for (Map.Entry<String, String> entry : secrets.entrySet()) {
                env.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
            
            Files.writeString(secretsFile, env.toString());
            
            // 设置文件权限（Unix）
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<java.nio.file.attribute.PosixFilePermission> perms = 
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(secretsFile, perms);
            }
            
        } catch (IOException e) {
            logger.error("Failed to save secrets", e);
        }
    }
    
    /**
     * 加载密钥
     */
    private void loadSecrets() {
        if (!Files.exists(secretsFile)) {
            return;
        }
        
        try {
            List<String> lines = Files.readAllLines(secretsFile);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    secrets.put(key, value);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load secrets", e);
        }
    }
    
    // ============ 工具方法 ============
    
    /**
     * 环境变量展开
     */
    private Object expandEnvVars(Object value) {
        if (value instanceof String) {
            String str = (String) value;
            Matcher matcher = ENV_VAR_PATTERN.matcher(str);
            StringBuffer sb = new StringBuffer();
            
            while (matcher.find()) {
                String varName = matcher.group(1);
                String varValue = System.getenv(varName);
                if (varValue == null) {
                    varValue = secrets.get(varName);
                }
                if (varValue != null) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(varValue));
                }
            }
            matcher.appendTail(sb);
            
            return sb.toString();
        }
        return value;
    }
    
    /**
     * 深度合并配置
     */
    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object existing = target.get(key);
            
            if (value instanceof Map && existing instanceof Map) {
                deepMerge((Map<String, Object>) existing, (Map<String, Object>) value);
            } else {
                target.put(key, value);
            }
        }
    }
    
    /**
     * 获取所有配置（用于调试）
     */
    public Map<String, Object> getAll() {
        lock.readLock().lock();
        try {
            return new HashMap<>(config);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // ============ 便捷方法 ============
    
    public String getModelProvider() {
        return getString("model.provider", "volcengine");
    }
    
    public String getModelName() {
        return getString("model.model", "deepseek-v3-250324");
    }
    
    public String getApiKey() {
        // 优先从密钥获取
        String secretKey = getSecret("API_KEY");
        if (secretKey != null) {
            return secretKey;
        }
        return getString("model.api_key");
    }
    
    public int getMaxTurns() {
        return getInt("agent.max_turns", 90);
    }
    
    public List<String> getEnabledTools() {
        return getStringList("tools.enabled");
    }
    
    public boolean isMemoryEnabled() {
        return getBoolean("memory.memory_enabled", false);
    }

    // ============ B1: 租户级模型配置 ============

    /** B3: Platform provider catalog (lazy, shared singleton). */
    private static volatile com.nousresearch.hermes.platform.ProviderCatalog sharedCatalog;

    /** B3: Get or initialize the shared provider catalog. */
    private static com.nousresearch.hermes.platform.ProviderCatalog getSharedCatalog() {
        if (sharedCatalog == null) {
            synchronized (TenantConfig.class) {
                if (sharedCatalog == null) {
                    sharedCatalog = com.nousresearch.hermes.platform.ProviderCatalog.withDefaults();
                }
            }
        }
        return sharedCatalog;
    }

    /** B3: Override the shared catalog (for testing or custom init). */
    public static void setSharedCatalog(com.nousresearch.hermes.platform.ProviderCatalog catalog) {
        sharedCatalog = catalog;
    }

    /**
     * B3: Validate that the configured provider is in the platform catalog.
     * @return true if the provider is registered, false otherwise
     */
    public boolean isProviderValid() {
        return getSharedCatalog().isRegistered(getModelProvider());
    }

    /** Convert any value to String, returning null for null values. */
    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * B2: Parse tenant-level model_routes from config.
     *
     * <p>Format (in tenant config.yaml):</p>
     * <pre>{@code
     * model_routes:
     *   - alias: fast
     *     model: gpt-4o-mini
     *     provider: openai
     *   - alias: smart
     *     model: claude-3.5-sonnet
     *     provider: anthropic
     * }</pre>
     *
     * @return list of tenant-level model routes (may be empty)
     */
    @SuppressWarnings("unchecked")
    public List<com.nousresearch.hermes.config.ModelRoute> getModelRoutes() {
        Object raw = get("model_routes");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<com.nousresearch.hermes.config.ModelRoute> routes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String alias = stringValue(map.get("alias"));
            String model = stringValue(map.get("model"));
            String provider = stringValue(map.get("provider"));
            String baseUrl = stringValue(map.get("base-url"));
            if (alias != null && !alias.isBlank() && model != null && !model.isBlank()) {
                routes.add(new com.nousresearch.hermes.config.ModelRoute(alias, model, provider, baseUrl));
            }
        }
        return routes;
    }

    /**
     * B2: Resolve a model route by alias.
     *
     * <p>Searches tenant model_routes first, then falls back to the given
     * platform (global) model_routes. Returns null if no match.</p>
     *
     * @param alias the alias to resolve (case-insensitive)
     * @param platformRoutes global model_routes from HermesConfig (may be empty)
     * @return matching ModelRoute, or null if not found
     */
    public com.nousresearch.hermes.config.ModelRoute resolveModelRoute(
            String alias,
            List<com.nousresearch.hermes.config.ModelRoute> platformRoutes) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        // 1. Tenant model_routes (tenant override)
        for (var route : getModelRoutes()) {
            if (route.getAlias().equalsIgnoreCase(alias)) {
                return route;
            }
        }
        // 2. Platform model_routes (global default)
        if (platformRoutes != null) {
            for (var route : platformRoutes) {
                if (route.getAlias().equalsIgnoreCase(alias)) {
                    return route;
                }
            }
        }
        return null;
    }

    /**
     * B2: Convenience method - resolve route using only tenant routes (no platform fallback).
     */
    public com.nousresearch.hermes.config.ModelRoute resolveModelRoute(String alias) {
        return resolveModelRoute(alias, null);
    }

    /**
     * B2: Resolve a ModelConfig by alias, combining model_routes + buildModelConfig().
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Tenant model_routes alias match -> use route's model/provider/base_url</li>
     *   <li>Platform model_routes alias match -> use route's model/provider/base_url</li>
     *   <li>No match -> return tenant default buildModelConfig()</li>
     * </ol>
     *
     * <p>API key is always resolved from TenantConfig.resolveApiKey() based on
     * the route's provider, never from the route itself.</p>
     *
     * @param alias the alias to resolve (may be null)
     * @param platformRoutes global model_routes for fallback (may be null)
     * @return resolved ModelConfig (never null)
     */
    public HermesConfig.ModelConfig resolveModelConfig(
            String alias,
            List<com.nousresearch.hermes.config.ModelRoute> platformRoutes) {
        com.nousresearch.hermes.config.ModelRoute route = resolveModelRoute(alias, platformRoutes);

        if (route != null) {
            String provider = route.getProvider() != null ? route.getProvider() : getModelProvider();
            String apiKey = resolveApiKey(provider);
            String baseUrl = route.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = resolveBaseUrl(provider);
            }
            return new HermesConfig.ModelConfig(provider, route.getModel(), apiKey, baseUrl);
        }

        // No route match -> use default tenant config
        return buildModelConfig();
    }

    /**
     * B2: Convenience overload - resolve without platform routes.
     */
    public HermesConfig.ModelConfig resolveModelConfig(String alias) {
        return resolveModelConfig(alias, null);
    }

    /**
     * B2: Get all available model aliases (tenant + platform).
     *
     * @param platformRoutes global model_routes (may be null)
     * @return list of all aliases, tenant routes first
     */
    public List<String> getAllModelAliases(
            List<com.nousresearch.hermes.config.ModelRoute> platformRoutes) {
        List<String> aliases = new ArrayList<>();
        for (var route : getModelRoutes()) {
            aliases.add(route.getAlias());
        }
        if (platformRoutes != null) {
            for (var route : platformRoutes) {
                if (!aliases.contains(route.getAlias())) {
                    aliases.add(route.getAlias());
                }
            }
        }
        return aliases;
    }

    /**
     * Build a {@link HermesConfig.ModelConfig} from this tenant's configuration.
     *
     * <p>Resolution order for API key:
     * <ol>
     *   <li>{@code secrets.env} → {@code API_KEY} (tenant-specific secret)</li>
     *   <li>{@code config.yaml} → {@code model.api_key}</li>
     *   <li>{@code HermesConfig} global default (caller responsibility)</li>
     * </ol>
     *
     * <p>Resolution order for base_url:
     * <ol>
     *   <li>{@code config.yaml} → {@code model.base_url} (if non-blank)</li>
     *   <li>Provider-specific default (hardcoded fallback)</li>
     * </ol>
     *
     * @return a ModelConfig suitable for constructing a {@code ModelClient}
     */
    public HermesConfig.ModelConfig buildModelConfig() {
        String provider = getModelProvider();
        String modelName = getModelName();
        String apiKey = resolveApiKey(provider);
        String baseUrl = resolveBaseUrl(provider);
        return new HermesConfig.ModelConfig(provider, modelName, apiKey, baseUrl);
    }

    /**
     * Build a ModelConfig from this tenant's configuration, falling back to
     * the given global {@link HermesConfig} for any missing values.
     *
     * @param global the global HermesConfig for fallback
     * @return a resolved ModelConfig (tenant values take priority)
     */
    public HermesConfig.ModelConfig buildModelConfig(HermesConfig global) {
        HermesConfig.ModelConfig globalMc = global.getModelConfig();
        String provider = getString("model.provider", globalMc.getProvider());
        String modelName = getString("model.model", globalMc.getName());
        String apiKey = resolveApiKey(provider);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = globalMc.getApiKey();
        }
        String baseUrl = getString("model.base_url", "");
        if (baseUrl.isBlank()) {
            baseUrl = globalMc.getBaseUrl();
        }
        return new HermesConfig.ModelConfig(provider, modelName, apiKey, baseUrl);
    }

    /**
     * Resolve API key for a given provider.
     *
     * <p>Checks (in order):
     * <ol>
     *   <li>{@code secrets.env} → {@code {PROVIDER}_API_KEY} (e.g. OPENAI_API_KEY)</li>
     *   <li>{@code secrets.env} → {@code API_KEY} (generic fallback)</li>
     *   <li>{@code config.yaml} → {@code model.api_key} (only when provider == default)</li>
     * </ol>
     *
     * @param provider the provider ID (e.g. "openai", "anthropic")
     * @return the API key, or {@code null} if not found
     */
    public String resolveApiKey(String provider) {
        // B6: Check key_source policy
        String keySource = getString("model.key_source", "hybrid");

        if ("platform".equalsIgnoreCase(keySource)) {
            // Platform-only: skip tenant secrets, go straight to platform key
            return getPlatformKey(provider);
        }

        // tenant or hybrid: try tenant secrets first
        // 1. Provider-specific key in secrets.env
        String envKey = providerToEnvKey(provider);
        String key = getSecret(envKey);
        if (key != null && !key.isBlank()) return key;

        // 2. Generic API_KEY in secrets.env
        key = getSecret("API_KEY");
        if (key != null && !key.isBlank()) return key;

        // 3. config.yaml model.api_key (only when provider matches tenant's default)
        if (provider.equalsIgnoreCase(getModelProvider())) {
            key = getString("model.api_key");
            if (key != null && !key.isBlank()) return key;
        }

        // B6: hybrid mode -> fall back to platform key
        if ("hybrid".equalsIgnoreCase(keySource)) {
            return getPlatformKey(provider);
        }

        return null;
    }

    /**
     * B6: Convert a provider ID to the standard env var key format.
     * "openai" -> "OPENAI_API_KEY", "deepseek" -> "DEEPSEEK_API_KEY"
     */
    public static String providerToEnvKey(String provider) {
        if (provider == null) return "API_KEY";
        return provider.toUpperCase().replace("-", "_") + "_API_KEY";
    }

    /**
     * B6: Get platform-managed API key for a provider.
     *
     * <p>Platform keys are stored in the global HermesConfig under
     * {@code platform_keys.{PROVIDER}_API_KEY}. This enables the "platform
     *代付" model where the platform holds keys and tenants use them.</p>
     *
     * @param provider the provider ID
     * @return platform API key, or null if not configured
     */
    public String getPlatformKey(String provider) {
        // Read from global system property / env var
        String envKey = providerToEnvKey(provider);
        // 1. JVM system property: -Dplatform.OPENAI_API_KEY=sk-xxx
        String key = System.getProperty("platform." + envKey);
        if (key != null && !key.isBlank()) return key;
        // 2. Environment variable: PLATFORM_OPENAI_API_KEY
        key = System.getenv("PLATFORM_" + envKey);
        if (key != null && !key.isBlank()) return key;
        return null;
    }

    // ============ B6: Multi-Provider Key Management API ============

    /**
     * B6: Set the API key for a specific provider.
     *
     * <p>Stores as {@code {PROVIDER}_API_KEY} in secrets.env.
     * The key is persisted immediately with file permissions 600.</p>
     *
     * @param provider the provider ID (e.g. "openai")
     * @param apiKey   the API key value
     */
    public void setProviderApiKey(String provider, String apiKey) {
        setSecret(providerToEnvKey(provider), apiKey);
    }

    /**
     * B6: Get the API key for a specific provider (from secrets.env only,
     * no platform fallback).
     *
     * @param provider the provider ID
     * @return the API key, or null if not set
     */
    public String getProviderApiKey(String provider) {
        return getSecret(providerToEnvKey(provider));
    }

    /**
     * B6: Remove the API key for a specific provider.
     *
     * @param provider the provider ID
     * @return true if a key was removed
     */
    public boolean removeProviderApiKey(String provider) {
        String envKey = providerToEnvKey(provider);
        String existing = secrets.remove(envKey);
        if (existing != null) {
            saveSecrets();
            logger.info("Removed API key for provider: {}", provider);
            return true;
        }
        return false;
    }

    /**
     * B6: List all configured provider API keys.
     *
     * <p>Returns a map of provider ID -> key env var name (not the actual key value,
     * for security). Use {@link #getProviderApiKey(String)} to get individual values.</p>
     *
     * @return map of provider ID to env var name (e.g. "openai" -> "OPENAI_API_KEY")
     */
    public Map<String, String> listProviderApiKeys() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : secrets.keySet()) {
            if (key.endsWith("_API_KEY") && !"API_KEY".equals(key)) {
                String providerId = key.substring(0, key.length() - "_API_KEY".length())
                    .toLowerCase().replace("_", "-");
                result.put(providerId, key);
            }
        }
        return result;
    }

    /**
     * B6: Check if a provider has an API key configured (tenant or platform).
     *
     * @param provider the provider ID
     * @return true if any API key is available for this provider
     */
    public boolean hasApiKey(String provider) {
        String key = resolveApiKey(provider);
        return key != null && !key.isBlank();
    }

    /**
     * B6: Get the key source policy for this tenant.
     *
     * @return "tenant", "platform", or "hybrid" (default)
     */
    public String getKeySource() {
        return getString("model.key_source", "hybrid");
    }

    /**
     * B6: Set the key source policy.
     *
     * @param keySource "tenant", "platform", or "hybrid"
     */
    public void setKeySource(String keySource) {
        set("model.key_source", keySource);
    }

    /**
     * B6: Validate that the tenant's model configuration is complete.
     *
     * <p>Checks:</p>
     * <ul>
     *   <li>Provider is in the platform catalog</li>
     *   <li>API key is available (tenant or platform)</li>
     *   <li>Base URL is resolvable</li>
     * </ul>
     *
     * @return validation result with issues (empty if valid)
     */
    public ValidationResult validateModelConfig() {
        List<String> issues = new ArrayList<>();
        var catalog = getSharedCatalog();

        String provider = getModelProvider();
        String model = getModelName();

        // 1. Provider in catalog
        if (!catalog.isRegistered(provider)) {
            issues.add("Provider '" + provider + "' is not in the platform catalog. " +
                "Available: " + catalog.listProviderIds());
        }

        // 2. Key source valid
        String keySource = getKeySource();
        if (!List.of("tenant", "platform", "hybrid").contains(keySource.toLowerCase())) {
            issues.add("Invalid key_source '" + keySource + "'. Must be: tenant, platform, or hybrid");
        }

        // 3. Key source allowed by catalog
        if (catalog.isRegistered(provider) && !catalog.isAllowed(provider, keySource)) {
            var p = catalog.getProvider(provider);
            issues.add("Provider '" + provider + "' does not allow key_source '" + keySource +
                "' (allowTenantKeys=" + (p != null && p.allowTenantKeys()) +
                ", allowPlatformKeys=" + (p != null && p.allowPlatformKeys()) + ")");
        }

        // 4. API key available
        if (!hasApiKey(provider)) {
            issues.add("No API key configured for provider '" + provider +
                "'. Set " + providerToEnvKey(provider) + " in secrets.env" +
                ("hybrid".equalsIgnoreCase(keySource) ? " or configure platform key" : ""));
        }

        // 5. Model name not blank
        if (model == null || model.isBlank()) {
            issues.add("Model name is not configured");
        }

        return new ValidationResult(issues);
    }

    /**
     * B6: Validation result for model configuration.
     */
    public record ValidationResult(List<String> issues) {
        public boolean isValid() { return issues.isEmpty(); }
        public String summary() {
            return isValid() ? "OK" : String.join("; ", issues);
        }
    }

    /**
     * Resolve base URL for a given provider.
     * Uses the platform ProviderCatalog for defaults.
     *
     * @param provider the provider ID
     * @return the base URL (never null; falls back to catalog default then openrouter)
     */
    public String resolveBaseUrl(String provider) {
        String configured = getString("model.base_url", "");
        if (!configured.isBlank()) return configured;
        // B3: Use ProviderCatalog for default URL
        return getSharedCatalog().getDefaultBaseUrlOrFallback(provider);
    }
}
