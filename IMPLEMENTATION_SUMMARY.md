# Hermes Agent Java - 资源隔离实现汇总

## 已生成的 Java 文件

### 1. 进程沙箱 (Process Sandbox)
位置: `src/main/java/com/nousresearch/hermes/tenant/sandbox/`

| 文件 | 说明 |
|------|------|
| ProcessSandbox.java | 核心实现，限制子进程资源 |
| ProcessSandboxConfig.java | 配置类，白名单/黑名单 |
| ProcessOptions.java | 执行选项，超时/内存限制 |
| ProcessResult.java | 执行结果封装 |
| ProcessSandboxException.java | 异常类 |

**核心功能:**
- 命令白名单/黑名单
- 超时控制（自动 kill）
- 工作目录限制
- 环境变量清理
- 输出大小限制

**使用示例:**
```java
ProcessSandboxConfig config = ProcessSandboxConfig.builder()
    .commandWhitelist(Set.of("git", "mvn", "python3", "node"))
    .workDirectory(Paths.get("/data/tenant1"))
    .build();

ProcessSandbox sandbox = new ProcessSandbox(context, config);
ProcessResult result = sandbox.exec(
    List.of("git", "clone", "https://github.com/user/repo.git"),
    ProcessOptions.builder()
        .timeoutSeconds(60)
        .maxMemoryMB(256)
        .build()
);

if (result.isSuccess()) {
    System.out.println(result.getStdout());
}
```

### 2. 网络沙箱 (Network Sandbox)
位置: `src/main/java/com/nousresearch/hermes/tenant/sandbox/`

| 文件 | 说明 |
|------|------|
| NetworkSandbox.java | 核心实现，控制出站访问 |
| NetworkPolicy.java | 策略配置，白名单/黑名单 |
| NetworkSandboxException.java | 异常类 |

**核心功能:**
- URL 白名单/黑名单（支持通配符）
- 协议限制（HTTP/HTTPS）
- 端口限制
- 速率限制
- 连接超时

**使用示例:**
```java
NetworkPolicy policy = NetworkPolicy.builder()
    .allowHost("*.github.com")
    .allowHost("*.openai.com")
    .allowHost("registry.npmjs.org")
    .blockHost("localhost")
    .blockHost("127.0.0.*")
    .blockHost("10.*.*.*")
    .blockHost("192.168.*.*")
    .maxRequestsPerSecond(10)
    .connectTimeoutSeconds(10)
    .build();

NetworkSandbox network = new NetworkSandbox(policy);
HttpResponse<String> response = network.get("https://api.github.com/users/octocat");
```

### 3. 存储配额 (Storage Quota)
位置: `src/main/java/com/nousresearch/hermes/tenant/quota/`

| 文件 | 说明 |
|------|------|
| StorageQuotaEnforcer.java | 配额强制执行 |
| QuotaExceededException.java | 异常类 |

**核心功能:**
- 写入前配额检查
- 流式写入追踪
- 定期使用量扫描
- 接近配额告警

**使用示例:**
```java
StorageQuotaEnforcer quota = new StorageQuotaEnforcer(
    context, quotaConfig, tenantDirectory
);

// 直接写入（带检查）
quota.writeFile(path, data);

// 流式写入
try (OutputStream out = quota.createOutputStream(path, expectedSize)) {
    // 写入数据
}

// 检查空间
if (quota.canWrite(1024 * 1024)) { // 1MB
    // 有足够空间
}
```

### 4. 线程池隔离 (Thread Pool Isolation)
位置: `src/main/java/com/nousresearch/hermes/tenant/concurrent/`

| 文件 | 说明 |
|------|------|
| TenantThreadPool.java | 租户级线程池 |
| TenantPoolConfig.java | 配置类 |
| TenantPoolRejectedException.java | 异常类 |

**核心功能:**
- 租户级线程池
- 有界队列防止 OOM
- 线程命名便于监控
- 详细统计信息

**使用示例:**
```java
TenantPoolConfig poolConfig = TenantPoolConfig.builder()
    .coreThreads(2)
    .maxThreads(10)
    .queueCapacity(100)
    .keepAliveSeconds(60)
    .build();

TenantThreadPool pool = new TenantThreadPool(tenantId, poolConfig);

// 提交异步任务
Future<String> future = pool.submit(() -> {
    // 执行耗时操作
    return result;
});

// 获取统计
PoolStatistics stats = pool.getStatistics();
System.out.println("Active: " + stats.activeThreads());
System.out.println("Queue: " + stats.queueSize());
```

## 集成到 TenantContext

```java
public class TenantContext {
    private final ProcessSandbox processSandbox;
    private final NetworkSandbox networkSandbox;
    private final StorageQuotaEnforcer storageQuota;
    private final TenantThreadPool threadPool;

    public TenantContext(TenantProvisioningRequest request, ...) {
        // ... 现有代码 ...

        // 初始化进程沙箱
        this.processSandbox = new ProcessSandbox(
            this,
            request.getProcessSandboxConfig()
        );

        // 初始化网络沙箱
        this.networkSandbox = new NetworkSandbox(
            request.getSecurityPolicy().getNetworkPolicy()
        );

        // 初始化存储配额
        this.storageQuota = new StorageQuotaEnforcer(
            this,
            request.getQuota(),
            getSandboxRoot()
        );

        // 初始化线程池
        this.threadPool = new TenantThreadPool(
            request.getTenantId(),
            request.getPoolConfig()
        );
    }

    // 便捷方法
    public ProcessResult exec(List<String> command, ProcessOptions opts) {
        return processSandbox.exec(command, opts);
    }

    public HttpResponse<String> httpGet(String url) {
        return networkSandbox.get(url);
    }

    public void writeFile(Path path, byte[] data) {
        storageQuota.writeFile(path, data);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return threadPool.submit(task);
    }
}
```

## 配置扩展示例

```java
// TenantProvisioningRequest 添加新配置
public class TenantProvisioningRequest {
    private ProcessSandboxConfig processSandboxConfig;
    private TenantPoolConfig poolConfig;

    // Builder 模式添加配置
    public Builder processSandboxConfig(ProcessSandboxConfig config) {
        this.processSandboxConfig = config;
        return this;
    }

    public Builder poolConfig(TenantPoolConfig config) {
        this.poolConfig = config;
        return this;
    }
}

// TenantSecurityPolicy 添加网络策略
public class TenantSecurityPolicy {
    private NetworkPolicy networkPolicy;

    public NetworkPolicy getNetworkPolicy() {
        return networkPolicy != null ? networkPolicy : NetworkPolicy.defaultPolicy();
    }
}
```

## 下一步建议

1. **Phase 1** (立即): 集成 ProcessSandbox 和 NetworkSandbox
2. **Phase 2** (1周内): 集成 StorageQuotaEnforcer
3. **Phase 3** (2周内): 集成 TenantThreadPool
4. **测试**: 编写单元测试验证资源限制
5. **监控**: 添加 JMX 指标暴露资源使用情况
