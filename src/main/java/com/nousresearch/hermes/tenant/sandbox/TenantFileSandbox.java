package com.nousresearch.hermes.tenant.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 租户文件沙箱 - 完整实现
 * 
 * 提供租户级别的文件隔离，包括：
 * - 路径遍历防护
 * - 符号链接/硬链接控制
 * - 目录深度限制
 * - 文件类型检查
 * - 存储配额执行
 * - 会话工作区隔离
 */
public class TenantFileSandbox {
    private static final Logger logger = LoggerFactory.getLogger(TenantFileSandbox.class);
    
    // 路径遍历模式
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(".*\\.\\.+.*|.*~.*|^/.*");
    private static final Pattern FORBIDDEN_EXTENSIONS = Pattern.compile(".*\\.(exe|dll|so|dylib|bin|sh|bat|cmd)$", Pattern.CASE_INSENSITIVE);
    
    private final String tenantId;
    private final Path sandboxRoot;
    private final FileSandboxConfig config;
    private final ConcurrentHashMap<String, Path> sessionWorkspaces;
    
    public TenantFileSandbox(String tenantId, Path sandboxRoot, FileSandboxConfig config) {
        this.tenantId = tenantId;
        this.sandboxRoot = sandboxRoot;
        this.config = config;
        this.sessionWorkspaces = new ConcurrentHashMap<>();
        
        try {
            createSandboxStructure();
        } catch (IOException e) {
            logger.error("Failed to create sandbox structure for tenant: {}", tenantId, e);
            throw new SandboxException("Failed to initialize sandbox", e);
        }
    }
    
    public static TenantFileSandbox load(String tenantId, Path sandboxRoot) {
        return new TenantFileSandbox(tenantId, sandboxRoot, FileSandboxConfig.defaults());
    }
    
    /**
     * 获取沙箱根路径
     */
    public Path getSandboxPath() {
        return sandboxRoot;
    }
    
    // ============ 沙箱结构创建 ============
    
    private void createSandboxStructure() throws IOException {
        Files.createDirectories(sandboxRoot);
        
        // 创建子目录
        Files.createDirectories(sandboxRoot.resolve("sessions"));
        Files.createDirectories(sandboxRoot.resolve("uploads"));
        Files.createDirectories(sandboxRoot.resolve("generated"));
        Files.createDirectories(sandboxRoot.resolve("cache"));
        Files.createDirectories(sandboxRoot.resolve("temp"));
        
        // 设置目录权限（Unix 系统）
        if (isPosix()) {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-x---");
            Files.setPosixFilePermissions(sandboxRoot, perms);
        }
        
        logger.debug("Created sandbox structure for tenant: {}", tenantId);
    }
    
    // ============ 路径验证（完整版） ============
    
    public PathValidationResult validatePath(String pathStr, AccessMode mode) {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            return PathValidationResult.rejected("Path is empty", null);
        }
        
        try {
            // 1. 解析原始路径
            Path rawPath = Paths.get(pathStr);
            
            // 2. 检查是否为绝对路径（应该是相对或沙箱内）
            if (rawPath.isAbsolute() && !rawPath.startsWith(sandboxRoot)) {
                // 尝试作为相对路径处理
                rawPath = sandboxRoot.resolve(rawPath.getFileName());
            }
            
            // 3. 规范化路径
            Path path = rawPath.toAbsolutePath().normalize();
            Path realPath = resolveRealPath(path);
            
            // 4. 路径遍历检查
            PathTraversalCheck traversalCheck = checkPathTraversal(pathStr, path);
            if (traversalCheck.detected()) {
                logger.warn("Path traversal detected for tenant {}: {}", tenantId, pathStr);
                return PathValidationResult.rejected("Path traversal detected: " + traversalCheck.reason(), path);
            }
            
            // 5. 符号链接检查
            if (!config.isAllowSymlinks()) {
                if (Files.isSymbolicLink(path)) {
                    return PathValidationResult.rejected("Symbolic links not allowed", path);
                }
            }
            
            // 6. 硬链接检查
            if (!config.isAllowHardlinks()) {
                if (isHardLink(realPath)) {
                    return PathValidationResult.rejected("Hard links not allowed", path);
                }
            }
            
            // 7. 目录深度检查
            if (getPathDepth(realPath) > config.getMaxDepth()) {
                return PathValidationResult.rejected(
                    "Directory depth exceeds limit: " + config.getMaxDepth(), path);
            }
            
            // 8. 沙箱边界检查
            boolean withinSandbox = realPath.startsWith(sandboxRoot);
            boolean inWhitelist = config.getAllowedPaths().stream().anyMatch(realPath::startsWith);
            
            if (!withinSandbox && !inWhitelist) {
                logger.warn("Access outside sandbox for tenant {}: {} (sandbox: {})", 
                    tenantId, realPath, sandboxRoot);
                return PathValidationResult.rejected(
                    "Access denied - path outside sandbox: " + realPath, path);
            }
            
            // 9. 黑名单检查
            boolean inBlacklist = config.getDeniedPaths().stream().anyMatch(realPath::startsWith);
            if (inBlacklist) {
                return PathValidationResult.rejected("Access denied - blacklisted path", path);
            }
            
            // 10. 文件存在性和权限检查
            if (mode == AccessMode.READ) {
                if (!Files.exists(realPath)) {
                    return PathValidationResult.rejected("File not found: " + realPath, path);
                }
                if (!Files.isReadable(realPath)) {
                    return PathValidationResult.rejected("File not readable: " + realPath, path);
                }
            }
            
            if (mode == AccessMode.WRITE) {
                Path parent = realPath.getParent();
                if (parent != null) {
                    if (!Files.exists(parent)) {
                        // 自动创建父目录
                        try {
                            Files.createDirectories(parent);
                        } catch (IOException e) {
                            return PathValidationResult.rejected(
                                "Cannot create parent directory: " + e.getMessage(), path);
                        }
                    }
                    if (!Files.isWritable(parent)) {
                        return PathValidationResult.rejected("Directory not writable: " + parent, path);
                    }
                }
                
                // 检查存储配额
                if (Files.exists(realPath)) {
                    long existingSize = Files.size(realPath);
                    // 这里应该检查新内容大小，但暂时简化处理
                    if (existingSize > config.getMaxFileSize()) {
                        return PathValidationResult.rejected(
                            "File size exceeds limit: " + existingSize + " > " + config.getMaxFileSize(), path);
                    }
                }
            }
            
            // 11. 文件类型检查
            if (Files.isRegularFile(realPath)) {
                if (isForbiddenFileType(realPath)) {
                    return PathValidationResult.rejected("File type not allowed", path);
                }
                
                // 文件大小检查
                long size = Files.size(realPath);
                if (size > config.getMaxFileSize()) {
                    return PathValidationResult.rejected(
                        "File size " + size + " exceeds limit " + config.getMaxFileSize(), path);
                }
            }
            
            logger.debug("Path validation passed for tenant {}: {}", tenantId, realPath);
            return PathValidationResult.allowed(realPath);
            
        } catch (IOException e) {
            logger.error("Path validation error for tenant {}: {}", tenantId, pathStr, e);
            return PathValidationResult.rejected("Path validation error: " + e.getMessage(), null);
        }
    }
    
    // ============ 安全检查方法 ============
    
    private PathTraversalCheck checkPathTraversal(String original, Path normalized) {
        String orig = original.replace('\\', '/');
        
        // 检查 .. 序列
        if (orig.contains("../") || orig.contains("..\\")) {
            return PathTraversalCheck.detected("Contains parent directory reference (..)");
        }
        
        // 检查 ~
        if (orig.startsWith("~") || orig.contains("/~/") || orig.contains("\\~\\")) {
            return PathTraversalCheck.detected("Contains home directory reference (~)");
        }
        
        // 检查绝对路径尝试
        if (Pattern.matches("^/[a-zA-Z]:.*", orig) || orig.matches("^[a-zA-Z]:\\\\.*")) {
            // Windows 绝对路径
            if (!normalized.startsWith(sandboxRoot)) {
                return PathTraversalCheck.detected("Absolute path outside sandbox");
            }
        }
        
        // 检查空字节注入
        if (orig.contains("\0")) {
            return PathTraversalCheck.detected("Contains null byte");
        }
        
        // 检查规范化后的路径是否还在预期范围内
        if (!normalized.toString().contains(sandboxRoot.toString()) && 
            !isInWhitelist(normalized)) {
            // 额外的安全检查：确保规范化没有跳到其他地方
            if (normalized.getNameCount() < sandboxRoot.getNameCount()) {
                return PathTraversalCheck.detected("Path escaped sandbox after normalization");
            }
        }
        
        return PathTraversalCheck.clean();
    }
    
    private Path resolveRealPath(Path path) throws IOException {
        if (Files.exists(path)) {
            try {
                return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                // 如果无法解析真实路径，返回规范化路径
                return path;
            }
        }
        return path;
    }
    
    private boolean isHardLink(Path path) throws IOException {
        if (!Files.exists(path)) return false;
        
        try {
            Object nlink = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return nlink != null && ((Number) nlink).longValue() > 1;
        } catch (UnsupportedOperationException e) {
            // 非 Unix 系统，无法检测硬链接
            return false;
        }
    }
    
    private int getPathDepth(Path path) {
        // 计算相对于沙箱的深度
        if (path.startsWith(sandboxRoot)) {
            return sandboxRoot.relativize(path).getNameCount();
        }
        return path.getNameCount();
    }
    
    private boolean isInWhitelist(Path path) {
        return config.getAllowedPaths().stream().anyMatch(path::startsWith);
    }
    
    private boolean isForbiddenFileType(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        return FORBIDDEN_EXTENSIONS.matcher(filename).matches();
    }
    
    private boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
    
    // ============ 会话工作区 ============
    
    public Path createSessionWorkspace(String sessionId) throws IOException {
        String safeSessionId = sanitizeSessionId(sessionId);
        Path sessionDir = sandboxRoot.resolve("sessions").resolve(safeSessionId);
        
        Files.createDirectories(sessionDir);
        Files.createDirectories(sessionDir.resolve("uploads"));
        Files.createDirectories(sessionDir.resolve("output"));
        Files.createDirectories(sessionDir.resolve("temp"));
        
        // 设置权限
        if (isPosix()) {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-x---");
            Files.setPosixFilePermissions(sessionDir, perms);
        }
        
        sessionWorkspaces.put(sessionId, sessionDir);
        
        logger.debug("Created session workspace for tenant {} session {}: {}", 
            tenantId, sessionId, sessionDir);
        
        return sessionDir;
    }
    
    public Path getSessionWorkspace(String sessionId) {
        return sessionWorkspaces.get(sessionId);
    }
    
    public void cleanupSessionWorkspace(String sessionId) {
        Path sessionDir = sessionWorkspaces.remove(sessionId);
        if (sessionDir != null) {
            try {
                deleteRecursive(sessionDir);
                logger.debug("Cleaned up session workspace for tenant {} session {}", 
                    tenantId, sessionId);
            } catch (IOException e) {
                logger.warn("Failed to cleanup session workspace: {}", sessionDir, e);
            }
        }
    }
    
    public void cleanupAllSessions() {
        for (String sessionId : sessionWorkspaces.keySet()) {
            cleanupSessionWorkspace(sessionId);
        }
    }
    
    // ============ 临时文件管理 ============
    
    public Path createTempFile(String prefix, String suffix) throws IOException {
        Path tempDir = sandboxRoot.resolve("temp");
        Path tempFile = Files.createTempFile(tempDir, prefix, suffix);
        
        // 检查临时文件大小配额
        if (Files.size(tempFile) > config.getMaxFileSize()) {
            Files.delete(tempFile);
            throw new IOException("Temp file exceeds size limit");
        }
        
        return tempFile;
    }
    
    public void cleanupTempFiles() throws IOException {
        Path tempDir = sandboxRoot.resolve("temp");
        if (Files.exists(tempDir)) {
            try (var stream = Files.list(tempDir)) {
                stream.forEach(path -> {
                    try {
                        deleteRecursive(path);
                    } catch (IOException e) {
                        logger.warn("Failed to delete temp file: {}", path, e);
                    }
                });
            }
        }
    }
    
    // ============ 存储统计 ============
    
    public StorageUsage getStorageUsage() throws IOException {
        AtomicLong totalSize = new AtomicLong(0);
        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger dirCount = new AtomicInteger(0);
        
        if (Files.exists(sandboxRoot)) {
            Files.walkFileTree(sandboxRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalSize.addAndGet(attrs.size());
                    fileCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    dirCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        
        return new StorageUsage(
            totalSize.get(), 
            fileCount.get(), 
            dirCount.get(),
            config.getMaxTotalSize()
        );
    }
    
    public boolean checkStorageQuota(long additionalBytes) throws IOException {
        StorageUsage usage = getStorageUsage();
        return (usage.usedBytes() + additionalBytes) <= config.getMaxTotalSize();
    }
    
    // ============ Getter ============
    
    public String getTenantId() { return tenantId; }
    public Path getSandboxRoot() { return sandboxRoot; }
    public FileSandboxConfig getConfig() { return config; }
    
    // ============ 工具方法 ============
    
    private String sanitizeSessionId(String sessionId) {
        // 只允许字母数字和下划线
        return sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
    
    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        deleteRecursive(child);
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", child, e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
    
    // ============ 记录类 ============
    
    public record PathValidationResult(boolean allowed, Path path, String reason) {
        public static PathValidationResult allowed(Path path) {
            return new PathValidationResult(true, path, null);
        }
        public static PathValidationResult rejected(String reason, Path path) {
            return new PathValidationResult(false, path, reason);
        }
        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }
    
    public record StorageUsage(long usedBytes, int fileCount, int dirCount, long maxBytes) {
        public double usagePercent() {
            return maxBytes > 0 ? (double) usedBytes / maxBytes * 100 : 0;
        }
        public boolean isQuotaExceeded() {
            return usedBytes >= maxBytes;
        }
    }
    
    private record PathTraversalCheck(boolean detected, String reason) {
        static PathTraversalCheck detected(String reason) {
            return new PathTraversalCheck(true, reason);
        }
        static PathTraversalCheck clean() {
            return new PathTraversalCheck(false, null);
        }
    }
    
    public enum AccessMode { READ, WRITE, EXECUTE }
    
    // ============ 异常类 ============
    
    public static class SandboxException extends RuntimeException {
        public SandboxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
