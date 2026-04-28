package com.nousresearch.hermes.tenant.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 租户 Skill 管理器
 *
 * 提供租户级别的 Skill 隔离和管理：
 * - 四层分级：私有 > 已安装 > 共享 > 系统
 * - 安全扫描：签名验证、内容扫描
 * - 安装管理：从 Registry 安装/卸载
 * - 分享机制：租户间 Skill 共享
 * - 缓存优化：内存缓存 + 文件监听
 *
 * 存储结构：
 * ~/.hermes/tenants/{tenantId}/skills/
 * ├── private/          # 租户私有 Skills
 * ├── installed/        # 从 Registry 安装的 Skills
 * └── index.json        # 本地索引
 */
public class TenantSkillManager {
    private static final Logger logger = LoggerFactory.getLogger(TenantSkillManager.class);

    // Frontmatter 解析模式
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
        "^---\\n(.*?)\\n---\\n\\n(.*)$", Pattern.DOTALL);

    // 危险代码模式（用于安全扫描）
    private static final Pattern[] DANGEROUS_PATTERNS = {
        Pattern.compile("(?i)(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|Process)"),
        Pattern.compile("(?i)os\\.system\\s*\\("),
        Pattern.compile("(?i)subprocess\\.(call|run|Popen)"),
        Pattern.compile("(?i)exec\\s*\\("),
        Pattern.compile("(?i)(rm\\s+-rf|del\\s+/f)"),
        Pattern.compile("(?i)curl.*\\|.*sh"),
        Pattern.compile("(?i)wget.*\\|.*(bash|sh)"),
        Pattern.compile("(?i)eval\\s*\\("),
    };

    private final String tenantId;
    private final Path skillsDir;
    private final Path privateDir;
    private final Path installedDir;
    private final Path systemSkillsDir;
    private final TenantContext context;

    // 缓存
    private final ConcurrentHashMap<String, CachedSkill> skillCache;
    private final ReentrantReadWriteLock cacheLock;

    // Skill 来源
    public enum SkillSource {
        PRIVATE,      // 租户私有创建
        INSTALLED,    // 从 Registry 安装
        SHARED,       // 从其他租户共享
        SYSTEM,       // 系统预设（只读）
        BUILTIN       // 内置 Skills
    }

    public TenantSkillManager(String tenantId, Path skillsDir, TenantContext context) {
        this.tenantId = tenantId;
        this.skillsDir = skillsDir;
        this.privateDir = skillsDir.resolve("private");
        this.installedDir = skillsDir.resolve("installed");
        this.systemSkillsDir = skillsDir.resolve("../../_shared/skills").normalize();
        this.context = context;
        this.skillCache = new ConcurrentHashMap<>();
        this.cacheLock = new ReentrantReadWriteLock();

        try {
            createDirectoryStructure();
        } catch (IOException e) {
            logger.error("Failed to create skills directory for tenant: {}", tenantId, e);
        }
    }

    public static TenantSkillManager load(String tenantId, Path skillsDir, TenantContext context) {
        return new TenantSkillManager(tenantId, skillsDir, context);
    }

    // ============ 目录结构 ============

    private void createDirectoryStructure() throws IOException {
        Files.createDirectories(skillsDir);
        Files.createDirectories(privateDir);
        Files.createDirectories(installedDir);

        logger.debug("Created skills directory structure for tenant: {}", tenantId);
    }

    // ============ Skill 加载 ============

    /**
     * 加载 Skill（带层级查找）
     */
    public TenantSkill loadSkill(String name) {
        // 1. 检查缓存
        cacheLock.readLock().lock();
        try {
            CachedSkill cached = skillCache.get(name);
            if (cached != null && !isExpired(cached)) {
                return cached.skill();
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        // 2. 按优先级查找
        TenantSkill skill = null;
        SkillSource source = null;

        // 2.1 私有 Skills（最高优先级）
        skill = loadFromDirectory(privateDir, name);
        if (skill != null) {
            source = SkillSource.PRIVATE;
        }

        // 2.2 安装的 Skills
        if (skill == null) {
            skill = loadFromDirectory(installedDir, name);
            if (skill != null) {
                source = SkillSource.INSTALLED;
            }
        }

        // 2.3 系统 Skills（只读）
        if (skill == null && Files.exists(systemSkillsDir)) {
            skill = loadFromDirectory(systemSkillsDir, name);
            if (skill != null) {
                source = SkillSource.SYSTEM;
                skill = skill.withReadOnly(true);
            }
        }

        // 2.4 内置 Skills
        if (skill == null) {
            skill = loadBuiltinSkill(name);
            if (skill != null) {
                source = SkillSource.BUILTIN;
                skill = skill.withReadOnly(true);
            }
        }

        // 3. 更新缓存
        if (skill != null) {
            skill = skill.withSource(source);
            cacheSkill(name, skill);
        }

        return skill;
    }

    /**
     * 获取所有可用 Skills
     */
    public List<TenantSkill> listAvailableSkills() {
        Set<String> seen = new HashSet<>();
        List<TenantSkill> skills = new ArrayList<>();

        // 按优先级加载
        loadAllFromDirectory(privateDir, SkillSource.PRIVATE, skills, seen);
        loadAllFromDirectory(installedDir, SkillSource.INSTALLED, skills, seen);
        if (Files.exists(systemSkillsDir)) {
            loadAllFromDirectory(systemSkillsDir, SkillSource.SYSTEM, skills, seen);
        }
        loadAllBuiltinSkills(skills, seen);

        return skills.stream()
            .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
            .collect(Collectors.toList());
    }

    // ============ Skill 创建 ============

    /**
     * 创建私有 Skill
     */
    public TenantSkill createPrivateSkill(String name, String description, String content,
                                           List<String> tags, Map<String, Object> metadata) throws IOException {
        // 检查配额
        if (!checkSkillQuota()) {
            throw new QuotaExceededException("Maximum number of private skills reached");
        }

        // 安全扫描
        SecurityScanResult scan = scanSkillContent(content);
        if (!scan.isSafe()) {
            logger.warn("Tenant {}: Skill creation blocked - security violation: {}",
                tenantId, scan.reason());
            throw new SecurityException("Skill content failed security scan: " + scan.reason());
        }

        String safeName = sanitizeName(name);
        Path skillDir = privateDir.resolve(safeName);

        TenantSkill skill = new TenantSkill(
            safeName,
            description,
            content,
            tags != null ? new HashSet<>(tags) : new HashSet<>(),
            metadata != null ? metadata : new HashMap<>(),
            1,
            0,
            Instant.now(),
            Instant.now(),
            null,
            null,
            SkillSource.PRIVATE,
            tenantId,
            false,
            generateSignature(content)
        );

        saveSkill(skillDir, skill);
        cacheSkill(safeName, skill);

        // 审计日志
        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.SKILL_CREATED,
            Map.of("tenantId", tenantId, "skillName", name, "source", "private")
        );

        logger.info("Tenant {}: Created private skill: {}", tenantId, safeName);
        return skill;
    }

    // ============ Skill 安装 ============

    /**
     * 从 Registry 安装 Skill
     */
    public TenantSkill installFromRegistry(String skillId, String version) throws IOException {
        // 检查配额
        if (!checkInstallQuota()) {
            throw new QuotaExceededException("Maximum number of installed skills reached");
        }

        logger.info("Tenant {}: Installing skill {} version {}", tenantId, skillId, version);

        // 1. 从 Registry 下载
        SkillPackage pkg = downloadFromRegistry(skillId, version);
        if (pkg == null) {
            throw new IOException("Failed to download skill from registry: " + skillId);
        }

        // 2. 安全扫描
        SecurityScanResult scan = scanSkillContent(pkg.content());
        if (!scan.isSafe()) {
            logger.warn("Tenant {}: Skill {} failed security scan: {}", tenantId, skillId, scan.reason());
            throw new SecurityException("Skill package failed security scan: " + scan.reason());
        }

        // 3. 安装到租户目录
        Path installDir = installedDir.resolve(sanitizeName(skillId));
        Files.createDirectories(installDir);

        // 4. 创建 Skill 对象
        TenantSkill skill = new TenantSkill(
            sanitizeName(skillId),
            pkg.description(),
            pkg.content(),
            pkg.tags(),
            Map.of("registry_id", skillId, "version", version, "source_url", pkg.sourceUrl()),
            1,
            0,
            Instant.now(),
            Instant.now(),
            skillId,
            version,
            SkillSource.INSTALLED,
            tenantId,
            false,
            pkg.signature()
        );

        saveSkill(installDir, skill);
        cacheSkill(sanitizeName(skillId), skill);

        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.SKILL_INSTALLED,
            Map.of("tenantId", tenantId, "skillId", skillId, "version", version)
        );

        logger.info("Tenant {}: Successfully installed skill {}@{}", tenantId, skillId, version);
        return skill;
    }
    
    /**
     * Download skill package from registry
     */
    private SkillPackage downloadFromRegistry(String skillId, String version) {
        // Get registry URL from config
        String registryUrl = context.getConfig().getString("skills.registry.url", 
            "https://skillhub.tencent.com/api/v1");
        
        try {
            // Check local cache first
            Path cacheDir = skillsDir.resolve(".cache").resolve("registry");
            Files.createDirectories(cacheDir);
            Path cachedSkill = cacheDir.resolve(skillId + "-" + version + ".zip");
            
            if (Files.exists(cachedSkill)) {
                logger.debug("Using cached skill: {}", cachedSkill);
                return loadSkillPackageFromCache(cachedSkill, skillId, version);
            }
            
            // TODO: Implement actual HTTP download when registry API is available
            // For now, return null to indicate skill not found
            logger.warn("Registry download not implemented. Skill not found in cache: {}", skillId);
            return null;
            
        } catch (Exception e) {
            logger.error("Failed to download skill from registry: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Load skill package from local cache
     */
    private SkillPackage loadSkillPackageFromCache(Path cacheFile, String skillId, String version) {
        try {
            // Parse cached skill package
            // This is a placeholder - actual implementation would unzip and parse
            String content = Files.readString(cacheFile.resolve("SKILL.md"));
            String description = "Cached skill";
            Set<String> tags = Set.of();
            String signature = "";
            String sourceUrl = "";
            
            return new SkillPackage(skillId, version, description, content, tags, signature, sourceUrl);
        } catch (IOException e) {
            logger.error("Failed to load cached skill: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 卸载 Skill
     */
    public boolean uninstallSkill(String name) throws IOException {
        TenantSkill skill = loadSkill(name);
        if (skill == null) return false;

        // 只能卸载 INSTALLED 类型的 Skill
        if (skill.source() != SkillSource.INSTALLED) {
            logger.warn("Tenant {}: Cannot uninstall skill {} - not installed from registry",
                tenantId, name);
            return false;
        }

        Path skillDir = installedDir.resolve(name);
        deleteDirectory(skillDir);

        // 清除缓存
        skillCache.remove(name);

        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.SKILL_UNINSTALLED,
            Map.of("tenantId", tenantId, "skillName", name)
        );

        logger.info("Tenant {}: Uninstalled skill: {}", tenantId, name);
        return true;
    }

    // ============ Skill 分享 ============

    /**
     * 分享 Skill 给其他租户
     */
    public void shareSkill(String name, List<String> targetTenants) throws IOException {
        TenantSkill skill = loadSkill(name);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + name);
        }

        if (skill.source() != SkillSource.PRIVATE) {
            throw new IllegalArgumentException("Only private skills can be shared");
        }

        // 复制到共享目录
        Path sharedDir = skillsDir.resolve("../../_shared/skills").normalize();
        try {
            Files.createDirectories(sharedDir);
            Path sharedSkillDir = sharedDir.resolve(name);
            copyDirectory(privateDir.resolve(name), sharedSkillDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to share skill", e);
        }

        context.getAuditLogger().log(
            com.nousresearch.hermes.tenant.audit.AuditEvent.SKILL_SHARED,
            Map.of("fromTenant", tenantId, "skillName", name, "toTenants", targetTenants)
        );

        logger.info("Tenant {}: Shared skill {} to tenants: {}", tenantId, name, targetTenants);
    }

    // ============ 安全扫描 ============

    /**
     * 扫描 Skill 内容安全性
     */
    public SecurityScanResult scanSkillContent(String content) {
        // 1. 提取代码块
        List<CodeBlock> codeBlocks = extractCodeBlocks(content);

        for (CodeBlock block : codeBlocks) {
            // 检查是否为可执行语言
            if (isExecutableLanguage(block.language())) {
                // 扫描代码内容
                for (Pattern pattern : DANGEROUS_PATTERNS) {
                    Matcher matcher = pattern.matcher(block.content());
                    if (matcher.find()) {
                        return SecurityScanResult.failed(
                            "Dangerous pattern detected in " + block.language() +
                            " code: " + matcher.group());
                    }
                }
            }
        }

        // 2. 扫描内联命令建议
        if (content.matches("(?i).*curl.*\\|.*sh.*")) {
            return SecurityScanResult.failed("Suspicious pipe to shell detected");
        }

        return SecurityScanResult.ok();
    }

    // ============ 私有方法 ============

    private TenantSkill loadFromDirectory(Path dir, String name) {
        Path skillFile = dir.resolve(name).resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            return null;
        }

        return parseSkillFile(skillFile, name);
    }

    private void loadAllFromDirectory(Path dir, SkillSource source,
                                       List<TenantSkill> skills, Set<String> seen) {
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(Files::isDirectory)
                .forEach(skillDir -> {
                    String name = skillDir.getFileName().toString();
                    if (seen.add(name)) {
                        TenantSkill skill = parseSkillFile(skillDir.resolve("SKILL.md"), name);
                        if (skill != null) {
                            skills.add(skill.withSource(source)
                                .withReadOnly(source == SkillSource.SYSTEM));
                        }
                    }
                });
        } catch (IOException e) {
            logger.debug("Failed to list skills from {}: {}", dir, e.getMessage());
        }
    }

    private TenantSkill parseSkillFile(Path file, String defaultName) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
            if (!matcher.find()) {
                // 无 frontmatter，整个内容作为 skill
                return new TenantSkill(
                    defaultName, "", content, Set.of(), Map.of(),
                    1, 0, Instant.now(), Instant.now(),
                    null, null, null, null, false, null);
            }

            String frontmatter = matcher.group(1);
            String skillContent = matcher.group(2);

            Map<String, String> meta = parseFrontmatter(frontmatter);

            return new TenantSkill(
                meta.getOrDefault("name", defaultName),
                meta.getOrDefault("description", ""),
                skillContent,
                parseTags(meta.get("tags")),
                parseMetadata(meta.get("metadata")),
                Integer.parseInt(meta.getOrDefault("version", "1")),
                Integer.parseInt(meta.getOrDefault("usage_count", "0")),
                parseInstant(meta.get("created_at")),
                parseInstant(meta.get("updated_at")),
                meta.get("registry_id"),
                meta.get("version"),
                null, null, false, null
            );

        } catch (Exception e) {
            logger.error("Failed to parse skill file: {}", file, e);
            return null;
        }
    }

    private Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> result = new HashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                result.put(key, value);
            }
        }
        return result;
    }

    private Set<String> parseTags(String tagsStr) {
        if (tagsStr == null || tagsStr.isEmpty()) return Set.of();
        return Arrays.stream(tagsStr.replace("[", "").replace("]", "").split(",\\s*"))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    private Map<String, Object> parseMetadata(String metaStr) {
        // 简化实现，实际应解析 YAML
        return new HashMap<>();
    }

    private Instant parseInstant(String str) {
        if (str == null || str.isEmpty()) return Instant.now();
        try {
            return Instant.parse(str);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private void saveSkill(Path dir, TenantSkill skill) throws IOException {
        Files.createDirectories(dir);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.name()).append("\n");
        sb.append("description: ").append(skill.description()).append("\n");
        sb.append("version: ").append(skill.version()).append("\n");
        sb.append("created_at: ").append(skill.createdAt()).append("\n");
        sb.append("updated_at: ").append(skill.updatedAt()).append("\n");
        sb.append("tags: ").append(skill.tags()).append("\n");
        sb.append("usage_count: ").append(skill.usageCount()).append("\n");
        if (skill.registryId() != null) {
            sb.append("registry_id: ").append(skill.registryId()).append("\n");
        }
        sb.append("---\n\n");
        sb.append(skill.content());

        Files.writeString(dir.resolve("SKILL.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void cacheSkill(String name, TenantSkill skill) {
        cacheLock.writeLock().lock();
        try {
            skillCache.put(name, new CachedSkill(skill, Instant.now()));
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private boolean isExpired(CachedSkill cached) {
        // 缓存1小时过期
        return cached.timestamp().plusSeconds(3600).isBefore(Instant.now());
    }

    private boolean checkSkillQuota() {
        // 检查私有 Skill 数量限制
        long privateCount = skillCache.values().stream()
            .filter(c -> c.skill().source() == SkillSource.PRIVATE)
            .count();
        return privateCount < context.getConfig().getInt("skills.max_private", 50);
    }

    private boolean checkInstallQuota() {
        // 检查安装 Skill 数量限制
        long installedCount = skillCache.values().stream()
            .filter(c -> c.skill().source() == SkillSource.INSTALLED)
            .count();
        return installedCount < context.getConfig().getInt("skills.max_installed", 100);
    }

    private String sanitizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    private String generateSignature(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private TenantSkill loadBuiltinSkill(String name) {
        String resourcePath = "skills/builtin/" + name + "/SKILL.md";
        
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            TenantSkill skill = parseSkillContent(name, content);
            if (skill != null) {
                return skill.withSource(SkillSource.BUILTIN).withReadOnly(true);
            }
        } catch (IOException e) {
            logger.debug("Failed to load builtin skill {}: {}", name, e.getMessage());
        }
        
        return null;
    }

    private void loadAllBuiltinSkills(List<TenantSkill> skills, Set<String> seen) {
        String builtinPath = "skills/builtin/";
        
        try {
            // Try to list builtin skills from classpath
            var loader = getClass().getClassLoader();
            var url = loader.getResource(builtinPath);
            
            if (url == null) {
                logger.debug("No builtin skills directory found in classpath");
                return;
            }
            
            // For jar files, we need to scan differently
            if (url.getProtocol().equals("jar")) {
                loadBuiltinSkillsFromJar(loader, builtinPath, skills, seen);
            } else {
                loadBuiltinSkillsFromDirectory(Path.of(url.toURI()), skills, seen);
            }
            
        } catch (Exception e) {
            logger.debug("Failed to load builtin skills: {}", e.getMessage());
        }
    }
    
    private void loadBuiltinSkillsFromJar(ClassLoader loader, String path, 
                                          List<TenantSkill> skills, Set<String> seen) {
        // This is a simplified implementation
        // In production, you'd scan the JAR file entries
        String[] knownBuiltins = {"code", "file", "web_search", "memory"};
        
        for (String name : knownBuiltins) {
            if (!seen.contains(name)) {
                TenantSkill skill = loadBuiltinSkill(name);
                if (skill != null) {
                    skills.add(skill);
                    seen.add(name);
                }
            }
        }
    }
    
    private void loadBuiltinSkillsFromDirectory(Path dir, List<TenantSkill> skills, Set<String> seen) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }
        
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                .forEach(skillDir -> {
                    String name = skillDir.getFileName().toString();
                    if (!seen.contains(name)) {
                        TenantSkill skill = loadBuiltinSkill(name);
                        if (skill != null) {
                            skills.add(skill);
                            seen.add(name);
                        }
                    }
                });
        } catch (IOException e) {
            logger.debug("Failed to list builtin skills: {}", e.getMessage());
        }
    }
    
    private TenantSkill parseSkillContent(String name, String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return new TenantSkill(
                name, "", content, Set.of(), Map.of(),
                1, 0, Instant.now(), Instant.now(),
                null, null, null, null, true, null);
        }
        
        String frontmatter = matcher.group(1);
        String skillContent = matcher.group(2);
        Map<String, String> meta = parseFrontmatter(frontmatter);
        
        return new TenantSkill(
            meta.getOrDefault("name", name),
            meta.getOrDefault("description", ""),
            skillContent,
            parseTags(meta.get("tags")),
            parseMetadata(meta.get("metadata")),
            Integer.parseInt(meta.getOrDefault("version", "1")),
            Integer.parseInt(meta.getOrDefault("usage_count", "0")),
            parseInstant(meta.get("created_at")),
            parseInstant(meta.get("updated_at")),
            meta.get("registry_id"),
            meta.get("version"),
            SkillSource.BUILTIN,
            null,
            true,
            meta.get("signature")
        );
    }

    private List<CodeBlock> extractCodeBlocks(String content) {
        List<CodeBlock> blocks = new ArrayList<>();
        Pattern pattern = Pattern.compile("```(\\w+)?\\n(.*?)```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String lang = matcher.group(1);
            String code = matcher.group(2);
            blocks.add(new CodeBlock(lang != null ? lang : "text", code));
        }

        return blocks;
    }

    private boolean isExecutableLanguage(String lang) {
        if (lang == null) return false;
        Set<String> executable = Set.of(
            "python", "py", "bash", "sh", "shell",
            "javascript", "js", "java", "ruby", "perl"
        );
        return executable.contains(lang.toLowerCase());
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", p);
                    }
                });
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path dst = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy directory", e);
            }
        });
    }

    // ============ Skill 查询 ============

    /**
     * Skill 摘要信息
     */
    public record SkillSummary(
        String name,
        String description,
        String source,
        int version,
        boolean readOnly
    ) {}

    /**
     * 列出所有 Skills（摘要形式）
     */
    public List<SkillSummary> listSkills() {
        return listAvailableSkills().stream()
            .map(skill -> new SkillSummary(
                skill.name(),
                skill.description(),
                skill.source() != null ? skill.source().name().toLowerCase() : "unknown",
                skill.version(),
                skill.readOnly()
            ))
            .collect(Collectors.toList());
    }

    /**
     * 获取指定 Skill
     */
    public TenantSkill getSkill(String name) {
        return loadSkill(name);
    }

    // ============ 记录类 ============

    private record CachedSkill(TenantSkill skill, Instant timestamp) {}
    private record CodeBlock(String language, String content) {}
    
    /**
     * Skill package downloaded from registry
     */
    private record SkillPackage(
        String id,
        String version,
        String description,
        String content,
        Set<String> tags,
        String signature,
        String sourceUrl
    ) {}

    public record SecurityScanResult(boolean isSafe, String reason) {
        public static SecurityScanResult ok() {
            return new SecurityScanResult(true, null);
        }
        public static SecurityScanResult failed(String reason) {
            return new SecurityScanResult(false, reason);
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }
}
