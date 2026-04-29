# 存储配额强制详细实现方案

## 1. StorageQuotaEnforcer 核心实现

```java
package com.nousresearch.hermes.tenant.quota;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 存储配额强制执行器
 */
public class StorageQuotaEnforcer {
    
    private final TenantContext context;
    private final TenantQuota quota;
    private final Path tenantDirectory;
    private final AtomicLong currentUsage;
    private final ScheduledExecutorService scheduler;
    
    public StorageQuotaEnforcer(TenantContext context, TenantQuota quota, Path tenantDirectory) {
        this.context = context;
        this.quota = quota;
        this.tenantDirectory = tenantDirectory;
        this.currentUsage = new AtomicLong(0);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quota-checker-" + context.getTenantId());
            t.setDaemon(true);
            return t;
        });
        
        // 启动定期扫描
        startPeriodicScan();
    }
    
    /**
     * 写入文件（带配额检查）
     */
    public void writeFile(Path path, byte[] data) throws QuotaExceededException, IOException {
        long additionalSize = data.length;
        
        if (!canWrite(additionalSize)) {
            throw new QuotaExceededException(
                "Storage quota exceeded. Current: " + getCurrentUsage() + 
                " bytes, Limit: " + quota.getMaxStorageBytes() + 
                " bytes, Requested: " + additionalSize + " bytes"
            );
        }
        
        // 写入文件
        Files.write(path, data);
        
        // 更新使用量
        currentUsage.addAndGet(additionalSize);
        
        // 记录审计日志
        context.getAuditLogger().log(StorageEvent.FILE_WRITE, Map.of(
            "path", path.toString(),
            "size", additionalSize
        ));
    }
    
    /**
     * 写入文件（流式，带配额检查）
     */
    public OutputStream createOutputStream(Path path, long expectedSize) 
            throws QuotaExceededException, IOException {
        
        if (!canWrite(expectedSize)) {
            throw new QuotaExceededException("Storage quota would be exceeded");
        }
        
        return new QuotaTrackingOutputStream(path, expectedSize);
    }
    
    /**
     * 检查是否可以写入
     */
    public boolean canWrite(long additionalBytes) {
        long maxBytes = quota.getMaxStorageBytes();
        if (maxBytes <= 0) {
            return true; // 无限制
        }
        
        long current = getCurrentUsage();
        return current + additionalBytes <= maxBytes;
    }
    
    /**
     * 获取当前存储使用量
     */
    public long getCurrentUsage() {
        return currentUsage.get();
    }
    
    /**
     * 删除文件并更新配额
     */
    public void deleteFile(Path path) throws IOException {
        long size = Files.size(path);
        Files.delete(path);
        currentUsage.addAndGet(-size);
        
        context.getAuditLogger().log(StorageEvent.FILE_DELETE, Map.of(
            "path", path.toString(),
            "size", size
        ));
    }
    
    /**
     * 扫描并计算存储使用量
     */
    public long scanAndCalculateUsage() throws IOException {
        final AtomicLong totalSize = new AtomicLong(0);
        
        if (!Files.exists(tenantDirectory)) {
            return 0;
        }
        
        Files.walkFileTree(tenantDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                totalSize.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });
        
        currentUsage.set(totalSize.get());
        return totalSize.get();
    }
    
    /**
     * 启动定期扫描
     */
    private void startPeriodicScan() {
        // 每 5 分钟扫描一次
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long usage = scanAndCalculateUsage();
                
                // 如果接近配额，触发告警
                long max = quota.getMaxStorageBytes();
                if (max > 0) {
                    double ratio = (double) usage / max;
                    if (ratio > 0.9) {
                        context.getAuditLogger().log(StorageEvent.QUOTA_WARNING, Map.of(
                            "usage", usage,
                            "limit", max,
                            "ratio", ratio
                        ));
                    }
                }
            } catch (IOException e) {
                // 记录错误但不中断
            }
        }, 1, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 关闭执行器
     */
    public void shutdown() {
        scheduler.shutdown();
    }
    
    /**
     * 带配额追踪的输出流
     */
    private class QuotaTrackingOutputStream extends OutputStream {
        private final Path path;
        private final OutputStream delegate;
        private final AtomicLong written = new AtomicLong(0);
        private final long expectedSize;
        private boolean closed = false;
        
        QuotaTrackingOutputStream(Path path, long expectedSize) throws IOException {
            this.path = path;
            this.delegate = Files.newOutputStream(path);
            this.expectedSize = expectedSize;
        }
        
        @Override
        public void write(int b) throws IOException {
            checkQuota(1);
            delegate.write(b);
            written.incrementAndGet();
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkQuota(len);
            delegate.write(b, off, len);
            written.addAndGet(len);
        }
        
        private void checkQuota(int len) throws QuotaExceededException {
            long newTotal = getCurrentUsage() + written.get() + len;
            if (quota.getMaxStorageBytes() > 0 && newTotal > quota.getMaxStorageBytes()) {
                throw new QuotaExceededException("Storage quota exceeded during write");
            }
        }
        
        @Override
        public void close() throws IOException {
            if (!closed) {
                delegate.close();
                currentUsage.addAndGet(written.get());
                closed = true;
                
                context.getAuditLogger().log(StorageEvent.FILE_WRITE, Map.of(
                    "path", path.toString(),
                    "size", written.get()
                ));
            }
        }
    }
}

/**
 * 配额超限异常
 */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}

/**
 * 存储事件类型
 */
enum StorageEvent {
    FILE_WRITE,
    FILE_DELETE,
    QUOTA_WARNING
}
```

## 2. 集成到 TenantFileSandbox

```java
public class TenantFileSandbox {
    private final StorageQuotaEnforcer quotaEnforcer;
    
    public TenantFileSandbox(TenantContext context, Path sandboxRoot, TenantQuota quota) {
        // ... 现有代码 ...
        this.quotaEnforcer = new StorageQuotaEnforcer(context, quota, sandboxRoot);
    }
    
    /**
     * 写入文件（带配额检查）
     */
    public void writeFile(String relativePath, byte[] data) 
            throws SandboxException {
        Path target = resolvePath(relativePath);
        
        try {
            // 检查配额
            quotaEnforcer.writeFile(target, data);
        } catch (QuotaExceededException e) {
            throw new SandboxException("Storage quota exceeded", e);
        } catch (IOException e) {
            throw new SandboxException("Failed to write file", e);
        }
    }
    
    /**
     * 获取存储使用量
     */
    public long getStorageUsage() {
        return quotaEnforcer.getCurrentUsage();
    }
    
    /**
     * 检查是否有足够空间
     */
    public boolean hasSpace(long requiredBytes) {
        return quotaEnforcer.canWrite(requiredBytes);
    }
}
```

## 3. 使用示例

```java
// 在租户上下文中使用
TenantContext context = tenantManager.getTenant(tenantId);
TenantFileSandbox sandbox = context.getFileSandbox();

// 检查空间
documentId (requiredBytes > 1024 * 1024) { // 1MB
    throw new InsufficientSpaceException();
}

// 写入文件
try {
    sandbox.writeFile("data/output.json", jsonData.getBytes());
} catch (SandboxException e) {
    if (e.getCause() instanceof QuotaExceededException) {
        // 提示用户清理空间或升级配额
    }
}

// 获取使用量
long usage = sandbox.getStorageUsage();
System.out.println("Storage used: " + usage + " bytes");
```
