# Hermes Agent Java - 架构文档

> 系统整体架构、租户隔离机制与关键流程的核心视图。

---

## 一、整体系统架构

```mermaid
graph TB
    subgraph "External Layer"
        Users[用户/客户端]
        Platforms[消息平台<br/>Discord/Telegram/Feishu/QQ]
        ExternalAPIs[外部API<br/>OpenAI/Anthropic/Brave]
    end

    subgraph "Gateway Layer"
        Gateway[GatewayServer<br/>HTTP WebSocket]
        AuthFilter[TenantAuthFilter<br/>租户认证]
        RateLimiter[租户级限流]
    end

    subgraph "Core Engine"
        Agent[HermesAgentV2<br/>核心Agent引擎]
        ModelClient[ModelClient<br/>模型客户端]
        Transport[TransportFactory<br/>传输层适配]
    end

    subgraph "Tool System"
        ToolRegistry[ToolRegistry<br/>工具注册中心]
        subgraph "Built-in Tools"
            FileTool[FileTool]
            CodeTool[CodeTool]
            Browser[BrowserToolV2]
            WebSearch[WebSearchToolV2]
            Terminal[TerminalTool]
            GitTool[GitTool]
            MCPTool[MCPTool]
            SubAgent[SubAgentTool]
        end
        subgraph "Platform Tools"
            Feishu[FeishuDocTool]
            Discord[DiscordTool]
            QQBot[QQBotAdapter]
        end
    end

    subgraph "Multi-Tenant System"
        TenantMgr[TenantManager]
        TenantCtx[TenantContext]
        subgraph "Tenant Isolation"
            FileSandbox[文件沙箱]
            ProcessSandbox[进程沙箱]
            NetworkSandbox[网络沙箱]
            StorageQuota[存储配额]
            ThreadPool[线程池隔离]
        end
        subgraph "Tenant Management"
            Config[租户配置]
            Quota[资源配额]
            Security[安全策略]
            Audit[审计日志]
            SessionMgr[会话管理]
        end
    end

    subgraph "Storage Layer"
        LocalFS[本地文件系统<br/>sandbox/{tenantId}/]
        GitRepos[Git仓库]
    end

    Users -->|HTTP/WebSocket| Gateway
    Platforms -->|Webhook| Gateway
    Gateway --> AuthFilter --> RateLimiter --> Agent
    Agent --> ModelClient --> Transport --> ExternalAPIs
    Agent --> ToolRegistry
    ToolRegistry --> FileTool & CodeTool & Browser & WebSearch & Terminal & GitTool & MCPTool & SubAgent
    ToolRegistry --> Feishu & Discord & QQBot
    Agent --> TenantMgr --> TenantCtx
    TenantCtx --> FileSandbox & ProcessSandbox & NetworkSandbox & StorageQuota & ThreadPool
    TenantCtx --> Config & Quota & Security & Audit & SessionMgr
    FileSandbox --> LocalFS
    ProcessSandbox --> LocalFS
    Terminal --> LocalFS
    GitTool --> GitRepos
```

---

## 二、租户隔离架构

### 2.1 多层隔离模型

```mermaid
graph TB
    subgraph "Physical Layer"
        JVM[JVM进程]
        OS[操作系统]
    end

    subgraph "Tenant Isolation Layer"
        subgraph "Tenant A"
            TA_Config[独立配置]
            TA_FS[文件沙箱]
            TA_Process[进程沙箱]
            TA_Network[网络沙箱]
            TA_Thread[线程池]
            TA_Storage[存储配额]
        end
        subgraph "Tenant B"
            TB_Config[独立配置]
            TB_FS[文件沙箱]
            TB_Process[进程沙箱]
            TB_Network[网络沙箱]
            TB_Thread[线程池]
            TB_Storage[存储配额]
        end
    end

    subgraph "Shared Resources"
        Shared_Model[模型客户端<br/>共享连接池]
        Shared_Tool[工具注册表<br/>只读共享]
        Shared_Platform[平台适配器<br/>多路复用]
    end

    TenantA --> TA_Config & TA_FS & TA_Process & TA_Network & TA_Thread & TA_Storage
    TenantB --> TB_Config & TB_FS & TB_Process & TB_Network & TB_Thread & TB_Storage
    TenantA & TenantB --> Shared_Model & Shared_Tool & Shared_Platform
```

### 2.2 五大隔离维度

| 维度 | 组件 | 核心能力 |
|---|---|---|
| 文件 | TenantFileSandbox | 路径限制、权限控制、审计日志 |
| 进程 | ProcessSandbox | 命令白/黑名单、工作目录限制、环境变量清理、超时控制 |
| 网络 | NetworkSandbox | 域名白/黑名单、速率限制、请求审计 |
| 存储 | StorageQuotaEnforcer | 配额检查、流式写入追踪、阈值告警 |
| 线程 | TenantThreadPool | 线程数上限、队列限制、资源统计 |

---

## 三、关键流程时序

### 3.1 工具执行流程（带资源限制）

```mermaid
sequenceDiagram
    participant Agent as Agent
    participant Tool as ToolRegistry
    participant Ctx as TenantContext
    participant Sandbox as SandboxComponents
    participant FS as 文件系统/进程

    Agent->>Tool: executeTool(name, args)
    Tool->>Ctx: 获取租户上下文
    Ctx-->>Tool: TenantContext
    
    Tool->>Ctx: 权限检查
    Ctx->>Ctx: SecurityPolicy.check()
    Ctx-->>Tool: 允许/拒绝
    
    alt 权限拒绝
        Tool-->>Agent: PermissionDeniedError
    else 权限通过
        Tool->>Ctx: 配额检查
        Ctx->>Ctx: Quota.check()
        Ctx-->>Tool: 通过/超限
        
        alt 配额超限
            Tool-->>Agent: QuotaExceededError
        else 配额充足
            Tool->>Sandbox: 沙箱执行
            Sandbox->>FS: 实际操作
            FS-->>Sandbox: 结果
            Sandbox-->>Tool: 执行结果
            Tool->>Ctx: 记录审计日志
            Tool-->>Agent: ToolResult
        end
    end
```

### 3.2 租户创建流程

```mermaid
sequenceDiagram
    participant Caller as 调用方
    participant TM as TenantManager
    participant TC as TenantContext
    participant SB as 沙箱组件
    participant FS as 文件系统

    Caller->>TM: provisionTenant(tenantId, config)
    TM->>TM: 检查租户是否已存在
    TM->>FS: 创建租户目录 sandbox/{tenantId}/
    TM->>TC: 创建TenantContext
    TC->>SB: 初始化各沙箱组件
    SB->>SB: 文件沙箱 + 进程沙箱 + 网络沙箱
    SB->>SB: 存储配额 + 线程池
    TC->>TC: 初始化配置、审计、会话管理
    TC-->>TM: TenantContext
    TM-->>Caller: 租户创建成功
```

---

## 四、组件关系

### 4.1 工具系统与租户隔离的集成

```mermaid
graph TB
    subgraph "Agent Core"
        Agent[HermesAgentV2]
        Model[ModelClient]
    end

    subgraph "Tool System"
        Registry[ToolRegistry]
        subgraph "Sandboxed Tools"
            Code[CodeTool]
            Terminal[TerminalTool]
            Git[GitTool]
            File[FileTool]
        end
        subgraph "Network Tools"
            WebSearch[WebSearchToolV2]
            Browser[BrowserToolV2]
        end
        subgraph "External Tools"
            MCP[MCPTool]
            SubAgent[SubAgentTool]
        end
    end

    subgraph "Tenant Context"
        Context[TenantContext]
        subgraph "Resource Controls"
            FileSB[TenantFileSandbox]
            ProcessSB[ProcessSandbox]
            NetworkSB[NetworkSandbox]
            Storage[StorageQuotaEnforcer]
            Threads[TenantThreadPool]
        end
        subgraph "Security & Audit"
            Policy[TenantSecurityPolicy]
            Quota[TenantQuota]
            Audit[TenantAuditLogger]
        end
    end

    Agent --> Model
    Agent --> Registry
    Registry --> Code & Terminal & Git & File & WebSearch & Browser & MCP & SubAgent
    Code & Terminal & Git & File & WebSearch & Browser & MCP & SubAgent --> Context
    Context --> FileSB & ProcessSB & NetworkSB & Storage & Threads
    Context --> Policy & Quota & Audit
```

### 4.2 配置继承关系

```mermaid
graph BT
    Default[default-config.json<br/>系统默认值 + 安全基线]
    Tenant[tenant-config.json<br/>租户自定义（继承+覆盖）]
    Session[session-config<br/>运行时临时覆盖]
    
    Default -.->|继承| Tenant
    Tenant -.->|继承| Session
    
    Session --> ProcessConfig[ProcessSandboxConfig]
    Session --> NetworkConfig[NetworkPolicy]
    Session --> QuotaConfig[TenantQuota]
    Session --> SecurityConfig[TenantSecurityPolicy]
    Tenant --> ProcessConfig & NetworkConfig & QuotaConfig & SecurityConfig
```

配置优先级：**默认 < 租户 < 会话（运行时）**，高优先级覆盖低优先级。

---

## 五、部署架构

```mermaid
graph TB
    subgraph "Client Layer"
        WebUI[Web UI]
        CLI[CLI Client]
        API[API Client]
    end

    subgraph "Load Balancer"
        LB[Nginx/ALB<br/>SSL终止 + 负载均衡]
    end

    subgraph "Hermes Cluster"
        Node1[Node 1<br/>Gateway + TenantManager + Agent]
        Node2[Node 2<br/>Gateway + TenantManager + Agent]
        Node3[Node 3<br/>Gateway + TenantManager + Agent]
    end

    subgraph "Shared Storage"
        NFS[NFS/EFS<br/>租户文件持久化]
        Redis[Redis<br/>会话缓存 + 限流计数]
        DB[(PostgreSQL<br/>租户元数据 + 审计)]
    end

    subgraph "External Services"
        LLM[LLM API<br/>OpenAI/Anthropic]
        Search[Search APIs<br/>Brave/Tavily]
        MCP[MCP Servers]
    end

    subgraph "Monitoring"
        Prom[Prometheus<br/>指标采集]
        Grafana[Grafana<br/>可视化]
    end

    WebUI & CLI & API --> LB --> Node1 & Node2 & Node3
    Node1 & Node2 & Node3 --> NFS & Redis & DB
    Node1 & Node2 & Node3 --> LLM & Search & MCP
    Node1 & Node2 & Node3 --> Prom --> Grafana
```

**最小部署形态（单机）：** JAR + systemd / Docker-compose + 文件存储 + SQLite 可选。

---

## 六、数据流

### 6.1 请求处理数据流

```mermaid
flowchart LR
    A[用户请求] --> B{消息平台}
    B -->|各平台Adapter| G[GatewayServer]
    G --> H[TenantAuthFilter]
    H -->|认证通过| I[HermesAgentV2]
    H -->|失败| J[401]
    
    I --> K{请求类型}
    K -->|聊天| L[ModelClient] --> N[TransportFactory] --> O[LLM API]
    K -->|工具调用| M[ToolRegistry] --> P[工具执行]
    
    P --> Q[TenantContext] --> R[沙箱检查]
    R -->|通过| S[实际执行] --> U[AuditLogger]
    R -->|拒绝| T[返回错误]
    
    O --> I
    S --> I
    I --> G --> B --> W[返回用户]
```

---

## 七、核心类关系

```mermaid
classDiagram
    class HermesAgentV2 {
        -ModelClient modelClient
        -ToolRegistry toolRegistry
        -TenantManager tenantManager
        +processMessage(Message)
        +executeTool(ToolCall)
    }
    class TenantManager {
        -Map~String,TenantContext~ tenants
        +provisionTenant(request)
        +getTenant(tenantId)
        +destroyTenant(tenantId)
    }
    class TenantContext {
        -String tenantId
        -TenantConfig config
        -TenantFileSandbox fileSandbox
        -ProcessSandbox processSandbox
        -NetworkSandbox networkSandbox
        -StorageQuotaEnforcer storageQuota
        -TenantThreadPool threadPool
        +exec(command, options)
        +httpGet(url)
        +writeFile(path, data)
        +submit(task)
    }
    class ToolRegistry {
        -Map~String,ToolEntry~ tools
        +register(tool)
        +getTool(name)
        +execute(name, args, context)
    }
    class ProcessSandbox {
        -ProcessSandboxConfig config
        +exec(command, options)
        -isCommandAllowed(command)
    }
    class NetworkSandbox {
        -NetworkPolicy policy
        -RateLimiter rateLimiter
        +get(url) + post(url, body)
        -isHostAllowed(host)
    }
    class TenantFileSandbox {
        -Path sandboxRoot
        +readFile(path) + writeFile(path, data)
        -resolvePath(path)
    }
    class StorageQuotaEnforcer {
        -TenantQuota quota
        +canWrite(bytes) + writeFile(path, data)
    }
    class TenantThreadPool {
        -ThreadPoolExecutor executor
        +submit(task) + getStatistics()
    }

    HermesAgentV2 --> TenantManager
    HermesAgentV2 --> ToolRegistry
    TenantManager --> TenantContext
    TenantContext --> ProcessSandbox
    TenantContext --> NetworkSandbox
    TenantContext --> TenantFileSandbox
    TenantContext --> StorageQuotaEnforcer
    TenantContext --> TenantThreadPool
```

---

## 八、状态机

### 8.1 租户生命周期

```mermaid
stateDiagram-v2
    [*] --> Provisioning: 创建请求
    Provisioning --> Active: 初始化完成
    Provisioning --> Failed: 初始化失败
    Active --> Suspended: 暂停/欠费
    Active --> Terminating: 删除请求
    Suspended --> Active: 恢复/缴费
    Suspended --> Terminating: 强制删除
    Terminating --> Terminated: 清理完成
    Failed --> Terminating: 重试失败
    Terminated --> [*]
```

### 8.2 工具执行状态机

```mermaid
stateDiagram-v2
    [*] --> Validating: 接收调用
    Validating --> Rejected: 权限检查失败
    Validating --> QuotaCheck: 权限通过
    QuotaCheck --> Rejected: 配额超限
    QuotaCheck --> SandboxCheck: 配额充足
    SandboxCheck --> Rejected: 沙箱检查失败
    SandboxCheck --> Executing: 通过
    Executing --> Succeeded: 成功
    Executing --> Failed: 错误
    Executing --> TimedOut: 超时
    Succeeded & Failed & TimedOut & Rejected --> Logging: 记录
    Logging --> [*]
```

执行路径上的每一次拒绝都进入审计日志。

---

## 九、持久化（Persistence）

Hermes Agent Java 使用分层持久化策略存储租户状态、会话、配额使用、轨迹和审计数据。

### 9.1 存储后端

`TenantStateRepository` 定义存储契约，已实现两种后端：

| 后端 | 实现类 | 适用场景 |
|---|---|---|
| PostgreSQL | `PostgresTenantRepository` | 生产环境、多节点部署 |
| 文件系统 | `FileSystemTenantRepository` | 本地开发、嵌入式部署、单节点 |

### 9.2 文件系统安全保障

文件系统后端采用两步数据安全链保护 JSON 状态文件：

1. **临时文件写入** → 先写 `*.tmp`
2. **原子替换** → 支持的文件系统用 `ATOMIC_MOVE`，否则 `REPLACE_EXISTING`
3. **备份保留** → 替换前复制旧版本到 `*.bak`
4. **备份恢复** → 加载时主文件缺失/损坏则回退到 `.bak`

目录布局：
```text
~/.hermes/persistence/
├── tenants/{tenantId}/
│   ├── state.json
│   └── state.json.bak
└── sessions/{tenantId}/
    ├── {sessionId}.json
    └── {sessionId}.json.bak
```

### 9.3 会话持久化

`SessionSerializer` / `JsonSessionSerializer` 序列化会话上下文，包含：session id、tenant id、node id、时间戳、metadata、active 标记、消息列表。

### 9.4 配额使用持久化

当前使用量：`~/.hermes/tenants/{tenantId}/state/usage.json`
历史归档（按天）：`~/.hermes/tenants/{tenantId}/state/history/YYYY-MM-DD.json`

### 9.5 轨迹持久化

```text
~/.hermes/trajectories/
├── trajectory_samples.jsonl
├── failed_trajectories.jsonl
├── compressed/{trajectoryId}.json
└── insights.jsonl
```

---

## 十、监控（Monitoring）

### 10.1 指标暴露

通过 `MetricsCollector` 和 `TenantMetrics` 以 Prometheus 文本格式暴露租户和系统指标。

**租户级指标：**
- 内存 used / max / usage percent
- 网络请求总数与被拦截数、QPS
- 活跃 Agent 数与会话数
- 存储使用量与配额、文件数
- 活跃进程数
- 近 1 小时审计事件数
- 配额告警/超限标记、租户状态

**告警投递指标：**
```text
hermes_alerts_fired_total
hermes_alerts_suppressed_total
hermes_alert_deliveries_succeeded_total
hermes_alert_deliveries_failed_total
hermes_alert_channel_deliveries_succeeded_total{channel="..."}
hermes_alert_channel_deliveries_failed_total{channel="..."}
```

### 10.2 告警通道

| 通道 | 实现类 | 说明 |
|---|---|---|
| Email | `EmailAlertChannel` | SMTP |
| Webhook | `WebhookAlertChannel` | 钉钉、飞书、Slack、Discord、通用 JSON |

配置通过环境变量：
```bash
export ALERT_WEBHOOK_URL="https://..."
export ALERT_EMAIL_SENDER="bot@example.com"
export ALERT_EMAIL_RECIPIENT="ops@example.com"
export ALERT_SMTP_HOST="smtp.example.com"
export ALERT_SMTP_PORT="465"
export ALERT_EMAIL_SSL="true"
```

### 10.3 告警冷却

每个 `tenant:type` 有 5 分钟冷却，防止告警风暴。被抑制的告警计入 `hermes_alerts_suppressed_total`。

### 10.4 建议的 Prometheus 告警规则

```yaml
groups:
  - name: hermes
    rules:
      - alert: HermesTenantMemoryHigh
        expr: hermes_tenant_memory_usage_percent > 0.8
        for: 5m
        labels: { severity: warning }

      - alert: HermesAlertDeliveryFailing
        expr: increase(hermes_alert_deliveries_failed_total[10m]) > 0
        for: 1m
        labels: { severity: warning }
```

---

## 十一、Gateway 服务模式

Hermes Gateway 支持轻量级服务模式，基于 PID 文件管理进程。

### 11.1 PID 文件

默认位置：`~/.hermes/gateway.pid`，用于检测是否已有网关进程运行，以及后续停止服务。

### 11.2 启动与停止

```bash
# 启动（前台运行，记录 PID）
java -jar target/hermes-agent-java-*.jar gateway start

# 停止（读取 PID 发终止信号，超时强制销毁）
java -jar target/hermes-agent-java-*.jar gateway stop
```

### 11.3 生产部署建议

生产环境建议使用 systemd / Docker / Kubernetes 等进程管理器。服务模式提供 PID 记账，但本身不是完整的守护进程监督器。

**systemd 示例：**
```ini
[Unit]
Description=Hermes Agent Java Gateway
After=network.target

[Service]
Type=simple
User=hermes
WorkingDirectory=/opt/hermes-agent-java
ExecStart=/usr/bin/java -jar hermes-agent-java.jar gateway start
ExecStop=/usr/bin/java -jar hermes-agent-java.jar gateway stop
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

**行为说明：**
- JVM 正常退出时 PID 文件自动移除
- 进程被异常杀死时，下次启动检测 PID 是否存活，自动清理陈旧文件
- 网关优雅关闭时会停止适配器、API 服务、Dashboard 和租户管理器

---

*版本: v1.2（含持久化、监控、Gateway）*
