# 资源隔离集成指南

## 快速集成步骤

### 步骤1: 复制 Java 文件到项目

将以下文件复制到您的项目中：

```
src/main/java/com/nousresearch/hermes/tenant/sandbox/
├── ProcessSandbox.java
├── ProcessSandboxConfig.java
├── ProcessOptions.java
├── ProcessResult.java
├── ProcessSandboxException.java
├── NetworkSandbox.java
├── NetworkPolicy.java
├── NetworkSandboxException.java
└── RateLimiter.java
```

### 步骤2: 修改 TenantContext

在 `TenantContext.java` 中添加：

```java
// 1. 修改 imports
import com.nousresearch.hermes.tenant.sandbox.*;

// 2. 添加字段
public class TenantContext {
    // ... 现有字段 ...
    private final ProcessSandbox processSandbox;
    private final NetworkSandbox networkSandbox;
    
    // 3. 在构造函数中初始化
    public TenantContext(TenantProvisioningRequest request, ...) {
        // ... 现有代码 ...
        
        this.processSandbox = new ProcessSandbox(
            this,
            request.getProcessSandboxConfig() != null 
                ? request.getProcessSandboxConfig() 
                : ProcessSandboxConfig.defaultConfig()
        );
        
        this.networkSandbox = new NetworkSandbox(
            request.getNetworkPolicy() != null
                ? request.getNetworkPolicy()
                : NetworkPolicy.defaultPolicy()
        );
    }
    
    // 4. 添加便捷方法
    public ProcessResult exec(List<String> command, ProcessOptions options) {
        return processSandbox.exec(command, options);
    }
    
    public HttpResponse<String> httpGet(String url) {
        return networkSandbox.get(url);
    }
}
```

### 步骤3: 修改 TenantProvisioningRequest

```java
public class TenantProvisioningRequest {
    // 添加新字段
    private ProcessSandboxConfig processSandboxConfig;
    private NetworkPolicy networkPolicy;
    
    // Builder 方法
    public Builder processSandboxConfig(ProcessSandboxConfig config) {
        this.processSandboxConfig = config;
        return this;
    }
    
    public Builder networkPolicy(NetworkPolicy policy) {
        this.networkPolicy = policy;
        return this;
    }
    
    // Getters
    public ProcessSandboxConfig getProcessSandboxConfig() { return processSandboxConfig; }
    public NetworkPolicy getNetworkPolicy() { return networkPolicy; }
}
```

### 步骤4: 配置示例

```java
// 创建租户时配置沙箱
TenantProvisioningRequest request = TenantProvisioningRequest.builder(tenantId, createdBy)
    .tenantName(name)
    .processSandboxConfig(
        ProcessSandboxConfig.builder()
            .commandWhitelist(Set.of("git", "mvn", "python3"))
            .workDirectory(Paths.get("/data/tenants/" + tenantId))
            .build()
    )
    .networkPolicy(
        NetworkPolicy.builder()
            .allowHost("*.github.com")
            .allowHost("*.openai.com")
            .blockHost("localhost")
            .maxRequestsPerSecond(10)
            .build()
    )
    .build();
```

### 步骤5: 使用示例

```java
// 获取租户上下文
TenantContext context = tenantManager.getTenant(tenantId);

// 执行命令
try {
    ProcessResult result = context.exec(
        List.of("git", "clone", "https://github.com/user/repo.git"),
        ProcessOptions.builder().timeoutSeconds(60).build()
    );
    
    if (result.isSuccess()) {
        System.out.println(result.getStdout());
    }
} catch (ProcessSandboxException e) {
    System.err.println("Command not allowed: " + e.getMessage());
}

// 发送网络请求
try {
    HttpResponse<String> response = context.httpGet("https://api.github.com/users/octocat");
    System.out.println(response.body());
} catch (NetworkSandboxException e) {
    System.err.println("Access denied: " + e.getMessage());
}
```

## 完整代码文件

所有代码文件已保存在服务器：
- 位置：`/root/hermes-agent-java/src/main/java/com/nousresearch/hermes/tenant/sandbox/`
- 说明文档：`/root/hermes-agent-java/IMPLEMENTATION_DETAILS.md`

您可以直接复制这些文件到本地项目中使用。
