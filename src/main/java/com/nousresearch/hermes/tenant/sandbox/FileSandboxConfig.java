package com.nousresearch.hermes.tenant.sandbox;

import java.nio.file.Path;
import java.util.Set;

/**
 * 文件沙箱配置
 */
public class FileSandboxConfig {
    
    // 大小限制
    private long maxFileSize = 100 * 1024 * 1024;      // 100MB
    private long maxTotalSize = 1024 * 1024 * 1024;    // 1GB
    
    // 目录深度限制
    private int maxDepth = 10;
    
    // 链接控制
    private boolean allowSymlinks = false;
    private boolean allowHardlinks = false;
    
    // 白名单路径
    private Set<Path> allowedPaths = Set.of();
    
    // 黑名单路径
    private Set<Path> deniedPaths = Set.of(
        Path.of("/etc"),
        Path.of("/root"),
        Path.of("/var/log"),
        Path.of("/proc"),
        Path.of("/sys")
    );
    
    public static FileSandboxConfig defaults() {
        return new FileSandboxConfig();
    }
    
    // ============ Getters & Setters ============
    
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long size) { this.maxFileSize = size; }
    
    public long getMaxTotalSize() { return maxTotalSize; }
    public void setMaxTotalSize(long size) { this.maxTotalSize = size; }
    
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int depth) { this.maxDepth = depth; }
    
    public boolean isAllowSymlinks() { return allowSymlinks; }
    public void setAllowSymlinks(boolean allow) { this.allowSymlinks = allow; }
    
    public boolean isAllowHardlinks() { return allowHardlinks; }
    public void setAllowHardlinks(boolean allow) { this.allowHardlinks = allow; }
    
    public Set<Path> getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(Set<Path> paths) { this.allowedPaths = paths; }
    
    public Set<Path> getDeniedPaths() { return deniedPaths; }
    public void setDeniedPaths(Set<Path> paths) { this.deniedPaths = paths; }
}
