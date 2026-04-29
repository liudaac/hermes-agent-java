# Hermes Agent Java - 资源隔离架构分析报告

## 一、现有资源隔离能力

### 1. 租户级别隔离 (Tenant Isolation)
- ✅ **独立配置存储**: 每个租户有独立的 `config.json` 文件
- ✅ **文件沙箱**: `TenantFileSandbox` 提供租户级别的文件访问控制
- ✅ **配额管理**: `TenantQuota` 支持限制内存、请求数、存储等
- ✅ **安全策略**: `TenantSecurityPolicy` 支持命令/网络/文件访问控制
- ✅ **审计日志**: `TenantAuditLogger` 记录租户操作

### 2. 会话级别隔离 (Session Isolation)
- ✅ **独立会话管理**: `TenantSessionManager` 管理每个租户的多个会话
- ✅ **会话超时**: 支持空闲超时和绝对超时
- ✅ **会话配额**: 限制并发会话数

### 3. API 级别保护
- ✅ **租户认证过滤器**: `TenantAuthFilter` 验证租户身份
- ✅ **API 限流**: 支持按租户限流

## 二、需要完善的资源隔离能力

### 1. 进程级别隔离 (Critical)
```java
// 建议添加: ProcessSandbox.java
public class ProcessSandbox {
    // 使用 ProcessBuilder 限制子进程资源
    // - CPU 时间限制
    // - 内存限制
    // - 文件描述符限制
    // - 网络访问限制
}
```

**当前问题**: 
- 代码执行可能直接调用 `Runtime.exec()` 或 `ProcessBuilder`
- 缺乏对子进程的资源限制
- 没有进程命名空间隔离

**改进建议**:
- 封装 ProcessBuilder，添加资源限制
- 使用 cgroups (Linux) 限制子进程资源
- 考虑使用容器化隔离（Docker/Podman）

### 2. 网络隔离 (High Priority)
```java
// 建议添加: NetworkSandbox.java
public class NetworkSandbox {
    // - 出站连接白名单/黑名单
    // - 速率限制
    // - DNS 限制
    // - 协议限制 (只允许 HTTP/HTTPS)
}
```

**当前问题**:
- `TenantSecurityPolicy.allowNetwork` 只是布尔开关
- 没有细粒度的网络访问控制
- 无法限制特定域名/IP

**改进建议**:
- 添加 URL 白名单/黑名单
- 实现 HTTP 代理拦截
- 添加网络请求速率限制

### 3. JVM 资源隔离 (Medium Priority)
```java
// 建议添加: JvmResourceLimiter.java
public class JvmResourceLimiter {
    // - 堆内存隔离（使用 ByteBuffer 或堆外内存）
    // - GC 策略隔离
    // - 线程池隔离
}
```

**当前问题**:
- 所有租户共享同一个 JVM
- 一个租户的内存泄漏可能影响其他租户
- 没有线程数限制

**改进建议**:
- 使用独立的 ClassLoader（部分实现）
- 限制每个租户的线程数
- 监控并限制堆内存使用

### 4. 数据库/存储隔离 (Medium Priority)
```java
// 建议添加: StorageQuotaEnforcer.java
public class StorageQuotaEnforcer {
    // - 强制存储配额检查
    // - 自动清理过期数据
    // - 存储使用监控
}
```

**当前问题**:
- `TenantQuota.maxStorageBytes` 定义了但没有强制检查
- 缺乏存储使用实时监控

**改进建议**:
- 在文件写入时检查配额
- 定期扫描并强制执行配额
- 添加存储使用告警

### 5. 安全沙箱增强 (High Priority)
```java
// 建议添加: SecuritySandbox.java
public class SecuritySandbox {
    // - 禁止反射访问敏感类
    // - 限制 System.exit()
    // - 限制类加载
    // - 敏感操作审批
}
```

**当前问题**:
- `ApprovalSystem` 存在但可能未完全集成
- 缺乏对反射调用的限制
- 没有代码签名验证

**改进建议**:
- 使用 SecurityManager 或 Java Agent 限制危险操作
- 实现代码签名验证
- 集成 ApprovalSystem 到所有敏感操作

### 6. 多租户资源调度 (Low Priority)
```java
// 建议添加: TenantResourceScheduler.java
public class TenantResourceScheduler {
    // - 公平调度算法
    // - 优先级管理
    // - 资源预留
}
```

## 三、实现优先级建议

### Phase 1: 基础安全 (立即实现)
1. ✅ 文件沙箱 - 已实现
2. ⚠️ 进程资源限制 - 需要封装 ProcessBuilder
3. ⚠️ 网络访问控制 - 需要实现 URL 过滤

### Phase 2: 资源管控 (1-2周)
1. 存储配额强制执行
2. 线程数限制
3. 内存使用监控

### Phase 3: 高级隔离 (1个月)
1. 容器化隔离选项
2. 多租户资源调度
3. 安全沙箱增强

## 四、关键代码检查点

### 4.1 需要添加资源限制的代码位置
```
TenantContext.executeInSandbox()
├── 文件操作 → 使用 TenantFileSandbox ✅
├── 网络请求 → 需要添加 NetworkSandbox ❌
├── 进程执行 → 需要添加 ProcessSandbox ❌
└── 内存分配 → 需要监控和限制 ❌
```

### 4.2 建议的架构调整
```
TenantContext
├── FileSandbox (文件隔离) ✅
├── NetworkSandbox (网络隔离) ⚠️
├── ProcessSandbox (进程隔离) ⚠️
├── MemoryPool (内存隔离) ⚠️
└── SecurityPolicy (安全策略) ✅
```

## 五、参考实现

### 5.1 进程资源限制 (Linux)
```java
public class ResourceLimitedProcess {
    public Process start(List<String> command, ResourceLimits limits) {
        ProcessBuilder pb = new ProcessBuilder(command);
        
        // Linux cgroups 限制
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            // 使用 cgexec 或写入 cgroup 文件
            pb.command().add(0, "cgexec");
            pb.command().add(1, "-g");
            pb.command().add(2, "memory,cpu:/tenant-" + tenantId);
        }
        
        return pb.start();
    }
}
```

### 5.2 网络代理限制
```java
public class RestrictedHttpClient {
    public HttpResponse execute(HttpRequest request, NetworkPolicy policy) {
        // 检查 URL 是否在白名单中
        if (!policy.isAllowed(request.uri())) {
            throw new SecurityException("URL not allowed: " + request.uri());
        }
        
        // 检查速率限制
        if (!rateLimiter.tryAcquire(tenantId)) {
            throw new QuotaExceededException("Network rate limit exceeded");
        }
        
        return httpClient.send(request);
    }
}
```

## 六、总结

当前 hermes-agent-java 已经实现了基础的租户隔离和文件沙箱，但在以下方面需要加强：

1. **进程隔离**: 子进程缺乏资源限制
2. **网络隔离**: 只有开关，没有细粒度控制
3. **存储配额**: 定义但未强制执行
4. **JVM 隔离**: 共享 JVM 存在资源竞争风险

建议按优先级逐步实现，确保服务端部署时的安全性和稳定性。
