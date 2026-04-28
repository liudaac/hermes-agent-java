package com.nousresearch.hermes.tenant.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
        
        // 保存初始配置
        try {
            Files.createDirectories(configDir);
            save();
        } catch (IOException e) {
            logger.error("Failed to create config directory", e);
        }
    }
    
    /**
     * 从磁盘加载配置
     */
    public static TenantConfig load(Path configDir) {
        TenantConfig tenantConfig = new TenantConfig(configDir, null);
        tenantConfig.loadFromDisk();
        return tenantConfig;
    }
    
    // ============ 默认配置 ============
    
    private void loadDefaults() {
        // 模型配置
        set("model.provider", "openrouter");
        set("model.model", "anthropic/claude-3.5-sonnet");
        set("model.base_url", "");
        set("model.api_key", "");
        set("model.temperature", 0.7);
        set("model.max_tokens", 4096);
        
        // Agent 配置
        set("agent.max_turns", 90);
        set("agent.gateway_timeout", 300);
        set("agent.gateway_timeout_warning", 900);
        set("agent.restart_drain_timeout", 60);
        set("agent.tool_use_enforcement", "auto");
        
        // 终端配置
        set("terminal.backend", "local");
        set("terminal.timeout", 300);
        set("terminal.docker_image", "hermes-sandbox:latest");
        set("terminal.persistent_shell", true);
        
        // 工具配置
        set("tools.enabled", List.of("web_search", "terminal", "file_operations", "browser"));
        
        // 浏览器配置
        set("browser.inactivity_timeout", 120);
        set("browser.record_sessions", false);
        
        // 显示配置
        set("display.compact", false);
        set("display.show_thinking", false);
        set("display.streaming", true);
        
        // 压缩配置
        set("compression.enabled", true);
        set("compression.threshold", 0.5);
        
        // 记忆配置
        set("memory.memory_enabled", false);
        set("memory.user_profile_enabled", false);
        set("memory.nudge_interval", 10);
        
        // Skills 配置
        set("skills.creation_nudge_interval", 10);
        set("skills.auto_load", List.of());
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
        return getString("model.provider", "openrouter");
    }
    
    public String getModelName() {
        return getString("model.model", "anthropic/claude-3.5-sonnet");
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
}
