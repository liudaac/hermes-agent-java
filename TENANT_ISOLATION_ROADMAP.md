# 多租户隔离实施路线图

## 概述

Hermes Agent Java 多租户隔离方案，实现服务端部署支持多用户资源隔离。

## 隔离层级

```
┌─────────────────────────────────────────────────────────────────┐
│                        系统级别隔离                              │
│  • JVM 进程隔离（未来：Docker/K8s）                              │
│  • 操作系统用户隔离                                              │
├─────────────────────────────────────────────────────────────────┤
│                        租户级别隔离                              │
│  • 独立文件沙箱                                                  │
│  • 独立内存空间                                                  │
│  • 独立配置                                                      │
├─────────────────────────────────────────────────────────────────┤
│                        会话级别隔离                              │
│  • 临时工作区                                                    │
│  • 会话状态隔离                                                  │
├─────────────────────────────────────────────────────────────────┤
│                        Agent 级别隔离                            │
│  • 工具调用隔离                                                  │
│  • 代码执行隔离                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## 核心组件

### 1. 文件沙箱隔离 ✅

**组件**: `TenantFileSandbox`

| 功能 | 状态 | 说明 |
|------|------|------|
| 路径遍历防护 | ✅ | 阻止 `../` 和 `~` 攻击 |
| 符号链接控制 | ✅ | 禁止危险的符号链接 |
| 目录深度限制 | ✅ | 最大深度 10 层 |
| 存储配额执行 | ✅ | 限制租户存储使用 |
| 会话工作区隔离 | ✅ | 每个会话独立目录 |

**存储结构**:
```
~/.hermes/tenants/{tenantId}/
├── workspace/          # 主工作区
│   ├── sessions/       # 会话工作区
│   ├── uploads/        # 上传文件
│   ├── generated/      # 生成文件
│   ├── cache/          # 缓存文件
│   └── temp/           # 临时文件
├── skills/             # Skill 隔离
│   ├── private/        # 私有 Skills
│   └── installed/      # 安装的 Skills
├── memory/             # 记忆隔离
├── audit/              # 审计日志
└── config/             # 租户配置
    └── security.yaml   # 安全策略
```

### 2. 代码执行沙箱 ✅ 新增

**组件**: `TenantAwareCodeTool`

| 功能 | 状态 | 说明 |
|------|------|------|
| 独立执行目录 | ✅ | 每个租户独立的代码执行目录 |
| 环境变量隔离 | ✅ | 清除继承的环境变量 |
| 资源限制 | ✅ | CPU、内存、时间限制 |
| 审计日志 | ✅ | 记录所有代码执行 |
| 语言白名单 | ✅ | 可配置允许的语言 |

**实现要点**:
- 代码在租户专属目录执行：`~/.hermes/tenants/{tenantId}/workspace/code/`
- 环境变量完全隔离，只设置必要的 `HOME` 和 `TMPDIR`
- 支持 Python、JavaScript、Bash 三种语言
- 超时和内存限制通过配额系统控制

### 3. 工具权限配置 ✅ 新增

**组件**: `TenantToolRegistry` + `TenantSecurityPolicy`

| 功能 | 状态 | 说明 |
|------|------|------|
| 允许列表 | ✅ | 显式允许的工具列表 |
| 拒绝列表 | ✅ | 显式禁止的工具列表 |
| 参数安全检查 | ✅ | 危险命令检测 |
| 敏感数据脱敏 | ✅ | 审计日志脱敏处理 |
| 调用配额限制 | ✅ | 每会话工具调用次数限制 |

**配置示例** (`security.yaml`):
```yaml
allowCodeExecution: true
requireSandbox: true
allowedLanguages: [python, javascript]
allowNetworkAccess: false
allowedHosts: []
allowedTools: [file_read, file_write, web_search]
deniedTools: [terminal, bash]
allowFileRead: true
allowFileWrite: true
deniedPaths: [/etc/passwd, /root, /var]
```

### 4. 资源配置与审计 ✅

**组件**: `TenantQuotaManager` + `TenantAuditLogger`

| 资源类型 | 配额控制 | 审计记录 |
|----------|----------|----------|
| 每日请求数 | ✅ | ✅ |
| 每日 Token 数 | ✅ | ✅ |
| 并发 Agent 数 | ✅ | ✅ |
| 存储空间 | ✅ | ✅ |
| 内存使用 | ✅ | ✅ |
| 工具调用次数 | ✅ | ✅ |
| 代码执行 | ✅ | ✅ |
| 文件操作 | ✅ | ✅ |

**配额配置** (`quota`):
```java
TenantQuota quota = new TenantQuota()
    .setMaxDailyRequests(1000)
    .setMaxDailyTokens(1_000_000)
    .setMaxConcurrentAgents(5)
    .setMaxStorageBytes(100 * 1024 * 1024)  // 100MB
    .setMaxMemoryBytes(512 * 1024 * 1024)    // 512MB
    .setMaxToolCallsPerSession(50)
    .setMaxExecutionTime(Duration.ofMinutes(5));
```

### 5. Skill 隔离机制 ✅

**组件**: `TenantSkillManager`

| 层级 | 优先级 | 隔离性 | 可修改 |
|------|--------|--------|--------|
| PRIVATE | 1 | 租户私有 | ✅ |
| INSTALLED | 2 | 租户独立 | ✅ |
| SHARED | 3 | 租户间共享 | ❌ |
| SYSTEM | 4 | 系统预设 | ❌ |
| BUILTIN | 5 | 内置只读 | ❌ |

**安全扫描**:
- 签名验证
- 危险代码模式检测
- 内容哈希验证

## 关键实现改进

### 改进 1: TenantAwareCodeTool (新增)

```java
// 租户感知的代码执行工具
public class TenantAwareCodeTool {
    private final TenantContext context;
    private final Path sandboxDir;  // 租户专属目录
    
    private String executePython(Map<String, Object> args) {
        // 1. 检查安全策略
        if (!context.getSecurityPolicy().isAllowCodeExecution()) {
            return error("Code execution disabled");
        }
        
        // 2. 检查配额
        context.getQuotaManager().checkStorageQuota(code.length());
        
        // 3. 在租户沙箱执行
        ProcessBuilder pb = new ProcessBuilder("python3", script);
        pb.directory(sandboxDir.toFile());
        
        // 4. 完全隔离的环境变量
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", sandboxDir.toString());
        env.put("TMPDIR", tempDir.toString());
        
        // 5. 审计日志
        context.getAuditLogger().log(CODE_EXECUTED, ...);
    }
}
```

### 改进 2: TenantToolRegistry (重写)

```java
public PermissionCheckResult checkPermission(String toolName, Map<String, Object> args) {
    // 1. 检查拒绝列表
    if (securityPolicy.getDeniedTools().contains(toolName)) {
        return denied("Tool in denied list");
    }
    
    // 2. 检查允许列表
    if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
        return denied("Tool not in allowed list");
    }
    
    // 3. 检查调用配额
    if (sessionToolCalls.get() >= maxCallsPerSession) {
        return denied("Quota exceeded");
    }
    
    // 4. 参数安全检查
    if (containsDangerousPatterns(args)) {
        return denied("Dangerous pattern detected");
    }
    
    return allowed();
}
```

## 部署架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Load Balancer                          │
└────────────────────┬────────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
┌───────▼─────┐ ┌────▼─────┐ ┌────▼─────┐
│  Hermes     │ │  Hermes  │ │  Hermes  │
│  Instance 1 │ │Instance 2│ │Instance 3│
│  (Tenant A) │ │(Tenant B)│ │(Tenant C)│
└───────┬─────┘ └────┬─────┘ └────┬─────┘
        │            │            │
        └────────────┼────────────┘
                     │
        ┌────────────▼────────────┐
        │    Shared Storage       │
        │  (S3/MinIO/NFS)         │
        │                         │
        │  tenants/{tenantId}/    │
        └─────────────────────────┘
```

## 安全清单

### 文件系统安全
- [x] 路径遍历防护
- [x] 符号链接检查
- [x] 硬链接限制
- [x] 目录深度限制
- [x] 文件类型检查
- [x] 存储配额执行

### 代码执行安全
- [x] 独立执行目录
- [x] 环境变量隔离
- [x] 超时控制
- [x] 内存限制
- [x] 语言白名单
- [x] 审批流程
- [x] 审计日志

### 网络安全
- [x] 网络访问控制
- [x] 主机白名单
- [x] 请求配额
- [x] Token 配额

### 工具安全
- [x] 工具允许/拒绝列表
- [x] 参数安全检查
- [x] 调用配额
- [x] 敏感数据脱敏

### 资源隔离
- [x] CPU 限制
- [x] 内存限制
- [x] 存储限制
- [x] 并发限制
- [x] 时间限制

## API 端点

### 租户管理
```
POST   /api/v1/tenants              # 创建租户
GET    /api/v1/tenants/:id          # 获取租户信息
DELETE /api/v1/tenants/:id          # 删除租户
POST   /api/v1/tenants/:id/suspend   # 暂停租户
POST   /api/v1/tenants/:id/resume    # 恢复租户
```

### 资源配额
```
GET    /api/v1/tenants/:id/quota     # 获取配额
PUT    /api/v1/tenants/:id/quota     # 更新配额
GET    /api/v1/tenants/:id/usage     # 获取使用量
```

### 安全策略
```
GET    /api/v1/tenants/:id/security  # 获取安全策略
PUT    /api/v1/tenants/:id/security  # 更新安全策略
```

### 审计日志
```
GET    /api/v1/tenants/:id/audit     # 获取审计日志
GET    /api/v1/tenants/:id/audit/export  # 导出审计日志
```

## 监控指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `tenant.active` | Gauge | 活跃租户数 |
| `tenant.requests.total` | Counter | 租户请求总数 |
| `tenant.requests.rate` | Rate | 请求速率 |
| `tenant.quota.usage` | Gauge | 配额使用率 |
| `tenant.storage.bytes` | Gauge | 存储使用量 |
| `tenant.memory.bytes` | Gauge | 内存使用量 |
| `tenant.tool.calls` | Counter | 工具调用次数 |
| `tenant.audit.events` | Counter | 审计事件数 |
| `tenant.security.violations` | Counter | 安全违规次数 |

## 下一步工作

### 短期 (1-2 周)
- [ ] 添加更多单元测试
- [ ] 实现 API 端点
- [ ] 完善错误处理

### 中期 (1 个月)
- [ ] 实现 Docker 容器隔离
- [ ] 添加 WebSocket 实时审计
- [ ] 实现租户间 Skill 共享

### 长期 (3 个月)
- [ ] K8s Operator 部署
- [ ] 分布式存储集成
- [ ] 多区域部署支持

## 相关文件

| 文件 | 说明 |
|------|------|
| `TenantContext.java` | 租户上下文 |
| `TenantManager.java` | 租户管理器 |
| `TenantFileSandbox.java` | 文件沙箱 |
| `TenantAwareCodeTool.java` | 代码执行沙箱 |
| `TenantToolRegistry.java` | 工具权限控制 |
| `TenantQuotaManager.java` | 资源配额管理 |
| `TenantSecurityPolicy.java` | 安全策略 |
| `TenantAuditLogger.java` | 审计日志 |
| `TenantSkillManager.java` | Skill 隔离 |
