package com.nousresearch.hermes.tenant.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 租户记忆管理器
 * 
 * 为每个租户提供完全隔离的记忆存储，包括：
 * - MEMORY.md: 系统记忆（AI 需要记住的事实）
 * - USER.md: 用户画像（用户偏好、习惯等）
 * - 向量存储: 用于语义搜索
 * 
 * 存储路径: ~/.hermes/tenants/{tenantId}/memories/
 * 
 * 特性：
 * - 租户间完全隔离
 * - 支持系统提示词注入
 * - 安全扫描防止提示词注入
 * - 字符限制自动修剪
 * - 去重和相似度检测
 */
public class TenantMemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(TenantMemoryManager.class);
    
    // Entry delimiter - same as Python version
    private static final String ENTRY_DELIMITER = "\n§\n";
    
    // Character limits
    private static final int MEMORY_CHAR_LIMIT = 2200;
    private static final int USER_CHAR_LIMIT = 1375;
    
    private final String tenantId;
    private final Path memoriesDir;
    private final Path memoryFile;
    private final Path userFile;
    private final Path embeddingsDir;
    
    // Lock for thread-safe operations
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Live state
    private List<MemoryEntry> memoryEntries = new ArrayList<>();
    private List<MemoryEntry> userEntries = new ArrayList<>();
    
    // Frozen snapshot for system prompt
    private final Map<String, String> systemPromptSnapshot = new HashMap<>();
    private Instant snapshotTimestamp;
    
    // Security patterns
    private static final Pattern[] THREAT_PATTERNS = {
        Pattern.compile("ignore\\s+(previous|all|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
        Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("do\\s+not\\s+tell\\s+the\\s+user", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE),
        Pattern.compile("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE),
    };
    
    private static final Set<Character> INVISIBLE_CHARS = Set.of(
        '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff',
        '\u202a', '\u202b', '\u202c', '\u202d', '\u202e'
    );
    
    public TenantMemoryManager(String tenantId, Path memoriesDir) {
        this.tenantId = tenantId;
        this.memoriesDir = memoriesDir;
        this.memoryFile = memoriesDir.resolve("MEMORY.md");
        this.userFile = memoriesDir.resolve("USER.md");
        this.embeddingsDir = memoriesDir.resolve("embeddings");
        
        try {
            Files.createDirectories(memoriesDir);
            Files.createDirectories(embeddingsDir);
        } catch (IOException e) {
            logger.error("Failed to create memories directory for tenant: {}", tenantId, e);
        }
        
        // Load from disk
        loadFromDisk();
    }
    
    public static TenantMemoryManager load(String tenantId, Path memoriesDir) {
        return new TenantMemoryManager(tenantId, memoriesDir);
    }
    
    // ============ 核心操作 ============
    
    /**
     * 添加系统记忆
     */
    public boolean addMemory(String content) {
        return addEntry(content, "memory", MEMORY_CHAR_LIMIT);
    }
    
    /**
     * 添加系统记忆（带标签）
     */
    public boolean addMemory(String content, Set<String> tags) {
        return addEntry(content, "memory", MEMORY_CHAR_LIMIT, tags);
    }
    
    /**
     * 添加用户画像
     */
    public boolean addUser(String content) {
        return addEntry(content, "user", USER_CHAR_LIMIT);
    }
    
    /**
     * 搜索记忆
     */
    public List<MemoryEntry> search(String query, int limit) {
        lock.readLock().lock();
        try {
            String lowerQuery = query.toLowerCase();
            
            List<MemoryEntry> results = new ArrayList<>();
            
            // 搜索内存条目
            for (MemoryEntry entry : memoryEntries) {
                if (entry.content().toLowerCase().contains(lowerQuery) ||
                    entry.tags().stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery))) {
                    results.add(entry);
                }
            }
            
            // 搜索用户条目
            for (MemoryEntry entry : userEntries) {
                if (entry.content().toLowerCase().contains(lowerQuery) ||
                    entry.tags().stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery))) {
                    results.add(entry);
                }
            }
            
            // 按时间排序，最新的在前
            results.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
            
            return results.stream().limit(limit).collect(Collectors.toList());
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 按分类获取记忆
     */
    public List<MemoryEntry> getByCategory(String category, int limit) {
        lock.readLock().lock();
        try {
            List<MemoryEntry> entries = "user".equals(category) ? userEntries : memoryEntries;
            return entries.stream()
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .limit(limit)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 按标签获取记忆
     */
    public List<MemoryEntry> getByTag(String tag, int limit) {
        lock.readLock().lock();
        try {
            List<MemoryEntry> results = new ArrayList<>();
            
            for (MemoryEntry entry : memoryEntries) {
                if (entry.tags().contains(tag)) {
                    results.add(entry);
                }
            }
            
            for (MemoryEntry entry : userEntries) {
                if (entry.tags().contains(tag)) {
                    results.add(entry);
                }
            }
            
            return results.stream()
                .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                .limit(limit)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 删除记忆
     */
    public boolean delete(String category, String substring) {
        lock.writeLock().lock();
        try {
            List<MemoryEntry> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;
            
            boolean removed = entries.removeIf(e -> e.content().contains(substring));
            if (removed) {
                writeFile(file, entries);
                refreshSnapshot();
                logger.debug("Tenant {}: Deleted entry from {} matching: {}", 
                    tenantId, category, substring);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 替换记忆
     */
    public boolean replace(String category, String oldSubstring, String newContent) {
        lock.writeLock().lock();
        try {
            // 安全扫描
            String scanResult = scanContent(newContent);
            if (scanResult != null) {
                logger.warn("Tenant {}: Blocked memory replacement: {}", tenantId, scanResult);
                return false;
            }
            
            List<MemoryEntry> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;
            
            boolean replaced = false;
            for (int i = 0; i < entries.size(); i++) {
                MemoryEntry entry = entries.get(i);
                if (entry.content().contains(oldSubstring)) {
                    entries.set(i, new MemoryEntry(
                        newContent,
                        entry.tags(),
                        Instant.now()
                    ));
                    replaced = true;
                    break;
                }
            }
            
            if (replaced) {
                writeFile(file, entries);
                refreshSnapshot();
                logger.debug("Tenant {}: Replaced entry in {} matching: {}", 
                    tenantId, category, oldSubstring);
            }
            return replaced;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 更新快照（在修改后调用）
     */
    public void refreshSnapshot() {
        lock.writeLock().lock();
        try {
            systemPromptSnapshot.put("memory", renderBlock("memory", memoryEntries));
            systemPromptSnapshot.put("user", renderBlock("user", userEntries));
            snapshotTimestamp = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取系统提示词快照
     */
    public String getSystemPromptSnapshot() {
        lock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            
            String memoryBlock = systemPromptSnapshot.get("memory");
            if (memoryBlock != null && !memoryBlock.isEmpty()) {
                sb.append(memoryBlock);
            }
            
            String userBlock = systemPromptSnapshot.get("user");
            if (userBlock != null && !userBlock.isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(userBlock);
            }
            
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // ============ 存储统计 ============
    
    public MemoryStats getStats() {
        lock.readLock().lock();
        try {
            int memoryChars = memoryEntries.stream().mapToInt(e -> e.content().length()).sum();
            int userChars = userEntries.stream().mapToInt(e -> e.content().length()).sum();
            
            return new MemoryStats(
                memoryEntries.size(),
                userEntries.size(),
                memoryChars,
                userChars,
                MEMORY_CHAR_LIMIT,
                USER_CHAR_LIMIT
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // ============ 私有方法 ============
    
    private void loadFromDisk() {
        lock.writeLock().lock();
        try {
            memoryEntries = readFile(memoryFile);
            userEntries = readFile(userFile);
            
            // 去重
            memoryEntries = deduplicate(memoryEntries);
            userEntries = deduplicate(userEntries);
            
            // 创建快照
            refreshSnapshot();
            
            logger.info("Tenant {}: Loaded {} memory entries, {} user entries", 
                tenantId, memoryEntries.size(), userEntries.size());
                
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private boolean addEntry(String content, String category, int charLimit) {
        return addEntry(content, category, charLimit, Set.of());
    }
    
    private boolean addEntry(String content, String category, int charLimit, Set<String> tags) {
        // 安全扫描
        String scanResult = scanContent(content);
        if (scanResult != null) {
            logger.warn("Tenant {}: Blocked memory entry: {}", tenantId, scanResult);
            return false;
        }
        
        lock.writeLock().lock();
        try {
            List<MemoryEntry> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;
            
            // 检查相似条目
            entries.removeIf(e -> isSimilar(e.content(), content));
            
            // 添加新条目
            MemoryEntry newEntry = new MemoryEntry(content, tags, Instant.now());
            entries.add(newEntry);
            
            // 修剪到限制内
            List<MemoryEntry> pruned = pruneEntries(entries, charLimit);
            
            if ("user".equals(category)) {
                userEntries = pruned;
            } else {
                memoryEntries = pruned;
            }
            
            // 持久化
            writeFile(file, pruned);
            
            // 刷新快照
            refreshSnapshot();
            
            logger.debug("Tenant {}: Added {} entry: {}", tenantId, category,
                content.substring(0, Math.min(50, content.length())));
            return true;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // Frontmatter pattern for YAML metadata
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
        "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    
    private final Yaml yaml = new Yaml();
    
    private List<MemoryEntry> readFile(Path file) {
        try {
            if (!Files.exists(file)) {
                return new ArrayList<>();
            }
            
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Check if file has frontmatter format
            if (content.startsWith("---")) {
                return parseFrontmatterEntries(content);
            }
            
            // Legacy delimiter format
            String[] parts = content.split(ENTRY_DELIMITER, -1);
            List<MemoryEntry> entries = new ArrayList<>();
            
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;
                
                entries.add(new MemoryEntry(part, Set.of(), Instant.now()));
            }
            
            return entries;
            
        } catch (Exception e) {
            logger.error("Tenant {}: Failed to read file {}: {}", tenantId, file, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Parse entries with YAML frontmatter
     * Format:
     * ---
     * tags: [tag1, tag2]
     * created: 2024-01-01T00:00:00Z
     * ---
     * Content here
     * 
     * ---
     * tags: [another]
     * ---
     * Another entry
     */
    private List<MemoryEntry> parseFrontmatterEntries(String content) {
        List<MemoryEntry> entries = new ArrayList<>();
        
        // Split by frontmatter separator
        String[] sections = content.split("(?m)^---\\s*$");
        
        for (int i = 0; i < sections.length; i++) {
            String section = sections[i].trim();
            if (section.isEmpty()) continue;
            
            // First section might be just content without frontmatter
            if (i == 0 && !content.startsWith("---")) {
                entries.add(new MemoryEntry(section, Set.of(), Instant.now()));
                continue;
            }
            
            // Parse frontmatter + content
            int contentStart = section.indexOf('\n');
            if (contentStart == -1) continue;
            
            String frontmatter = section.substring(0, contentStart).trim();
            String entryContent = section.substring(contentStart + 1).trim();
            
            if (entryContent.isEmpty()) continue;
            
            // Parse YAML frontmatter
            Set<String> tags = Set.of();
            Instant timestamp = Instant.now();
            
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = yaml.load(frontmatter);
                if (meta != null) {
                    // Parse tags
                    Object tagsObj = meta.get("tags");
                    if (tagsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> tagList = (List<String>) tagsObj;
                        tags = new HashSet<>(tagList);
                    } else if (tagsObj instanceof String) {
                        tags = Set.of((String) tagsObj);
                    }
                    
                    // Parse timestamp
                    Object timeObj = meta.get("created");
                    if (timeObj instanceof String) {
                        try {
                            timestamp = Instant.parse((String) timeObj);
                        } catch (Exception e) {
                            logger.debug("Failed to parse timestamp: {}", timeObj);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to parse frontmatter: {}", e.getMessage());
            }
            
            entries.add(new MemoryEntry(entryContent, tags, timestamp));
        }
        
        return entries;
    }
    
    private void writeFile(Path file, List<MemoryEntry> entries) {
        try {
            // Use YAML frontmatter format for better metadata support
            StringBuilder sb = new StringBuilder();
            
            for (MemoryEntry entry : entries) {
                // Write YAML frontmatter
                sb.append("---\n");
                
                Map<String, Object> frontmatter = new LinkedHashMap<>();
                if (!entry.tags().isEmpty()) {
                    frontmatter.put("tags", new ArrayList<>(entry.tags()));
                }
                frontmatter.put("created", entry.timestamp().toString());
                
                sb.append(yaml.dump(frontmatter));
                sb.append("---\n");
                
                // Write content
                sb.append(entry.content());
                sb.append("\n\n");
            }
            
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Tenant {}: Failed to write file {}: {}", tenantId, file, e.getMessage());
        }
    }
    
    private String renderBlock(String category, List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(capitalize(category)).append(" Memory\n\n");
        
        for (MemoryEntry entry : entries) {
            sb.append("- ").append(entry.content().replace("\n", "\n  ")).append("\n");
        }
        
        return sb.toString();
    }
    
    private List<MemoryEntry> deduplicate(List<MemoryEntry> entries) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<MemoryEntry> result = new ArrayList<>();
        
        for (MemoryEntry entry : entries) {
            if (seen.add(entry.content())) {
                result.add(entry);
            }
        }
        
        return result;
    }
    
    private boolean isSimilar(String a, String b) {
        if (a.equalsIgnoreCase(b)) return true;
        if (a.length() > 20 && b.length() > 20) {
            return a.substring(0, 20).equalsIgnoreCase(b.substring(0, 20));
        }
        return false;
    }
    
    private List<MemoryEntry> pruneEntries(List<MemoryEntry> entries, int charLimit) {
        int totalChars = entries.stream().mapToInt(e -> e.content().length()).sum();
        
        List<MemoryEntry> result = new ArrayList<>(entries);
        
        while (totalChars > charLimit && !result.isEmpty()) {
            // 移除最旧的条目
            MemoryEntry removed = result.remove(0);
            totalChars -= removed.content().length();
        }
        
        return result;
    }
    
    private String scanContent(String content) {
        // 检查不可见字符
        for (char c : content.toCharArray()) {
            if (INVISIBLE_CHARS.contains(c)) {
                return String.format("Blocked: content contains invisible unicode character U+%04X", (int) c);
            }
        }
        
        // 检查威胁模式
        for (Pattern pattern : THREAT_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return String.format("Blocked: content matches threat pattern '%s'", pattern.pattern());
            }
        }
        
        return null;
    }
    
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    
    // ============ 记录类 ============
    
    public record MemoryEntry(String content, Set<String> tags, Instant timestamp) {}
    
    public record MemoryStats(
        int memoryCount,
        int userCount,
        int memoryChars,
        int userChars,
        int memoryLimit,
        int userLimit
    ) {
        public double memoryUsagePercent() {
            return memoryLimit > 0 ? (double) memoryChars / memoryLimit * 100 : 0;
        }
        
        public double userUsagePercent() {
            return userLimit > 0 ? (double) userChars / userLimit * 100 : 0;
        }
    }
}
