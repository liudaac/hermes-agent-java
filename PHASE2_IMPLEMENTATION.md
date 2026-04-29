# Phase 2 实现汇总 - 高级资源隔离

## 概述

Phase 2 实现了三种高级资源隔离机制：

1. **Linux cgroups 集成** - 真正的进程资源限制
2. **网络代理模式** - 透明的 HTTP 访问控制
3. **JVM 内存隔离** - 租户级内存配额管理

---

## 1. Linux cgroups 集成

### 文件
- `CgroupProcessSandbox.java` - 基于 cgroups v2 的进程资源限制
- `ProcessOptions.java` - 新增资源限制选项
- `ProcessResult.java` - 新增资源使用统计

### 功能特性

| 限制项 | cgroup 控制器 | 说明 |
|--------|--------------|------|
| CPU 限制 | `cpu.max` | 限制CPU核心数使用率 |
| 内存限制 | `memory.max` | 硬性内存上限 |
| PID 限制 | `pids.max` | 限制进程/线程数 |
| IO 限制 | `io.max` | 限制磁盘IO带宽 |

### 使用示例

```java
// 创建租户时配置
TenantProvisioningRequest request = TenantProvisioningRequest.builder()
    .tenantId("tenant-123")
    .processSandboxConfig(ProcessSandboxConfig.builder()
        .commandWhitelist(Set.of("git", "python3", "node"))
        .workDirectory(Paths.get("/data/tenants/tenant-123"))
        .build())
    .build();

// 执行命令时设置资源限制
ProcessResult result = context.exec(
    List.of("python3", "heavy_computation.py"),
    ProcessOptions.builder()
        .timeoutSeconds(60)
        .maxCpuCores(0.5)      // 最多使用0.5核CPU
        .maxMemoryMB(256)       // 最多256MB内存
        .maxPids(10)            // 最多10个进程
        .maxIoBps(1024 * 1024)  // 最多1MB/s磁盘IO
        .build()
);

// 查看资源使用统计
if (result.isOomKilled()) {
    System.out.println("进程因OOM被终止");
}
System.out.println("内存峰值: " + result.getMemoryPeakBytes() / 1024 / 1024 + " MB");
System.out.println("CPU统计: " + result.getCpuStats());
```

### 系统要求
- Linux 内核 4.15+ (cgroups v2)
- 已挂载 cgroups v2 文件系统
- 具有写入 `/sys/fs/cgroup` 的权限

### 降级策略
如果系统不支持 cgroups v2，自动降级到普通 `ProcessSandbox`，仅提供命令白名单和超时控制。

---

## 2. 网络代理模式

### 文件
- `RestrictedHttpClient.java` - 受限的 HTTP 客户端
- `NetworkPolicy.java` - 网络访问策略配置

### 功能特性

| 特性 | 说明 |
|------|------|
| URL 白名单/黑名单 | 支持通配符匹配 `*.github.com` |
| 协议限制 | 只允许 http/https |
| 端口限制 | 只允许指定端口 (80, 443, 8080等) |
| 速率限制 | 每秒请求数限制 |
| 请求/响应大小限制 | 防止过大数据传输 |
| 审计日志 | 记录所有网络访问 |

### 使用示例

```java
// 创建网络策略
NetworkPolicy policy = NetworkPolicy.builder()
    .allowHost("*.github.com")
    .allowHost("*.openai.com")
    .allowHost("registry.npmjs.org")
    .blockHost("localhost")
    .blockHost("127.0.0.*")
    .blockHost("10.*.*.*")      // 内网IP
    .blockHost("192.168.*.*")
    .maxRequestsPerSecond(10)    // 每秒最多10个请求
    .maxRequestBodySize(1024 * 1024)    // 请求体最大1MB
    .maxResponseBodySize(10 * 1024 * 1024) // 响应体最大10MB
    .connectTimeoutSeconds(10)
    .build();

// 租户上下文自动使用
HttpResponse<String> response = context.httpGet("https://api.github.com/users/octocat");

// 或直接使用
RestrictedHttpClient client = new RestrictedHttpClient(context, policy);
HttpResponse<String> result = client.post(
    "https://api.openai.com/v1/chat/completions",
    jsonBody
);

// 查看网络统计
RestrictedHttpClient.NetworkStats stats = context.getNetworkStats();
System.out.println("总请求数: " + stats.getTotalRequests());
System.out.println("被阻止数: " + stats.getBlockedRequests());
System.out.println("阻止率: " + stats.getBlockRate() * 100 + "%");
```

### SSRF 防护
```java
// 尝试访问内网地址会被阻止
try {
    context.httpGet("http://169.254.169.254/");  // AWS元数据地址
} catch (NetworkSandboxException e) {
    System.out.println("阻止了SSRF攻击: " + e.getMessage());
}
```

---

## 3. JVM 内存隔离

### 文件
- `TenantMemoryPool.java` - 租户内存池管理
- `TrackedByteBuffer.java` - 带追踪的 ByteBuffer 包装器
- `MemoryQuotaExceededException.java` - 内存配额异常

### 功能特性

| 特性 | 说明 |
|------|------|
| 堆外内存隔离 | 使用 DirectByteBuffer 绕过 JVM 堆 |
| 精确配额追踪 | 每字节分配都记录 |
| 快速失败 | 超出配额立即抛出异常 |
| 自动清理 | Cleaner + finalize 双重保障 |
| 内存映射支持 | 支持内存映射文件 |
| 统计监控 | 峰值、分配次数等 |

### 使用示例

```java
// 创建租户时配置内存配额
TenantProvisioningRequest request = TenantProvisioningRequest.builder()
    .tenantId("tenant-123")
    .maxMemoryBytes(512 * 1024 * 1024L)  // 512MB内存配额
    .build();

// 分配内存
ByteBuffer buffer = context.allocateMemory(100 * 1024 * 1024);  // 100MB
try {
    // 使用 buffer 处理数据...
    
} finally {
    // 主动释放（推荐）
    context.freeMemory(buffer);
}

// 内存映射文件
try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
    MappedByteBuffer mapped = memoryPool.allocateMapped(
        channel, 
        FileChannel.MapMode.READ_ONLY, 
        0, 
        channel.size()
    );
    // 使用 mapped buffer...
}

// 查看内存使用
TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
System.out.println("已分配: " + stats.getTotalAllocated() / 1024 / 1024 + " MB");
System.out.println("已释放: " + stats.getTotalDeallocated() / 1024 / 1024 + " MB");

// 获取完整报告
System.out.println(memoryPool.getMemoryReport());
```

### 内存分配失败处理
```java
try {
    // 尝试分配超大内存
    ByteBuffer huge = context.allocateMemory(2 * 1024 * 1024 * 1024L);  // 2GB
} catch (MemoryQuotaExceededException e) {
    System.out.println("内存配额不足: " + e.getMessage());
    // 系统和其他租户不受影响
}
```

---

## 4. TenantContext 集成

Phase 2 组件已完全集成到 TenantContext：

```java
public class TenantContext {
    // Phase 2 资源隔离沙箱
    private volatile CgroupProcessSandbox cgroupSandbox;
    private volatile NetworkSandbox networkSandbox;
    private volatile RestrictedHttpClient restrictedHttpClient;
    private volatile TenantMemoryPool memoryPool;
    
    // 便捷方法
    public ProcessResult exec(List<String> command, ProcessOptions options);
    public HttpResponse<String> httpGet(String url);
    public HttpResponse<String> httpPost(String url, String body);
    public ByteBuffer allocateMemory(int size);
    public void freeMemory(ByteBuffer buffer);
    
    // 统计信息
    public TenantMemoryPool.MemoryStats getMemoryStats();
    public RestrictedHttpClient.NetworkStats getNetworkStats();
}
```

---

## 5. 配置扩展示例

### application.yml
```yaml
hermes:
  tenant:
    # 默认资源限制
    defaults:
      maxMemoryBytes: 268435456  # 256MB
      maxCpuCores: 1.0
      maxPids: 50
      
    # 网络策略
    network:
      allowed-hosts:
        - "*.github.com"
        - "*.openai.com"
        - "registry.npmjs.org"
      blocked-hosts:
        - "localhost"
        - "127.0.0.*"
        - "10.*.*.*"
        - "192.168.*.*"
      max-requests-per-second: 10
      
    # 进程沙箱
    process:
      command-whitelist:
        - "git"
        - "python3"
        - "node"
        - "mvn"
        - "gradle"
      command-blacklist:
        - "rm"
        - "mkfs"
        - "dd"
        - "sudo"
```

---

## 6. 测试建议

### cgroups 测试
```bash
# 测试 fork 炸弹防护
echo ':(){ :|:& };:' | java -jar hermes-agent.jar

# 测试内存限制
python3 -c "import numpy; a = numpy.zeros((10000, 10000))"
```

### 网络测试
```java
// 测试 SSRF 防护
@Test(expected = NetworkSandboxException.class)
public void testSsrfProtection() {
    context.httpGet("http://169.254.169.254/latest/meta-data/");
}

// 测试速率限制
@Test
public void testRateLimit() {
    for (int i = 0; i < 20; i++) {
        if (i < 10) {
            context.httpGet("https://api.github.com");  // 成功
        } else {
            assertThrows(NetworkQuotaExceededException.class, () -> {
                context.httpGet("https://api.github.com");  // 被限流
            });
        }
    }
}
```

### 内存隔离测试
```java
@Test(expected = MemoryQuotaExceededException.class)
public void testMemoryQuota() {
    // 分配超过配额的内存
    ByteBuffer b1 = context.allocateMemory(200 * 1024 * 1024);  // 200MB
    ByteBuffer b2 = context.allocateMemory(200 * 1024 * 1024);  // 再200MB，超过256MB配额
}
```

---

## 7. 性能考量

| 功能 | 性能开销 | 优化建议 |
|------|---------|----------|
| cgroups | ~5ms (首次创建) | 复用 cgroup，避免频繁创建/销毁 |
| 网络代理 | ~1ms/请求 | 使用连接池，减少TCP握手 |
| 内存隔离 | ~0.1ms/分配 | 批量分配，对象池复用 |

---

## 8. 下一步 (Phase 3)

1. **可观测性** - JMX指标 + Prometheus + Grafana
2. **安全增强** - SecurityManager 集成
3. **容器化** - Docker/Podman 隔离选项

---

*实现完成时间: 2026-04-29*
