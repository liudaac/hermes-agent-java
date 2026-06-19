# Hermes Agent Java - 资源隔离文档

> 覆盖进程、网络、存储、线程池四大隔离维度，以及 Delegated Executor 安全契约、前端集成与集成指南。

---

## 一、目标

为多租户 Hermes Agent 提供进程、网络、存储和线程池层面的隔离，防止租户之间资源争用、越权访问和安全影响扩散。

---

## 二、进程沙箱 ProcessSandbox

### 目标
- 限制工具执行产生的外部进程权限
- 控制进程超时、工作目录、环境变量
- 防止跨租户文件访问

### 核心能力
- 租户级工作目录
- 命令白名单/黑名单
- 超时控制
- 环境变量隔离
- 输出大小限制

### 关键类
- `tenant.sandbox.ProcessSandbox`
- `tenant.sandbox.SandboxConfig`
- `tenant.sandbox.ProcessExecutionResult`

---

## 三、网络沙箱 NetworkSandbox

### 目标
- 控制工具访问外部网络的范围
- 支持租户级 allowlist/blocklist
- 限制请求速率和超时

### 核心能力
- 域名白名单/黑名单
- IP 段限制
- 协议限制（http/https 等）
- 请求速率控制
- 审计日志

### 关键类
- `tenant.sandbox.NetworkSandbox`
- `tenant.sandbox.NetworkPolicy`
- `tenant.sandbox.NetworkAccessResult`

---

## 四、存储配额 StorageQuotaManager

### 目标
- 限制每个租户的磁盘空间使用
- 防止单租户占满宿主机磁盘
- 提供目录级统计和清理能力

### 核心能力
- 租户根目录隔离
- 配额检查
- 磁盘使用统计
- 文件数量限制
- 自动清理策略接口

### 关键类
- `tenant.quota.StorageQuotaManager`
- `tenant.quota.StorageQuota`
- `tenant.quota.StorageUsage`

---

## 五、线程池隔离 TenantThreadPool

### 目标
- 防止某个租户耗尽全局执行线程
- 支持租户级并发控制
- 支持优先级和队列限制

### 核心能力
- 租户级线程池/队列
- 并发上限
- 任务超时
- 拒绝策略
- 执行统计

### 关键类
- `tenant.execution.TenantThreadPool`
- `tenant.execution.TenantTask`
- `tenant.execution.TenantExecutionStats`

---

## 六、Delegated Executor 安全契约

Hermes 自身不执行 delegated agent，也不依赖外部 delegated 执行。`com.nousresearch.hermes.collaboration` 包定义了策略和验证基础——delegated executor 必须满足这些约束，其产出才能被接受。

### 6.1 默认安全姿态

`DelegatedExecutorSafetyPolicy.restrictiveDefault()` 刻意保守：

| 维度 | 默认值 |
|---|---|
| 允许变更路径 | `src/main/java`、`src/test/java`、`docs` |
| 禁止变更路径 | `.git`、`.github`、构建产物、核心构建文件 |
| 命令执行 | 禁止 |
| 网络访问 | 禁止 |
| 浏览器访问 | 禁止 |
| Patch 沙箱 | 必须 |
| 父级验证 | 必须 |
| 自动合并 | 关闭 |

策略只验证意图和报告的输出，不启动进程、不打开网络连接、不驱动浏览器、不修改文件。

### 6.2 LocalPatchExecutor

第一个具体执行器。Java 本地、仅 patch、沙箱化：
- 接受 caller 提供的 unified diff
- 在临时沙箱目录中应用
- 报告变更文件列表
- **不修改父级 checkout，也不自动合并**

### 6.3 生命周期（未来 Executor 遵循）

```
创建沙箱 → 沙箱内执行 → 收集 diff 和测试结果 → 父级验证 → 合并或拒绝
```

**关键不变量：**
- 所有工作在沙箱内完成，不直接写父级 checkout
- 默认只允许文件读 + patch 写；命令/网络/浏览器/自动合并均拒绝
- 默认 auto merge = false，父级决定是否应用 patch
- 被拒绝的 patch 留在沙箱记录中，不污染父级

---

## 七、多租户前端集成

> React `web/` 已成为 Dashboard 主线，租户管理页接入 `/tenants`。新租户 UI 优先接入 `web/src/pages/TenantsPage.tsx`，构建输出 `hermes_cli/web_dist`。

### 7.1 API 端点

| 方法 | 路径 | 描述 |
|------|------|------|
| **租户管理** | | |
| GET | `/api/tenants` | 租户列表 |
| POST | `/api/tenants` | 创建租户 |
| GET | `/api/tenants/{id}` | 租户详情 |
| DELETE | `/api/tenants/{id}` | 删除租户 |
| POST | `/api/tenants/{id}/suspend` | 暂停 |
| POST | `/api/tenants/{id}/resume` | 恢复 |
| **配额** | | |
| GET | `/api/tenants/{id}/quota` | 获取配额 |
| PUT | `/api/tenants/{id}/quota` | 更新配额 |
| GET | `/api/tenants/{id}/usage` | 使用量 |
| **安全策略** | | |
| GET | `/api/tenants/{id}/security` | 获取策略 |
| PUT | `/api/tenants/{id}/security` | 更新策略 |
| **审计** | | |
| GET | `/api/tenants/{id}/audit` | 审计日志 |
| **监控** | | |
| GET | `/api/tenants/{id}/metrics` | 资源指标 |
| **租户内功能** | | |
| GET | `/api/tenants/{id}/config` | 租户配置 |
| PUT | `/api/tenants/{id}/config` | 更新配置 |
| GET | `/api/tenants/{id}/sessions` | 会话列表 |
| GET | `/api/tenants/{id}/skills` | 技能列表 |

### 7.2 界面功能

访问路径：`/admin/tenants`

- **租户管理**：列表、创建/删除、暂停/恢复、详情
- **资源监控**：配额进度条、实时统计、历史趋势
- **审计日志**：操作记录、类型筛选、时间过滤

---

## 八、快速集成指南

### 8.1 配置示例

```java
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

### 8.2 使用示例

```java
TenantContext context = tenantManager.getTenant(tenantId);

// 执行命令
ProcessResult result = context.exec(
    List.of("git", "clone", repoUrl),
    ProcessOptions.builder().timeoutSeconds(60).build()
);

// 网络请求
HttpResponse<String> response = context.httpGet("https://api.github.com/users/octocat");
```

---

## 九、推荐默认策略

| 资源 | 默认限制 | 说明 |
|---|---:|---|
| Token/日 | 100000 | 默认租户可放宽 |
| 消息/日 | 1000 | 防刷屏 |
| 磁盘 | 1GB | 单租户工作目录 |
| 并发任务 | 4 | 防线程耗尽 |
| 任务超时 | 60s | 工具调用默认超时 |
| 网络 | allowlist 优先 | 生产环境建议显式配置 |

---

## 十、安全注意事项

1. 沙箱不是强安全边界；生产环境仍建议容器/虚拟机隔离
2. 文件路径必须统一做 normalize + root prefix 校验
3. 外部命令默认拒绝，仅允许白名单
4. 网络访问默认最小权限
5. 所有拒绝事件写入审计日志
6. Delegated executor 产出必须经父级验证才能合并

---

*版本: v1.2（合并：资源隔离 + Delegated Executor 契约 + 前端集成 + 集成指南）*
