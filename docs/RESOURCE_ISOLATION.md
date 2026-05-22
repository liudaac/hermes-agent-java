# Hermes Agent Java - 资源隔离实现文档

> 本文档合并了原 resource-isolation-analysis.md、PROCESS_SANDBOX_IMPL.md、NETWORK_SANDBOX_IMPL.md、STORAGE_QUOTA_IMPL.md、THREAD_POOL_ISOLATION_IMPL.md

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

## 六、推荐默认策略

| 资源 | 默认限制 | 说明 |
|---|---:|---|
| Token/日 | 100000 | 默认租户可放宽 |
| 消息/日 | 1000 | 防刷屏 |
| 磁盘 | 1GB | 单租户工作目录 |
| 并发任务 | 4 | 防线程耗尽 |
| 任务超时 | 60s | 工具调用默认超时 |
| 网络 | allowlist 优先 | 生产环境建议显式配置 |

---

## 七、安全注意事项

1. 不要把沙箱视为强安全边界；生产环境仍建议容器/虚拟机隔离
2. 文件路径必须统一做 normalize + root prefix 校验
3. 外部命令默认拒绝，仅允许白名单
4. 网络访问默认最小权限
5. 所有拒绝事件写入审计日志

