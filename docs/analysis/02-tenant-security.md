# 阶段 2：多租户与安全边界

> 分析时间：2026-07-11
> 分析范围：`tenant/` 13 子系统、`approval/`、`policy/`、`auth/`
> HEAD：`e7c2b5b`

---

## 1. 总览

**代码量**：`tenant/` 14 920 行（59 文件）+ `approval/` 759 行 + `policy/` 700+ 行 + `auth/` 368 行 ≈ **16 700 行**。

这是整个项目最重的子系统——多租户隔离 + 沙箱 + 配额 + 审计 + 审批 + 指标 + GPU + 自动扩缩容一应俱全。但落地程度不均：**沙箱/配额/工具权限/文件隔离是真的落地了**，**Redis/Postgres/Distributed 抽象大多是未接线的骨架**。

## 2. TenantManager（610 行）— 租户注册表

- **注册表形式**：进程内 `ConcurrentHashMap<String, TenantContext>` + 磁盘 JSON `tenants/_system/tenants.json`
- **生命周期**：create / getOrCreateTenant / getOrLoadTenant / destroy / suspend / resume
- **延迟加载**：服务启动时只校验目录存在，租户在首次访问时 `TenantContext.load(tenantId)` 从磁盘恢复
- **空闲清理**：5 分钟一次 scheduled task，闲置超 30 分钟且无 active agents 的租户 unload（保留数据）
- **ACP 兼容方法**：`resolveForWorkspace(workspaceId)` 和 `getDefaultContext()`
- **tenantId 安全**：sanitize 为 `[\\p{L}\\p{N}_-]`，最长 64 字符，lowercase

⚠️ **关键观察**：TenantManager **单 JVM 单实例**，没有 peer 发现/集群协议。多实例部署下每个 JVM 各维护一份 tenants map 和本地 JSON 注册表，互不可见。

## 3. TenantContext（1318 行）— 租户容器

每个租户拥有一组**完全隔离**的子组件：

| 组件 | 类型 | 初始化时机 |
|---|---|---|
| `config` | TenantConfig | create/load |
| `fileSandbox` | TenantFileSandbox (526 行) | create/load |
| `memoryManager` | TenantMemoryManager (637 行) | create/load |
| `skillManager` | TenantSkillManager (321 行) | create/load |
| `sessionManager` | TenantSessionManager | create/load |
| `toolRegistry` | TenantToolRegistry (256 行) | create/load |
| `resourceMonitor` | TenantResourceMonitor | create/load |
| `processSandbox`/`cgroupSandbox` | ProcessSandbox / CgroupProcessSandbox | create/load（cgroup 不可用降级到普通 process） |
| `networkSandbox` | NetworkSandbox + RestrictedHttpClient | create/load |
| `memoryPool` | TenantMemoryPool (321 行) + TrackedByteBuffer (407 行) | create/load（默认 256MB/租户） |
| `quotaManager` | TenantQuotaManager (987 行) | create/load |
| `auditLogger` | TenantAuditLogger (216 行) | create/load（最先初始化） |
| `securityPolicy` | TenantSecurityPolicy (154 行) | create/load |
| `metrics` | TenantMetrics (JMX MBean + Prometheus 导出) | create/load |

协作/组织类组件通过**双重检查锁懒加载**：`governancePolicy`、`tenantBus`、`taskOrchestrator`、`negotiator`、`orgHealthChecker`、`orgKnowledgeBase`、`handoffProtocol`、`teamManager`、`scenarioOrchestrator`、`delegatedTaskStore`、`observability`、`evolutionEngine`、`browserBridge`/`browserApprovalQueue`——都在 `initCollaboration()` 启动 bus 时才陆续实例化。

**自动保存**：每 300 秒 `save()` 一次，所有子组件写盘。

**状态机**：`INITIALIZING → ACTIVE → SUSPENDED ↔ ACTIVE → CLEANING_UP → DESTROYED`；另外还有 `EXPIRED`。

### 3.1 便捷方法（沙箱桥）
- `exec(command, options)` → 优先 cgroupSandbox，退化 processSandbox
- `httpGet(url)` / `httpPost(url, body)` → restrictedHttpClient
- `allocateMemory(size)` / `freeMemory(buffer)` → memoryPool（Cleaner 自动释放 TrackedByteBuffer）

## 4. Sandbox 子系统（3210 行，最重）

### 4.1 TenantFileSandbox（526 行）——**落地扎实**
`validatePath(path, mode)` 做 **11 步校验**：
1. 非空检查
2. 绝对/相对路径规范化（相对路径基于 sandboxRoot，绝不碰 JVM cwd）
3. `toAbsolutePath().normalize()` + `toRealPath()` 符号链接解析
4. 路径遍历检测（`../`、NUL 字节、双写绕过等）
5. 符号链接禁止（可配置）
6. 硬链接禁止（可配置）
7. 目录深度限制（config.maxDepth）
8. **沙箱边界检查**：`realPath.startsWith(sandboxRoot)` 或白名单
9. 黑名单检查
10. 读写权限 + 父目录自动创建 + 存储配额
11. 文件类型/大小检查

还支持：session workspace 隔离（`createSessionWorkspace(sessionId)`）、临时文件、存储用量统计、递归 cleanup。这是真正的生产级文件沙箱。

### 4.2 ProcessSandbox（252 行）+ CgroupProcessSandbox（303 行）
- 默认 `ProcessBuilder` 启动子进程，设置 work directory、env、超时
- `CgroupProcessSandbox.isCgroupV2Available()` 检测 cgroup v2 是否可写，可用则把进程放进 cgroup 做 CPU/内存/pid 限制，不可用优雅降级
- 有 timeout、stdout/stderr 捕获、exit code
- ⚠️ **没有 seccomp / namespace / chroot**——进程级隔离是"工作目录切到沙箱+超时"级别，**不是容器级隔离**。ContainerSandbox（212 行）写了类但只是骨架，实际没接 docker 调用链（pom 里有 docker-java 但 grep 下来没有在 ProcessSandbox 里使用）。
- ⚠️ 命令白名单/黑名单靠 ApprovalSystem 的 dangerPattern（rm -rf/sudo/mkfs 等字符串匹配），不是结构化 AST 检查——绕过空间存在（阶段 3 细盘 tools 时再看）。

### 4.3 NetworkSandbox（118 行）+ RestrictedHttpClient（487 行）+ NetworkPolicy（275 行）
- `NetworkPolicy` 支持 allowlist/denylist host、max requests/sec、max bandwidth
- `RestrictedHttpClient` 用 JDK HttpClient，过 host 校验 + RateLimiter（令牌桶，437 行有一个自实现 RateLimiter 类）
- URL allow 检查做 DNS 解析后 IP 比对，**但没防 DNS rebinding**（解析一次后没锁 IP）
- ⚠️ 有 SSRF 风险：能访问 `169.254.169.254`（云 metadata）、内网 IP，需要 policy 显式 block

### 4.4 TenantMemoryPool（321 行）+ TrackedByteBuffer（407 行）
- 基于 `ByteBuffer.allocateDirect()` 的内存池，按租户总配额限制
- `TrackedByteBuffer` 使用 `Cleaner`（Java 9+ PhantomReference 替代）自动回收
- 提供 MemoryStats（used/peak/limit/count）
- ⚠️ 不是 JVM 堆外内存的硬限制——只是 Java 侧记账，对外调用 ProcessSandbox 启动的子进程内存完全管不着

### 4.5 RateLimiter（128 行，RestrictedHttpClient 内部类）
- 令牌桶自实现，线程安全
- 支持 tryAcquire 非阻塞

## 5. Quota 子系统（987 行）— 抽象了 Store，默认 Local

```
TenantQuotaManager
    └── QuotaStore（接口）
         ├── LocalQuotaStore（默认，AtomicInteger/AtomicLong 内存计数）
         └── RedisQuotaStore（有类，RedisCommandExecutor 接口抽象，Lua 脚本原子 incr+TTL）
```

- Quota 维度：`maxDailyRequests` / `maxDailyTokens` / `maxConcurrentAgents` / `maxStorageBytes` / `maxFileSizeBytes` / `maxToolCallsPerSession`
- 跨日自动重置，昨天 usage 归档到 `state/history/YYYY-MM-DD.json`，30 天自动清理
- 每次 increment 都写 `state/usage.json`（同步 IO），后台有异步版本但主路径没用到
- TenantQuotaManager 构造器**硬编码 `QuotaStoreFactory.createLocal()`**——`create(tenantId, "redis", executor)` 方法写了但没有任何调用点
- 所以**RedisQuotaStore 是死代码**，pom 里也没引入任何 Redis 客户端依赖（Lettuce/Jedis 都不在 pom.xml）

## 6. TenantToolRegistry（256 行）— 工具级权限检查

`checkPermission(toolName, args)` 做 4 层：
1. `securityPolicy.deniedTools` 黑名单直接拒绝
2. `securityPolicy.allowedTools` 非空时白名单
3. `quotaManager.checkToolCallQuota()` 工具调用配额
4. `paramCheck(toolName, args)` 参数校验（针对危险工具的简单参数模式匹配）

注意：这是**租户级**工具权限。Agent 角色级的 allowed/denied 在 `TenantAwareAIAgent.executeToolCall()` 里做（阶段 1 已盘），PolicyService 的 workspace+team+agent 三级规则又是另一层——三套权限过滤叠加。

## 7. TenantSecurityPolicy（154 行）

配置项：
- `allowCodeExecution`（默认 true）
- `requireSandbox`（默认 true）
- `allowedLanguages`（默认 python、javascript）
- `allowNetworkAccess`（默认？）
- `allowedHosts`（默认空）
- `networkPolicy`（引用 NetworkPolicy）
- `allowedTools` / `deniedTools`

从 `<tenantDir>/config/security-policy.yaml|yml|json` 加载，支持 save。

## 8. TenantAuditLogger（216 行）

- 日志写 `<tenantDir>/logs/audit-YYYY-MM-DD.log`（日切）
- 事件类型：`TENANT_CREATED/SUSPENDED/RESUMED/DESTROYED/AGENT_CREATED/TOOL_CALLED/FILE_READ/FILE_WRITE/COMMAND_EXECUTED/NETWORK_REQUEST/...`
- JSON Lines 格式，带 timestamp、tenantId、userId、action、details
- ⚠️ 但 TenantAwareToolDispatcher 真正执行工具时是否调 auditLogger.log？阶段 3 验证。

## 9. TenantMetrics（MBean + Prometheus）

- 实现 `TenantMetricsMBean`，注册到平台 MBeanServer（JMX 可查）
- MBean 暴露：tenantId、uptime、totalAgents、totalProcesses、totalToolCalls、storageBytes、dailyRequests、dailyTokens
- `toPrometheus()` 方法输出 Prometheus text format
- EmailAlertChannel + WebhookAlertChannel——在阈值超时时发告警
- ⚠️ AlertChannel 写了但没有调度代码定期检查阈值并触发告警——骨架

## 10. Skill 隔离（TenantSkillManager 321 行）

目录层级（从代码注释+实现看）：
```
<tenantDir>/skills/
├── private/          ← 租户私有 Skills（最优先）
├── installed/        ← 从 Registry 安装的 Skills
└── （loadFromDirectory 3层，再 fallback 到 builtin）
```
加上 `_shared/skills/`（TenantManager 初始化时创建的全局共享目录），**实际是 4 层查找**（修正上次盘点：不是"4 层 private/installed/shared/system"而是"private/installed/shared/builtin"）：
1. private（租户私有）
2. installed（租户从 registry 装的）
3. system（`../../_shared/skills`，跨租户共享）
4. builtin（`loadBuiltinSkill`，硬编码于 ToolInitializerV2 注册的技能）

搜索顺序：先到先得，seen set 去重。
加载时做 `DANGEROUS_PATTERNS` 检查（ignore previous instructions 等注入模式）。

## 11. Approval 三层架构

| 层 | 类 | 行数 | 位置 | 状态 |
|---|---|---|---|---|
| L1 内核 | `ApprovalSystem` | 347 | `approval/` | ✅ 落地 |
| L2 协调 | `ToolApprovalCoordinator` + `ApprovalMessageHandler` | 168+210=378 | `approval/` + `dashboard/handlers/` | ✅ 落地 |
| L3 业务 | `BusinessApprovalService` + `FileBusinessApprovalRepository` | 370+? | `business/approval/` | ✅ 落地 |
| 旁路 | `PolicyService.checkToolApprovalRequired` | 321 行（policy 包） | 🟡 半接 |

### 11.1 ApprovalSystem（内核）
- 7 种 ApprovalType：TERMINAL_COMMAND/FILE_WRITE/FILE_DELETE/CODE_EXECUTION/BROWSER_ACTION/SUBAGENT_SPAWN/SKILL_INSTALL
- 4 种模式：AUTO/PROMPT/REQUIRE/DENY
- 8 条硬编码 DangerPattern（mkfs、`> /dev/sda` 直接 DENY；rm -rf、sudo、dd if=、/etc/ 写、/usr/lib/systemd/ 写 REQUIRE）
- **normalizeCommandForDetection()** 做 shell 绕过防护：折叠 `\<newline>` 续行、`\x` 转义、`''`/`""` 空字符串、`${IFS}` 变量
- 30 分钟 session approval 缓存（同操作不再问）
- promptForApproval 走 externalApprover callback，没设置就走 console（`System.in` 读 y/n/a/d）

⚠️ 关键：这个内核是**控制台交互式**的（生产环境不可能真走 System.in），它现在更像是"危险模式兜底"，真正的审批走 L3 BusinessApprovalService + SSE 通知前端。

### 11.2 ToolApprovalCoordinator（跨 JVM 协调）
- 维护 `Map<String, ToolApprovalContext> pendingApprovals`（ConcurrentHashMap）
- `requestToolApproval(...)` 创建 BusinessApprovalRecord 持久化 + 抛 ToolApprovalRequiredException
- `resumeToolApproval(approvalId, approved, reason)` → 调 agent.resumeToolApproval(...)
- 300s 超时（CountDownLatch.await）

⚠️ **pendingApprovals 是进程内 Map**——如果 approve 请求打到另一个 JVM 实例，找不到记录。这就是之前盘点说的"审批跨实例丢失"。

### 11.3 BusinessApprovalService
- 持久化到 `~/.hermes/business/workspaces/{workspaceId}/approvals/{approvalId}.json`（File 仓库）
- 三级事件订阅：global/workspace/approvalId
- RedisApprovalStore 类写了但没人 new
- SSE 推送通过 JarvisHandler 的 `subscribeGlobal` + `BusinessEventSseHandler`

### 11.4 PolicyService
- WorkspacePolicyRecord 持久化到 JSON 仓库
- 规则：every_tool_requires_approval / high_risk_keywords / agent_role_allowed_tools / allowed_tools / denied_tools
- `checkToolApprovalRequired(workspaceId, teamId, agentId, toolName, args)` 返回 ApprovalCheckResult
- 但 **TenantAwareAIAgent.executeToolCall 里只检查 agentRole.getToolApprovalRules()**，没调 PolicyService——工作空间级策略实际未在 agent 执行路径上生效。🟡 又是"写了没接"。

## 12. auth/SsoService（253 行）— OIDC 骨架

- OIDC Authorization Code Flow：`startLogin()` → `handleCallback(code)` → `validateSession(token)` → `logout()`
- state 防 CSRF，5 分钟 TTL；session 8 小时 TTL
- 进程内 ConcurrentHashMap 存 sessions 和 pending states
- ⚠️ **没被 DashboardServer 调用**——Dashboard 现在用自己生成的 `SecureRandom` sessionToken（阶段 0 已盘），SsoService 是死代码。

## 13. 其他子系统

| 子系统 | 文件 | 行数 | 评估 |
|---|---|---|---|
| `tenant/autoscaler/TenantAutoscaler` | 1 文件 | 314 | 基于 metrics 的水平扩缩建议骨架，没实际扩缩逻辑 |
| `tenant/container/ContainerSandbox` | 1 文件 | 212 | Docker 容器隔离骨架，方法体多为 `throw new UnsupportedOperationException("Not yet implemented")` |
| `tenant/gpu/GpuManager` | 1 文件 | 289 | GPU 显存记账 + 分配/释放，没对接 CUDA/NVML，是模拟接口 |
| `tenant/lifecycle/GracefulShutdownHandler` | 1 文件 | 140 | JVM shutdown hook 注册，调 TenantManager.shutdown() |
| `tenant/metrics/EmailAlertChannel` + `WebhookAlertChannel` | 2 文件 | 200+ | SMTP/HTTP 告警通道，但阈值触发调度没接 |
| `tenant/persistence/FileSystemTenantRepository` + `PostgresTenantRepository` + `TenantStateRepository` | 3 文件 | 831 | FileSystem 是真的；Postgres 类写了但 pom 没 pg JDBC driver 没引用；TenantStateRepository 只被 DistributedSessionManager 用 |
| `tenant/session/DistributedSessionManager` + `JsonSessionSerializer` + `ModelOverride` | 4 文件 | 994 | DistributedSessionManager 依赖 TenantStateRepository（Postgres 版），没有构造调用点；TenantContext 里 sessionManager 实际是 TenantSessionManager（本地文件） |

## 14. 多实例一致性 7 问题复核

重新验证上次盘点提的 7 个问题，给出当前状态：

| # | 问题 | 当前状态 |
|---|---|---|
| 1 | 🔴 配额计数 → Redis | ❌ RedisQuotaStore 写了但**没接入**（pom 没 Redis client，没 new 点，默认 LocalQuotaStore 内存计数）。多实例下同一 tenant 在不同 JVM 上独立计数 → **可绕过配额直接亏钱**。 |
| 2 | 🔴 租户路由 sticky | ❌ 无 sticky session / tenant locality。同一 workspaceId 的请求可能轮询到不同 JVM，每个 JVM 独立 load TenantContext，状态分裂（conversationHistory、approvalCheckpoint、memory 都在内存）。 |
| 3 | 🟠 审批跨实例 callback 丢失 | ❌ `ToolApprovalCoordinator.pendingApprovals` 是进程内 CHM；`BusinessApprovalService` 虽然存了 JSON 文件，但**resume 请求只查 Coordinator 内存**，在另一个实例找不到 → 用户点"批准"按钮会 IllegalStateException。 |
| 4 | 🟠 速率限制 → Redis | ❌ `RestrictedHttpClient.RateLimiter` 是**进程内令牌桶**，多实例下每个 JVM 独立速率限制，总速率 = N × 配置速率。TenantResourceMonitor 里也没看到分布式限流。 |
| 5 | 🟡 审计中心化 | 🟡 日志写本地文件 `<tenantDir>/logs/audit-*.log`。多实例下同一 tenant 的审计分散到各机本地磁盘，合规取证需要事后汇总（不是 P0 但要 log shipper）。 |
| 6 | 🟡 Skill 索引广播 | ❌ private/installed 是本地文件系统，新 skill 安装只在当前 JVM 可见，TenantSkillManager 也没刷新机制（启动时 load 一次）。 |
| 7 | 🟢 JMX → Prometheus 标签 | 🟢 TenantMetrics.toPrometheus() 已经实现，暴露 HTTP endpoint 的位置待阶段 6 验证。 |

**结论**：7 处问题**全部存在**，而且 Redis/Postgres/Distributed 这些"抽象层"虽然在代码里写了，但都是**没 pom 依赖、没构造调用点**的骨架，实际上是单 JVM + 本地文件系统的部署模型。

## 15. 阶段 2 识别到的债/风险

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🔴 | Redis/Postgres 抽象全是骨架 | tenant/quota、tenant/persistence、business/approval、tenant/session | pom 没有 Redis client/JDBC driver，Factory 只 new Local/File 实现，Redis/Postgres/Distributed 代码是死的 |
| 🔴 | 多实例完全不 work（配额/审批/routing/速率限制都是进程内） | 多处 | 阶段 14 列表 1–4 项。"多租户"成立，"多实例"目前是单实例部署模型 |
| 🔴 | SsoService 没接入登录链路 | auth/SsoService | DashboardServer 自己用 SecureRandom 生成 token，SSO 是死代码 |
| 🟠 | PolicyService 的工作空间级工具审批没接到 agent.executeToolCall | policy/PolicyService | agent 只查 agentRole 规则，工作区/团队级规则绕过 |
| 🟠 | ProcessSandbox 没有容器/namespace 隔离 | ProcessSandbox、ContainerSandbox（骨架） | "沙箱"是 work dir + timeout，不是安全边界；ContainerSandbox 方法抛 UnsupportedOperationException |
| 🟠 | NetworkSandbox 缺 DNS rebinding 防护 + 内网 IP 默认不拦 | RestrictedHttpClient | 能访问云 metadata（169.254.169.254），有 SSRF 风险 |
| 🟡 | AlertChannel 没接调度触发 | Email/WebhookAlertChannel | 通道写了但阈值触发逻辑没有 |
| 🟡 | CgroupSandbox 在无 cgroup v2 机器上静默降级 | TenantContext.initializeResourceSandboxes | 开发者机器上跑的是普通 ProcessSandbox，cgroup 限制不生效可能导致测试与生产行为不一致 |
| 🟡 | 三层工具权限过滤（PolicyService/TenantSecurityPolicy/AgentRole）逻辑分散 | 三处叠加 | 运维配置容易混乱，没有一个"最终裁决点" |
| 🟢 | TenantContext 构造器 15+ 个 `@Nullable` 组件 + 大量 lazy init DCL | TenantContext | 生命周期复杂，有 NPE 风险 |
| 🟢 | persistSession 每次工具调用都写 usage.json 同步 IO | TenantQuotaManager.saveUsage | 高频场景下磁盘 IO 可能成为瓶颈 |

## 16. 阶段 2 小结

**多租户成熟度：Beta（80%）单实例 / Alpha（35%）多实例**

单实例部署下多租户是真隔离：
- ✅ 文件沙箱 11 步校验（扎实）
- ✅ 网络沙箱（host allowlist + 限流，但 SSRF 有缝）
- ✅ 进程沙箱（cgroup v2 可用时 CPU/MEM 限制）
- ✅ 内存池 + Cleaner 自动回收
- ✅ 配额记账（日请求/token/存储/并发）
- ✅ 工具权限（租户级 allow/deny）
- ✅ 审计日志（本地文件）
- ✅ Skill 4 层隔离（private/installed/shared/builtin）
- ✅ 审批 3 层架构（内核/协调/业务）
- ✅ JMX + Prometheus metrics
- ✅ 30 分钟空闲租户自动卸载

但多实例/分布式：
- ❌ Redis/Postgres 抽象是"有类没依赖没调用"的死代码
- ❌ Sticky routing 不存在
- ❌ 审批/速率限制/配额全部进程内
- ❌ SSO 没接
- ❌ ContainerSandbox 是 UnsupportedOperationException 骨架
- ❌ GPU 管理是模拟

如果部署形态是**单 JVM + 本地磁盘**（当前 Dockerfile 就是这种），可以跑 Beta；如果要做**多副本水平扩展、高可用、容灾**，多租户隔离就要从"进程内"升级到"分布式"，工作量 2–3 周（加 Redis 依赖 + 接 RedisQuotaStore + 接 RedisApprovalStore + 接 DistributedSessionManager + sticky routing + DNS rebinding 防护 + ContainerSandbox 实现）。

### 亮点
- **File sandbox 的 11 步路径校验是教科书级**，考虑了符号链接、硬链接、深度、traversal、白/黑名单、文件类型、配额
- **normalizeCommandForDetection** 折叠 shell 续行/转义/IFS 绕过，这是对标 OpenAI/Browser Use 等真实 CVE 的 patch
- **TenantContext 用双重检查锁做协作组件的 lazy init**——冷启动快、不跑协作场景不实例化一堆东西
- **MemoryPool + Cleaner + TrackedByteBuffer** 是少见的 Java 侧堆外内存记账实现

### 下一步
→ **阶段 3：工具与执行沙箱** —— 深盘 `tools/` 10 072 行（TenantAwareToolDispatcher 8 关卡 + 每个工具实现）+ `browser/` 1700 行 + `terminal/` 658 行，验证"工具分发流水线到底长什么样，TenantFileSandbox 的校验是否真的被每个工具调用到"。
