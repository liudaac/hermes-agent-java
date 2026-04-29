# Hermes Agent Java API 文档

> 版本: 1.0  
> 更新日期: 2026-04-29

## 目录

1. [租户管理 API](#租户管理-api)
2. [沙箱执行 API](#沙箱执行-api)
3. [网络请求 API](#网络请求-api)
4. [内存管理 API](#内存管理-api)
5. [GPU 管理 API](#gpu-管理-api)
6. [指标监控 API](#指标监控-api)
7. [自动扩缩容 API](#自动扩缩容-api)

---

## 租户管理 API

### 创建租户

```java
TenantProvisioningRequest request = TenantProvisioningRequest.builder()
    .tenantId("tenant-123")
    .config(TenantConfig.builder()
        .maxMemoryMB(512)
        .maxStorageMB(1024)
        .maxCpuCores(1.0)
        .build())
    .securityPolicy(TenantSecurityPolicy.builder()
        .allowNetwork(true)
        .allowCodeExecution(true)
        .build())
    .build();

TenantContext context = tenantManager.provisionTenant(request);
```

### 获取租户

```java
Optional<TenantContext> context = tenantManager.getTenant("tenant-123");
context.ifPresent(ctx -> {
    System.out.println("Tenant state: " + ctx.getState());
});
```

### 销毁租户

```java
tenantManager.destroyTenant("tenant-123", true); // true = 保留数据
```

---

## 沙箱执行 API

### 执行命令

```java
ProcessResult result = context.exec(
    List.of("python3", "script.py", "arg1", "arg2"),
    ProcessOptions.builder()
        .timeoutSeconds(30)
        .maxMemoryMB(256)
        .maxCpuCores(0.5)
        .maxPids(10)
        .workDirectory(Path.of("/workspace"))
        .build()
);

if (result.isSuccess()) {
    System.out.println(result.getStdout());
} else {
    System.err.println(result.getError());
}
```

### 容器化执行

```java
ContainerSandbox sandbox = new ContainerSandbox(
    context,
    ContainerSandbox.ContainerRuntime.DOCKER,
    true // GPU enabled
);

ProcessResult result = sandbox.exec(
    List.of("python3", "train.py"),
    ProcessOptions.builder()
        .maxMemoryMB(4096)
        .maxCpuCores(2.0)
        .gpuEnabled(true)
        .build()
);
```

---

## 网络请求 API

### GET 请求

```java
HttpResponse<String> response = context.httpGet("https://api.github.com/users/octocat");
System.out.println(response.body());
```

### POST 请求

```java
String jsonBody = "{\"name\":\"test\"}";
HttpResponse<String> response = context.httpPost(
    "https://api.example.com/data",
    jsonBody,
    "application/json"
);
```

### 配置网络策略

```java
NetworkPolicy policy = NetworkPolicy.builder()
    .allowHost("*.github.com")
    .allowHost("api.openai.com")
    .blockHost("localhost")
    .blockHost("10.*.*.*")
    .maxRequestsPerSecond(10)
    .maxRequestBodySize(10 * 1024 * 1024)   // 10MB
    .maxResponseBodySize(50 * 1024 * 1024)  // 50MB
    .build();

context.setNetworkPolicy(policy);
```

---

## 内存管理 API

### 分配内存

```java
ByteBuffer buffer = context.allocateMemory(100 * 1024 * 1024); // 100MB

// 使用内存...
buffer.put(data);

// 释放内存
context.freeMemory(buffer);
```

### 获取内存统计

```java
TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
System.out.println("Used: " + stats.usedBytes() / (1024 * 1024) + " MB");
System.out.println("Usage: " + (stats.usagePercent() * 100) + "%");
System.out.println("Allocations: " + stats.allocationCount());

if (stats.warning()) {
    System.out.println("⚠️ Memory usage warning!");
}

// 检查内存泄漏
Map<String, Long> leaks = stats.potentialLeaks();
if (!leaks.isEmpty()) {
    System.out.println("⚠️ Potential memory leaks detected: " + leaks.size());
}
```

---

## GPU 管理 API

### 分配 GPU

```java
GpuManager gpuManager = new GpuManager();

Optional<Integer> gpuIndex = gpuManager.allocateGpu("tenant-123");
gpuIndex.ifPresent(index -> {
    System.out.println("Allocated GPU: " + index);
});
```

### 获取 GPU 信息

```java
gpuManager.getGpuInfo(gpuIndex.get()).ifPresent(info -> {
    System.out.println("GPU: " + info.getName());
    System.out.println("Memory: " + info.getUsedMemoryMB() + " / " + 
                       info.getTotalMemoryMB() + " MB");
    System.out.println("Utilization: " + info.getUtilizationPercent() + "%");
    System.out.println("Temperature: " + info.getTemperatureCelsius() + "°C");
});
```

### 释放 GPU

```java
gpuManager.releaseGpu("tenant-123");
```

### 生成 GPU 报告

```java
String report = gpuManager.generateReport();
System.out.println(report);
```

---

## 指标监控 API

### JMX 访问

```bash
# 使用 jconsole 连接
jconsole localhost:9999

# MBean 路径: com.nousresearch.hermes:type=TenantMetrics,tenant=<tenant-id>
```

### 程序化访问

```java
TenantMetrics metrics = context.getMetrics();

// 内存指标
System.out.println("Memory Usage: " + (metrics.getMemoryUsagePercent() * 100) + "%");
System.out.println("Memory Peak: " + metrics.getMemoryPeakBytes() / (1024 * 1024) + " MB");

// 网络指标
System.out.println("Total Requests: " + metrics.getNetworkTotalRequests());
System.out.println("Blocked Requests: " + metrics.getNetworkBlockedRequests());

// 进程指标
System.out.println("Total Processes: " + metrics.getTotalProcessesExecuted());
System.out.println("OOM Killed: " + metrics.getProcessesOomKilled());

// 生成报告
String report = metrics.generateReport();
System.out.println(report);
```

### Prometheus 导出

```java
String prometheusMetrics = metrics.exportPrometheusMetrics();
// 输出 Prometheus 格式的指标
```

---

## 自动扩缩容 API

### 配置扩缩容策略

```java
TenantAutoscaler autoscaler = new TenantAutoscaler(tenantManager);
autoscaler.start();

TenantAutoscaler.ScalingPolicy policy = new TenantAutoscaler.ScalingPolicy(
    0.8,   // scale up threshold (80%)
    0.3,   // scale down threshold (30%)
    5,     // cooldown minutes
    5,     // max scale up steps
    3,     // max scale down steps
    true   // enabled
);

autoscaler.setScalingPolicy("tenant-123", policy);
```

### 停止扩缩容

```java
autoscaler.stop();
```

---

## 错误处理

所有 API 都可能抛出以下异常：

| 异常 | 说明 | 处理建议 |
|------|------|----------|
| `ProcessSandboxException` | 进程执行失败 | 检查命令白名单、资源限制 |
| `NetworkSandboxException` | 网络请求被拒绝 | 检查 URL 白名单、速率限制 |
| `MemoryQuotaExceededException` | 内存配额超限 | 释放内存或增加配额 |
| `QuotaExceededException` | 其他配额超限 | 检查资源使用情况 |

---

## 最佳实践

### 1. 资源限制

始终为租户设置合理的资源限制：

```java
TenantConfig config = TenantConfig.builder()
    .maxMemoryMB(512)        // 内存限制
    .maxStorageMB(1024)      // 存储限制
    .maxCpuCores(1.0)        // CPU 限制
    .maxNetworkRequestsPerSecond(10)  // 网络速率限制
    .build();
```

### 2. 安全策略

根据租户需求配置安全策略：

```java
TenantSecurityPolicy policy = TenantSecurityPolicy.builder()
    .allowNetwork(true)
    .allowCodeExecution(true)
    .allowGpuAccess(false)   // 默认禁用 GPU
    .commandWhitelist(Set.of("python3", "node", "java"))
    .build();
```

### 3. 监控告警

启用监控和告警：

```java
// JMX 监控
TenantMetrics metrics = new TenantMetrics(context);

// Prometheus 导出
// 访问 /metrics 端点获取 Prometheus 格式指标

// Grafana 仪表板
// 导入 monitoring/grafana/tenant-dashboard.json
```

### 4. 优雅关闭

在应用关闭时执行优雅关闭：

```java
GracefulShutdownHandler shutdownHandler = new GracefulShutdownHandler(
    tenantManager,
    Duration.ofSeconds(30)
);
shutdownHandler.registerShutdownHook();
```

---

## 配置示例

### application.yml

```yaml
hermes:
  tenant:
    # 默认资源配额
    default-quota:
      max-memory-mb: 512
      max-storage-mb: 1024
      max-cpu-cores: 1.0
      max-pids: 50
    
    # 沙箱配置
    sandbox:
      timeout-seconds: 30
      command-whitelist:
        - echo
        - cat
        - ls
        - python3
        - node
      command-blacklist:
        - rm
        - sudo
        - chmod
    
    # 网络配置
    network:
      max-requests-per-second: 10
      connect-timeout-seconds: 10
      request-timeout-seconds: 30
      max-request-body-size: 10485760  # 10MB
      max-response-body-size: 52428800 # 50MB
    
    # 持久化配置
    persistence:
      type: postgresql
      url: jdbc:postgresql://localhost:5432/hermes
      username: hermes
      password: ${DB_PASSWORD}
    
    # 自动扩缩容
    autoscaler:
      enabled: true
      evaluation-interval: 60s
      scale-up-threshold: 0.8
      scale-down-threshold: 0.3
      cooldown-minutes: 5

  # 监控配置
  metrics:
    jmx:
      enabled: true
    prometheus:
      enabled: true
      port: 8080
      path: /metrics
```

---

## 部署指南

### Docker Compose

```yaml
version: '3'
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: hermes
      POSTGRES_USER: hermes
      POSTGRES_PASSWORD: secret
    volumes:
      - postgres_data:/var/lib/postgresql/data

  prometheus:
    image: prom/prometheus
    volumes:
      - ./monitoring/prometheus:/etc/prometheus
      - prometheus_data:/prometheus
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana
    volumes:
      - ./monitoring/grafana:/var/lib/grafana/dashboards
      - grafana_data:/var/lib/grafana
    ports:
      - "3000:3000"

  hermes:
    image: hermes-agent:latest
    environment:
      - DB_URL=jdbc:postgresql://postgres:5432/hermes
      - DB_PASSWORD=secret
    depends_on:
      - postgres

volumes:
  postgres_data:
  prometheus_data:
  grafana_data:
```

---

## 更多资源

- [架构图文档](ARCHITECTURE_DIAGRAMS.md)
- [Phase 2 实现文档](PHASE2_IMPLEMENTATION.md)
- [Phase 3 实现文档](PHASE3_IMPLEMENTATION.md)
- [Phase 4 实现文档](PHASE4_IMPLEMENTATION.md)
- [Phase 5 实现文档](PHASE5_IMPLEMENTATION.md)
