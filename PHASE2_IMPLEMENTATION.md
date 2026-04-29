# Phase 2 实现文档 - 高级资源隔离

> 实现时间: 2026-04-29  
> 版本: v1.0

## 概述

Phase 2 实现了真正的底层资源隔离，解决 Phase 1 中沙箱只能"检查"不能"限制"的问题。

## 实现内容

### 1. Linux cgroups v2 集成 (`CgroupProcessSandbox`)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/sandbox/CgroupProcessSandbox.java`

**功能**:
- CPU 限制 (`cpu.max`) - 限制进程 CPU 使用率
- 内存限制 (`memory.max`) - 硬性内存上限，OOM 时自动 kill
- PID 限制 (`pids.max`) - 防止 fork 炸弹
- 资源使用统计 - 内存峰值、CPU 使用量

**使用示例**:
```java
ProcessResult result = context.exec(
    List.of("python3", "script.py"),
    ProcessOptions.builder()
        .maxCpuCores(0.5)      // 最多使用 0.5 核
        .maxMemoryMB(256)      // 最多 256MB 内存
        .maxPids(10)           // 最多 10 个进程
        .timeoutSeconds(30)
        .build()
);

// 获取资源统计
System.out.println(result.getResourceReport());
// Memory Peak: 128.5 MB
// CPU Stats: usage_usec=45000000
// ⚠️ OOM Killed (如果被 OOM kill)
```

**系统要求**:
- Linux 内核 4.5+ (cgroups v2)
- 需要 `cgexec` 命令可用
- 需要写入 `/sys/fs/cgroup` 的权限

### 2. 网络代理模式 (`RestrictedHttpClient`)

**文件**: `src/main/java/com/nousresearch/hermes/tenant/sandbox/RestrictedHttpClient.java`

**功能**:
- 透明拦截所有 HTTP 请求
- URL 白名单/黑名单（支持通配符）
- 速率限制（每秒请求数）
- 请求/响应大小限制
- 完整的审计日志

**使用示例**:
```java
// 配置网络策略
NetworkPolicy policy = NetworkPolicy.builder()
    .allowHost("*.github.com")
    .allowHost("*.openai.com")
    .blockHost("localhost")
    .blockHost("10.*.*.*")
    .maxRequestsPerSecond(10)
    .maxRequestBodySize(10 * 1024 * 1024)   // 10MB
    .maxResponseBodySize(50 * 1024 * 1024)  // 50MB
    .build();

// 发送请求（自动经过沙箱检查）
HttpResponse<String> response = context.httpGet("https://api.github.com/users/octocat");

// 获取网络统计
RestrictedHttpClient.NetworkStats stats = context.getNetworkStats();
System.out.println("Total: " + stats.getTotalRequests());
System.out.println("Blocked: " + stats.getBlockedRequests());
System.out.println("Block Rate: " + stats.getBlockRate() * 100 + "%");
```

**安全特性**:
- 默认阻止内网地址 (`10.*.*.*`, `192.168.*.*`, `localhost`)
- 阻止元数据地址 (`169.254.169.254` - AWS/GCP 元数据)
- 阻止 IPv6 本地地址

### 3. JVM 内存隔离 (`TenantMemoryPool`)

**文件**: 
- `src/main/java/com/nousresearch/hermes/tenant/sandbox/TenantMemoryPool.java`
- `src/main/java/com/nousresearch/hermes/tenant/sandbox/TrackedByteBuffer.java`

**功能**:
- 使用堆外内存（Off-Heap），绕过 JVM GC
- 精确的内存配额限制
- 内存泄漏检测（5分钟以上未释放标记为潜在泄漏）
- 自动清理（使用 Java Cleaner）

**使用示例**:
```java
// 分配内存
ByteBuffer buffer = context.allocateMemory(100 * 1024 * 1024);  // 100MB

// 使用内存...
buffer.put(data);

// 手动释放（或等待自动 GC）
context.freeMemory(buffer);

// 获取内存统计
TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
System.out.println(stats.format());
// Memory Stats for tenant-123:
//   Max: 512 MB
//   Used: 45.2 MB (8.8%)
//   Available: 466.8 MB
//   Allocations: 5
//   Warning: NO
//   Potential Leaks: 0
```

**内存泄漏检测**:
```java
// 分配后不释放，超过 5 分钟会被标记
ByteBuffer leak = context.allocateMemory(1024 * 1024);
// ... 忘记释放 ...

// 5 分钟后
TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
Map<String, Long> leaks = stats.potentialLeaks();
// leaks 包含 allocationId -> 持续时间(毫秒)
```

## 架构变化

### TenantContext 集成

```java
public class TenantContext {
    // Phase 1
    private TenantFileSandbox fileSandbox;
    
    // Phase 2 - 新增
    private CgroupProcessSandbox cgroupSandbox;     // cgroups 进程限制
    private RestrictedHttpClient restrictedHttpClient;  // 网络代理
    private TenantMemoryPool memoryPool;            // 内存隔离
    
    // 便捷方法
    public ProcessResult exec(List<String> command, ProcessOptions options)
    public HttpResponse<String> httpGet(String url)
    public ByteBuffer allocateMemory(int size)
}
```

## 测试建议

### 1. 进程沙箱测试

```java
@Test
public void testCgroupMemoryLimit() {
    // 分配超过限制的内存应该被 OOM kill
    ProcessResult result = context.exec(
        List.of("python3", "-c", "import sys; a = 'x' * (100 * 1024 * 1024)"),
        ProcessOptions.builder()
            .maxMemoryMB(10)  // 只允许 10MB
            .build()
    );
    
    assertTrue(result.isOomKilled());
}

@Test
public void testForkBomb() {
    // Fork 炸弹应该被 PID 限制阻止
    ProcessResult result = context.exec(
        List.of("bash", "-c", ":(){ :|:& };:"),  // fork bomb
        ProcessOptions.builder()
            .maxPids(10)
            .timeoutSeconds(5)
            .build()
    );
    
    assertFalse(result.isSuccess());
}
```

### 2. 网络沙箱测试

```java
@Test
public void testBlockInternalIP() {
    assertThrows(NetworkSandboxException.class, () -> {
        context.httpGet("http://localhost:8080/");
    });
    
    assertThrows(NetworkSandboxException.class, () -> {
        context.httpGet("http://10.0.0.1/");
    });
}

@Test
public void testRateLimit() {
    // 快速发送请求应该触发速率限制
    for (int i = 0; i < 15; i++) {
        try {
            context.httpGet("https://api.github.com/");
        } catch (NetworkSandboxException e) {
            assertTrue(e.getMessage().contains("Rate limit"));
            return;
        }
    }
    fail("Should have hit rate limit");
}
```

### 3. 内存池测试

```java
@Test
public void testMemoryQuota() {
    // 超出配额应该抛出异常
    assertThrows(MemoryQuotaExceededException.class, () -> {
        context.allocateMemory(1024 * 1024 * 1024);  // 1GB，超出默认 256MB
    });
}

@Test
public void testMemoryLeakDetection() throws InterruptedException {
    ByteBuffer buffer = context.allocateMemory(1024 * 1024);
    // 不要释放
    
    Thread.sleep(6 * 60 * 1000);  // 等待 6 分钟
    
    TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
    assertEquals(1, stats.potentialLeaks().size());
}
```

## 性能影响

| 功能 | 开销 | 说明 |
|------|------|------|
| cgroups | ~5-10ms | 创建/销毁 cgroup 的开销 |
| 网络代理 | ~1-2ms | URL 匹配和速率检查 |
| 内存池 | ~0.5ms | 分配追踪的开销 |

## 降级策略

如果系统不支持 cgroups v2，会自动降级到普通 ProcessSandbox：

```java
if (CgroupProcessSandbox.isCgroupV2Available()) {
    this.cgroupSandbox = new CgroupProcessSandbox(this, config);
} else {
    this.processSandbox = new ProcessSandbox(this, config);  // 降级
    logger.warn("cgroups v2 not available, using basic process sandbox");
}
```

## 下一步（Phase 3）

1. **JMX 指标暴露** - 通过 MBean 暴露资源使用指标
2. **Prometheus 集成** - 导出指标供 Prometheus 采集
3. **Grafana 仪表板** - 可视化资源使用情况
4. **实时监控告警** - 资源使用超过阈值时发送告警

## 相关文件

- `CgroupProcessSandbox.java` - cgroups 集成
- `RestrictedHttpClient.java` - 网络代理
- `NetworkPolicy.java` - 网络策略配置
- `TenantMemoryPool.java` - 内存池
- `TrackedByteBuffer.java` - 追踪包装器
- `MemoryQuotaExceededException.java` - 内存配额异常
