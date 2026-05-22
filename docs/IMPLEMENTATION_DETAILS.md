# 资源隔离实现细节详解

## 一、ProcessSandbox（进程沙箱）实现细节

### 1.1 核心设计思路

```
┌─────────────────────────────────────────────────────────┐
│                    ProcessSandbox                        │
├─────────────────────────────────────────────────────────┤
│  1. 命令白名单检查                                       │
│     └── 防止执行 rm, mkfs 等危险命令                      │
│                                                          │
│  2. 工作目录限制                                         │
│     └── 限制在租户目录 /data/tenants/{tenantId}/         │
│                                                          │
│  3. 环境变量清理                                         │
│     └── 移除 API_KEY, SECRET 等敏感信息                   │
│                                                          │
│  4. 超时控制                                             │
│     └── Linux: timeout 命令                              │
│     └── Windows: 等待 + destroyForcibly()               │
│                                                          │
│  5. 资源监控（可选）                                     │
│     └── Linux cgroups: CPU/内存/PID 限制                │
└─────────────────────────────────────────────────────────┘
```

### 1.2 关键实现代码解析

#### 命令白名单检查
```java
private boolean isCommandAllowed(String command) {
    // 提取命令名称（去除路径）
    // /usr/bin/git → git
    // C:\Windows\System32\cmd.exe → cmd.exe
    
    // 先检查黑名单
    if (blacklist.contains(cmdName)) {
        return false;  // 直接拒绝
    }
    
    // 再检查白名单（如果配置了）
    if (!whitelist.isEmpty()) {
        return whitelist.contains(cmdName);  // 必须白名单匹配
    }
    
    return true;  // 默认允许
}
```

**为什么这样设计？**
- 黑名单优先：确保危险命令（rm, dd, mkfs）绝对禁止
- 白名单可选：如果配置了白名单，只有白名单内的命令可以执行
- 安全默认值：默认允许，但建议生产环境使用白名单模式

#### 超时控制实现
```java
// Linux: 使用 timeout 命令包装
if (isLinux() && timeoutSeconds > 0) {
    // timeout -s SIGTERM 30 <original_command>
    wrappedCommand.add(0, "timeout");
    wrappedCommand.add(1, "-s");
    wrappedCommand.add(2, "SIGTERM");
    wrappedCommand.add(3, String.valueOf(timeoutSeconds));
}
```

**为什么使用 timeout 命令？**
- 内核级超时，比 Java 的 waitFor(timeout) 更可靠
- 自动发送 SIGTERM，子进程无法忽略
- 无需 root 权限即可使用

#### 环境变量清理
```java
private void sanitizeEnvironment(Map<String, String> env) {
    // 移除敏感变量
    env.remove("HERMES_API_KEY");
    env.remove("AWS_ACCESS_KEY_ID");
    env.remove("OPENAI_API_KEY");
    env.remove("PRIVATE_KEY");
    
    // 设置安全的 PATH
    env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
}
```

**为什么要清理环境变量？**
- 防止子进程获取父进程的环境变量
- 保护 API 密钥不被泄露
- 限制可执行命令的路径

### 1.3 使用示例

```java
// 基础使用
ProcessResult result = context.exec(
    List.of("git", "clone", "https://github.com/user/repo.git"),
    ProcessOptions.builder()
        .timeoutSeconds(60)
        .maxMemoryMB(256)
        .build()
);

// 检查结果
if (result.isSuccess()) {
    System.out.println("Output: " + result.getStdout());
} else if (result.isTimedOut()) {
    System.err.println("Command timed out!");
} else {
    System.err.println("Exit code: " + result.getExitCode());
    System.err.println("Error: " + result.getStderr());
}
```

---

## 二、NetworkSandbox（网络沙箱）实现细节

### 2.1 核心设计思路

```
┌─────────────────────────────────────────────────────────┐
│                    NetworkSandbox                        │
├─────────────────────────────────────────────────────────┤
│  1. 协议检查                                             │
│     └── 只允许 http/https（可配置）                       │
│                                                          │
│  2. 主机白名单/黑名单                                    │
│     └── *.github.com ✓                                  │
│     └── *.openai.com ✓                                  │
│     └── localhost ✗                                     │
│     └── 10.*.*.* ✗                                      │
│                                                          │
│  3. 端口限制                                             │
│     └── 只允许 80, 443, 8080 等                         │
│                                                          │
│  4. 速率限制                                             │
│     └── 每秒最多 N 个请求                                │
│                                                          │
│  5. 请求体大小限制                                       │
│     └── 防止上传过大文件                                 │
└─────────────────────────────────────────────────────────┘
```

### 2.2 关键实现代码解析

#### URL 匹配算法
```java
private boolean isHostAllowed(String host) {
    // 先检查黑名单（优先级更高）
    for (Pattern pattern : blacklist) {
        if (pattern.matcher(host).matches()) {
            return false;
        }
    }
    
    // 再检查白名单
    if (!whitelist.isEmpty()) {
        for (Pattern pattern : whitelist) {
            if (pattern.matcher(host).matches()) {
                return true;
            }
        }
        return false;  // 白名单非空但未匹配
    }
    
    return true;  // 无白名单时默认允许
}
```

**匹配示例：**
- 白名单: `*.github.com`, `api.openai.com`
- 黑名单: `localhost`, `127.0.0.*`, `10.*.*.*`

| URL | 结果 | 原因 |
|-----|------|------|
| https://api.github.com/users | ✅ | 匹配 *.github.com |
| https://api.openai.com/v1 | ✅ | 匹配 api.openai.com |
| http://localhost:8080 | ❌ | 匹配黑名单 localhost |
| http://10.0.0.1 | ❌ | 匹配黑名单 10.*.*.* |

#### 速率限制实现
```java
class RateLimiter {
    private final int maxRequestsPerSecond;
    private final ConcurrentHashMap<Long, Integer> requestCounts = new ConcurrentHashMap<>();
    
    synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis() / 1000;
        
        // 清理旧数据（1分钟前的）
        requestCounts.keySet().removeIf(t -> t < now - 60);
        
        // 检查当前秒
        int current = requestCounts.getOrDefault(now, 0);
        if (current >= maxRequestsPerSecond) {
            return false;  // 超过限制
        }
        
        requestCounts.put(now, current + 1);
        return true;
    }
}
```

**为什么是每秒限制？**
- 简单易实现
- 满足大多数场景
- 可以考虑改用令牌桶算法实现更平滑的限流

### 2.3 使用示例

```java
// 配置网络策略
NetworkPolicy policy = NetworkPolicy.builder()
    .allowHost("*.github.com")
    .allowHost("*.openai.com")
    .allowHost("registry.npmjs.org")
    .blockHost("localhost")
    .blockHost("127.0.0.*")
    .blockHost("10.*.*.*")
    .blockHost("192.168.*.*")
    .maxRequestsPerSecond(10)
    .maxRequestBodySize(1024 * 1024)  // 1MB
    .connectTimeoutSeconds(10)
    .followRedirects(false)
    .build();

// 创建沙箱
NetworkSandbox network = new NetworkSandbox(policy);

// 发送请求
try {
    HttpResponse<String> response = network.get("https://api.github.com/users/octocat");
    System.out.println(response.body());
} catch (NetworkSandboxException e) {
    System.err.println("Access denied: " + e.getMessage());
}
```

---

## 三、集成到 TenantContext

### 3.1 初始化流程

```java
public TenantContext(TenantProvisioningRequest request, ...) {
    // 1. 基础初始化
    this.tenantId = request.getTenantId();
    this.config = new TenantConfig(configFile, data);
    
    // 2. 初始化文件沙箱
    this.fileSandbox = new TenantFileSandbox(this, sandboxRoot, 
        config.getFileSandboxConfig());
    
    // 3. 初始化进程沙箱（新增）
    this.processSandbox = new ProcessSandbox(
        this,
        request.getProcessSandboxConfig() != null 
            ? request.getProcessSandboxConfig() 
            : ProcessSandboxConfig.defaultConfig()
    );
    
    // 4. 初始化网络沙箱（新增）
    this.networkSandbox = new NetworkSandbox(
        config.getSecurityPolicy().getNetworkPolicy()
    );
    
    // 5. 其他初始化...
}
```

### 3.2 便捷方法设计

```java
// 在 TenantContext 中添加便捷方法

/**
 * 执行系统命令（带沙箱限制）
 */
public ProcessResult exec(List<String> command, ProcessOptions options) {
    return processSandbox.exec(command, options);
}

/**
 * HTTP GET 请求（带网络限制）
 */
public HttpResponse<String> httpGet(String url) {
    return networkSandbox.get(url);
}

/**
 * HTTP POST 请求（带网络限制）
 */
public HttpResponse<String> httpPost(String url, String body) {
    return networkSandbox.post(url, body);
}
```

**为什么设计便捷方法？**
- 简化调用：context.exec() 比 context.getProcessSandbox().exec() 更简洁
- 统一入口：所有资源操作都通过 TenantContext
- 便于监控：可以在便捷方法中添加审计日志

### 3.3 完整使用示例

```java
// 获取租户上下文
TenantContext context = tenantManager.getTenant("tenant-123");

// 1. 执行命令
ProcessResult result = context.exec(
    List.of("git", "clone", "https://github.com/user/repo.git"),
    ProcessOptions.builder()
        .timeoutSeconds(60)
        .build()
);

// 2. 发送网络请求
HttpResponse<String> response = context.httpGet(
    "https://api.github.com/users/octocat"
);

// 3. 文件操作（已有功能）
context.getFileSandbox().writeFile("data/output.json", jsonData);
```

---

## 四、配置扩展

### 4.1 TenantProvisioningRequest 扩展

```java
public class TenantProvisioningRequest {
    // 新增配置
    private ProcessSandboxConfig processSandboxConfig;
    private NetworkPolicy networkPolicy;
    
    public static class Builder {
        // 新增 builder 方法
        public Builder processSandboxConfig(ProcessSandboxConfig config) {
            request.processSandboxConfig = config;
            return this;
        }
        
        public Builder networkPolicy(NetworkPolicy policy) {
            request.networkPolicy = policy;
            return this;
        }
    }
    
    // Getters
    public ProcessSandboxConfig getProcessSandboxConfig() {
        return processSandboxConfig;
    }
    
    public NetworkPolicy getNetworkPolicy() {
        return networkPolicy;
    }
}
```

### 4.2 配置示例（JSON）

```json
{
  "tenantId": "tenant-123",
  "name": "Test Tenant",
  "processSandboxConfig": {
    "commandWhitelist": ["git", "mvn", "python3", "node"],
    "commandBlacklist": ["rm", "mkfs", "dd"],
    "workDirectory": "/data/tenants/tenant-123"
  },
  "networkPolicy": {
    "allowedProtocols": ["http", "https"],
    "allowedPorts": [80, 443, 8080],
    "hostWhitelist": ["*.github.com", "*.openai.com"],
    "hostBlacklist": ["localhost", "127.0.0.*", "10.*.*.*"],
    "maxRequestsPerSecond": 10,
    "connectTimeoutSeconds": 10
  }
}
```

---

## 五、安全考虑

### 5.1 威胁模型

| 威胁 | 防护措施 |
|------|----------|
| 命令注入 | 白名单检查 + 参数验证 |
| 目录遍历 | 工作目录限制 + 路径解析 |
| 敏感信息泄露 | 环境变量清理 |
| 资源耗尽 | 超时 + 内存限制 |
| SSRF 攻击 | URL 白名单 + 黑名单 |
| DDoS | 速率限制 |

### 5.2 生产环境建议

1. **使用白名单模式**：明确允许哪些命令/URL
2. **启用审计日志**：记录所有沙箱操作
3. **设置合理限制**：根据业务需求调整配额
4. **监控告警**：接近限制时发送告警
5. **定期审查**：检查白名单是否过时

---

## 六、性能考虑

### 6.1 开销分析

| 操作 | 额外开销 | 优化建议 |
|------|----------|----------|
| 命令白名单检查 | O(1) | 使用 HashSet |
| URL 匹配 | O(N) | 使用前缀树优化 |
| 速率限制 | O(1) | 定期清理旧数据 |
| 进程启动 | ~50ms | 复用进程池 |

### 6.2 优化建议

- 白名单/黑名单使用 `Set` 存储，O(1) 查找
- URL 匹配考虑使用 `Trie` 树优化
- 速率限制器定期清理过期数据（已实现）
- 考虑使用进程池减少进程启动开销
