# Hermes Agent Java - 完整架构图

> 本文档包含系统的整体架构、租户隔离机制和关键业务流程的完整可视化

---

## 一、整体系统架构图

```mermaid
graph TB
    subgraph "External Layer"
        Users[用户/客户端]
        Platforms[消息平台<br/>Discord/Telegram/Feishu/QQ]
        ExternalAPIs[外部API<br/>OpenAI/Anthropic/Brave]
    end

    subgraph "Gateway Layer"
        Gateway[GatewayServer<br/>HTTP WebSocket服务]
        AuthFilter[TenantAuthFilter<br/>租户认证过滤器]
        RateLimiter[API Rate Limiter<br/>租户级限流]
    end

    subgraph "Core Engine"
        Agent[HermesAgentV2<br/>核心Agent引擎]
        ModelClient[ModelClient<br/>模型客户端]
        Transport[TransportFactory<br/>传输层适配]
        
        subgraph "Transport Adapters"
            Anthropic[AnthropicTransport]
            Bedrock[BedrockTransport]
            ChatGPT[ChatCompletionsTransport]
            Codex[CodexTransport]
        end
    end

    subgraph "Tool System"
        ToolRegistry[ToolRegistry<br/>工具注册中心]
        ToolInit[ToolInitializerV2<br/>工具初始化器]
        
        subgraph "Built-in Tools"
            FileTool[FileTool<br/>文件操作]
            CodeTool[CodeTool<br/>代码执行]
            Browser[BrowserToolV2<br/>浏览器控制]
            WebSearch[WebSearchToolV2<br/>网络搜索]
            Terminal[TerminalTool<br/>终端执行]
            GitTool[GitTool<br/>版本控制]
            MCPTool[MCPTool<br/>MCP协议]
            SubAgent[SubAgentTool<br/>子Agent]
        end
        
        subgraph "Platform Tools"
            Feishu[FeishuDocTool<br/>飞书文档]
            Discord[DiscordTool<br/>Discord]
            QQBot[QQBotAdapter<br/>QQ机器人]
        end
    end

    subgraph "Multi-Tenant System"
        TenantMgr[TenantManager<br/>租户管理器]
        TenantCtx[TenantContext<br/>租户上下文]
        
        subgraph "Tenant Isolation"
            FileSandbox[TenantFileSandbox<br/>文件沙箱]
            ProcessSandbox[ProcessSandbox<br/>进程沙箱]
            NetworkSandbox[NetworkSandbox<br/>网络沙箱]
            StorageQuota[StorageQuotaEnforcer<br/>存储配额]
            ThreadPool[TenantThreadPool<br/>线程池隔离]
        end
        
        subgraph "Tenant Management"
            Config[TenantConfig<br/>租户配置]
            Quota[TenantQuota<br/>资源配额]
            Security[TenantSecurityPolicy<br/>安全策略]
            Audit[TenantAuditLogger<br/>审计日志]
            SessionMgr[TenantSessionManager<br/>会话管理]
            MemoryMgr[TenantMemoryManager<br/>内存管理]
        end
    end

    subgraph "Storage Layer"
        LocalFS[本地文件系统<br/>sandbox/{tenantId}/]
        GitRepos[Git仓库]
        MCPServers[MCP Servers]
    end

    %% Connections
    Users -->|HTTP/WebSocket| Gateway
    Platforms -->|Webhook| Gateway
    
    Gateway --> AuthFilter
    AuthFilter --> RateLimiter
    RateLimiter --> Agent
    
    Agent --> ModelClient
    ModelClient --> Transport
    Transport --> Anthropic & Bedrock & ChatGPT & Codex
    Transport --> ExternalAPIs
    
    Agent --> ToolRegistry
    ToolRegistry --> ToolInit
    ToolInit --> FileTool & CodeTool & Browser & WebSearch & Terminal & GitTool & MCPTool & SubAgent
    ToolInit --> Feishu & Discord & QQBot
    
    Agent --> TenantMgr
    TenantMgr --> TenantCtx
    TenantCtx --> FileSandbox & ProcessSandbox & NetworkSandbox & StorageQuota & ThreadPool
    TenantCtx --> Config & Quota & Security & Audit & SessionMgr & MemoryMgr
    
    FileSandbox --> LocalFS
    ProcessSandbox --> LocalFS
    Terminal --> LocalFS
    GitTool --> GitRepos
    MCPTool --> MCPServers
```

---

## 二、租户隔离架构图

### 2.1 多层隔离模型

```mermaid
graph TB
    subgraph "Physical Layer"
        JVM[JVM进程]
        OS[操作系统]
        Hardware[硬件资源]
    end

    subgraph "Tenant Isolation Layer"
        direction TB
        
        subgraph "Tenant A"
            TA_Config[独立配置]
            TA_FS[文件沙箱<br/>/sandbox/tenant-a/]
            TA_Process[进程沙箱<br/>资源限制]
            TA_Network[网络沙箱<br/>白名单控制]
            TA_Thread[线程池<br/>10 threads max]
            TA_Storage[存储配额<br/>1GB limit]
            TA_Memory[内存隔离<br/>256MB limit]
        end
        
        subgraph "Tenant B"
            TB_Config[独立配置]
            TB_FS[文件沙箱<br/>/sandbox/tenant-b/]
            TB_Process[进程沙箱<br/>资源限制]
            TB_Network[网络沙箱<br/>白名单控制]
            TB_Thread[线程池<br/>10 threads max]
            TB_Storage[存储配额<br/>1GB limit]
            TB_Memory[内存隔离<br/>256MB limit]
        end
        
        subgraph "Tenant C"
            TC_Config[独立配置]
            TC_FS[文件沙箱<br/>/sandbox/tenant-c/]
            TC_Process[进程沙箱<br/>资源限制]
            TC_Network[网络沙箱<br/>白名单控制]
            TC_Thread[线程池<br/>10 threads max]
            TC_Storage[存储配额<br/>1GB limit]
            TC_Memory[内存隔离<br/>256MB limit]
        end
    end

    subgraph "Shared Resources"
        Shared_Model[模型客户端<br/>共享连接池]
        Shared_Tool[工具注册表<br/>只读共享]
        Shared_Platform[平台适配器<br/>多路复用]
    end

    %% Isolation boundaries
    TA_FS -.->|完全隔离| TB_FS
    TA_FS -.->|完全隔离| TC_FS
    TA_Process -.->|cgroups隔离| TB_Process
    TA_Network -.->|独立策略| TB_Network
    
    %% Resource usage
    TenantA -->|使用| TA_FS & TA_Process & TA_Network & TA_Thread & TA_Storage & TA_Memory
    TenantB -->|使用| TB_FS & TB_Process & TB_Network & TB_Thread & TB_Storage & TB_Memory
    TenantC -->|使用| TC_FS & TC_Process & TC_Network & TC_Thread & TC_Storage & TC_Memory
    
    %% Shared access
    TenantA & TenantB & TenantC -->|共享访问| Shared_Model & Shared_Tool & Shared_Platform
```

### 2.2 资源沙箱详细架构

```mermaid
graph LR
    subgraph "Resource Sandbox Architecture"
        Core[TenantContext<br/>租户上下文]
        
        subgraph "Sandbox Components"
            direction TB
            
            FileSB[File Sandbox
            - 路径限制
            - 权限控制
            - 审计日志]
            
            ProcessSB[Process Sandbox
            - 命令白名单
            - 超时控制
            - 内存限制
            - 环境清理]
            
            NetworkSB[Network Sandbox
            - URL白名单
            - 速率限制
            - 协议限制
            - 连接超时]
            
            StorageSB[Storage Quota
            - 写入检查
            - 流式追踪
            - 定期扫描
            - 告警机制]
            
            ThreadSB[Thread Pool
            - 有界队列
            - 线程命名
            - 统计监控
            - 优雅关闭]
        end
        
        subgraph "Configuration"
            Config[TenantConfig
            - config.json]
            
            Policy[SecurityPolicy
            - 安全策略]
            
            Quota[Quota Config
            - 资源配额]
        end
        
        subgraph "Monitoring"
            Metrics[TenantMetrics
            - 资源使用指标]
            
            Audit[TenantAuditLogger
            - 操作审计]
            
            Monitor[TenantResourceMonitor
            - 实时监控]
        end
    end

    Core --> FileSB & ProcessSB & NetworkSB & StorageSB & ThreadSB
    Config & Policy & Quota --> Core
    FileSB & ProcessSB & NetworkSB & StorageSB & ThreadSB --> Metrics
    Core --> Audit
    Metrics --> Monitor
```

---

## 三、关键逻辑链路时序图

### 3.1 租户创建流程

```mermaid
sequenceDiagram
    autonumber
    participant Client as 客户端
    participant Gateway as GatewayServer
    participant Auth as TenantAuthFilter
    participant TenantMgr as TenantManager
    participant Context as TenantContext
    participant FileSB as TenantFileSandbox
    participant ProcessSB as ProcessSandbox
    participant NetworkSB as NetworkSandbox
    participant Storage as StorageQuotaEnforcer
    participant ThreadPool as TenantThreadPool
    participant Config as TenantConfig
    participant Audit as TenantAuditLogger

    Client->>Gateway: POST /api/tenants<br/>TenantProvisioningRequest
    
    Gateway->>Auth: 验证管理员Token
    Auth-->>Gateway: 验证通过
    
    Gateway->>TenantMgr: provisionTenant(request)
    
    TenantMgr->>Config: 创建配置目录<br/>/tenants/{tenantId}/
    Config-->>TenantMgr: 配置已创建
    
    TenantMgr->>Context: new TenantContext(request)
    
    par 初始化各沙箱组件
        Context->>FileSB: 初始化文件沙箱<br/>sandboxRoot={tenantDir}
        FileSB-->>Context: 文件沙箱就绪
        
        Context->>ProcessSB: 初始化进程沙箱<br/>commandWhitelist/blacklist
        ProcessSB-->>Context: 进程沙箱就绪
        
        Context->>NetworkSB: 初始化网络沙箱<br/>hostWhitelist/blacklist
        NetworkSB-->>Context: 网络沙箱就绪
        
        Context->>Storage: 初始化存储配额<br/>maxStorageBytes
        Storage-->>Context: 存储配额就绪
        
        Context->>ThreadPool: 初始化线程池<br/>coreThreads/maxThreads
        ThreadPool-->>Context: 线程池就绪
    end
    
    Context-->>TenantMgr: TenantContext已创建
    
    TenantMgr->>Audit: 记录租户创建日志
    
    TenantMgr-->>Gateway: Tenant实例
    Gateway-->>Client: 201 Created<br/>TenantResponse
```

### 3.2 工具执行流程（带资源限制）

```mermaid
sequenceDiagram
    autonumber
    participant Agent as HermesAgentV2
    participant ToolReg as ToolRegistry
    participant Tool as Tool实现类
    participant TenantCtx as TenantContext
    participant Auth as 权限检查
    participant Quota as 配额检查
    participant Sandbox as 沙箱执行
    participant Audit as AuditLogger

    Agent->>ToolReg: 解析工具调用<br/>executeTool(toolCall)
    ToolReg-->>Agent: Tool实例
    
    Agent->>Tool: execute(args, context)
    
    Tool->>TenantCtx: 获取租户上下文
    TenantCtx-->>Tool: TenantContext
    
    Tool->>Auth: 检查权限<br/>securityPolicy.allowXXX()
    
    alt 权限检查失败
        Auth-->>Tool: SecurityException
        Tool-->>Agent: ToolResult.error
    else 权限检查通过
        Auth-->>Tool: 允许执行
        
        Tool->>Quota: 检查配额<br/>quotaManager.checkQuota()
        
        alt 配额超限
            Quota-->>Tool: QuotaExceededException
            Tool-->>Agent: ToolResult.error
        else 配额充足
            Quota-->>Tool: 配额充足
            
            Tool->>Sandbox: 在沙箱中执行
            
            alt 需要文件操作
                Sandbox->>Sandbox: TenantFileSandbox<br/>检查路径合法性
            end
            
            alt 需要执行命令
                Sandbox->>Sandbox: ProcessSandbox<br/>检查命令白名单+超时控制
            end
            
            alt 需要网络请求
                Sandbox->>Sandbox: NetworkSandbox<br/>检查URL白名单+速率限制
            end
            
            alt 需要存储写入
                Sandbox->>Sandbox: StorageQuotaEnforcer<br/>检查存储配额
            end
            
            Sandbox-->>Tool: 执行结果
            
            Tool->>Audit: 记录操作日志
            Tool-->>Agent: ToolResult.success
        end
    end
```

### 3.3 进程沙箱执行流程

```mermaid
sequenceDiagram
    autonumber
    participant Caller as 调用方
    participant ProcessSB as ProcessSandbox
    participant Config as ProcessSandboxConfig
    participant Validator as 命令验证器
    participant Timeout as Timeout包装器
    subprocess Linux系统
        participant Process as ProcessBuilder
        participant cgroups as Linux cgroups
        participant Signal as SIGTERM信号
    end
    participant Result as ProcessResult

    Caller->>ProcessSB: exec(command, options)
    
    ProcessSB->>Config: 获取配置
    Config-->>ProcessSB: whitelist/blacklist<br/>workDirectory
    
    ProcessSB->>Validator: 验证命令
    Validator->>Validator: 提取命令名称
    Validator->>Validator: 检查黑名单
    
    alt 命中黑名单
        Validator-->>ProcessSB: 拒绝执行
        ProcessSB-->>Caller: ProcessSandboxException
    else 通过黑名单检查
        Validator->>Validator: 检查白名单
        
        alt 白名单非空且未命中
            Validator-->>ProcessSB: 拒绝执行
            ProcessSB-->>Caller: ProcessSandboxException
        else 通过白名单检查
            Validator-->>ProcessSB: 命令验证通过
            
            ProcessSB->>Timeout: 包装超时控制
            
            alt Linux系统且设置了超时
                Timeout->>Timeout: 添加timeout命令<br/>timeout -s SIGTERM {seconds}
            end
            
            Timeout->>Process: 创建进程
            Process->>Process: 设置工作目录
            Process->>Process: 清理环境变量
            
            alt Linux且有cgroups配置
                Process->>cgroups: 写入cgroup限制<br/>memory/cpu限制
            end
            
            Process->>Process: 启动进程
            
            alt 设置了超时
                Process->>Signal: 超时后发送SIGTERM
                Signal->>Process: 终止进程
            end
            
            Process-->>Timeout: 进程结束
            Timeout-->>ProcessSB: 返回结果
            
            ProcessSB->>Result: 封装结果
            Result-->>ProcessSB: ProcessResult
            ProcessSB-->>Caller: 返回结果
        end
    end
```

### 3.4 网络沙箱请求流程

```mermaid
sequenceDiagram
    autonumber
    participant Caller as 调用方
    participant NetworkSB as NetworkSandbox
    participant Policy as NetworkPolicy
    participant Matcher as URL匹配器
    participant RateLimit as RateLimiter
    participant HttpClient as HttpClient
    participant Target as 目标服务器

    Caller->>NetworkSB: get/post(url, body)
    
    NetworkSB->>Policy: 获取网络策略
    Policy-->>NetworkSB: whitelist/blacklist<br/>maxRequestsPerSecond
    
    NetworkSB->>NetworkSB: 解析URL<br/>提取协议/主机/端口
    
    alt 协议检查失败
        NetworkSB-->>Caller: NetworkSandboxException<br/>协议不允许
    else 协议检查通过
        NetworkSB->>Matcher: 匹配主机
        Matcher->>Matcher: 检查黑名单<br/>localhost, 127.0.0.*, 10.*.*.*
        
        alt 命中黑名单
            Matcher-->>NetworkSB: 拒绝访问
            NetworkSB-->>Caller: NetworkSandboxException<br/>访问被拒绝
        else 通过黑名单检查
            Matcher->>Matcher: 检查白名单<br/>*.github.com, *.openai.com
            
            alt 白名单非空且未命中
                Matcher-->>NetworkSB: 拒绝访问
                NetworkSB-->>Caller: NetworkSandboxException<br/>不在白名单
            else 通过白名单检查
                Matcher-->>NetworkSB: 允许访问
                
                NetworkSB->>RateLimit: 检查速率限制
                
                alt 超过速率限制
                    RateLimit-->>NetworkSB: 拒绝
                    NetworkSB-->>Caller: NetworkSandboxException<br/>请求过于频繁
                else 速率检查通过
                    RateLimit-->>NetworkSB: 允许
                    RateLimit->>RateLimit: 计数器+1
                    
                    NetworkSB->>HttpClient: 发送HTTP请求
                    HttpClient->>Target: HTTP Request
                    Target-->>HttpClient: HTTP Response
                    HttpClient-->>NetworkSB: HttpResponse
                    NetworkSB-->>Caller: 返回响应
                end
            end
        end
    end
```

### 3.5 存储配额检查流程

```mermaid
sequenceDiagram
    autonumber
    participant Caller as 调用方
    participant Storage as StorageQuotaEnforcer
    participant Quota as QuotaConfig
    participant Monitor as 使用量监控
    participant FileSystem as 文件系统

    Caller->>Storage: writeFile(path, data)
    
    Storage->>Quota: 获取配额配置
    Quota-->>Storage: maxStorageBytes
    
    Storage->>Storage: 计算数据大小<br/>data.length
    
    Storage->>Monitor: 获取当前使用量
    Monitor->>FileSystem: 扫描租户目录
    FileSystem-->>Monitor: 当前使用大小
    Monitor-->>Storage: currentUsage
    
    Storage->>Storage: 计算写入后总量<br/>currentUsage + newSize
    
    alt 超出配额
        Storage-->>Caller: QuotaExceededException<br/>存储空间不足
    else 未超出配额
        Storage->>FileSystem: 写入文件
        FileSystem-->>Storage: 写入成功
        
        Storage->>Monitor: 更新使用量<br/>currentUsage += newSize
        
        alt 使用量接近配额阈值(90%)
            Monitor->>Monitor: 触发告警
        end
        
        Storage-->>Caller: 写入成功
    end

    %% 流式写入场景
    Note over Caller,FileSystem: 流式写入场景
    
    Caller->>Storage: createOutputStream(path, expectedSize)
    
    Storage->>Quota: 检查预期大小
    Quota-->>Storage: 配额充足
    
    Storage-->>Caller: OutputStream包装器
    
    loop 数据写入
        Caller->>Storage: write(chunk)
        Storage->>Monitor: 追踪写入量
        Monitor->>FileSystem: 实际写入
    end
    
    Caller->>Storage: close()
    Storage->>Monitor: 最终使用量更新
    Storage-->>Caller: 完成
```

---

## 四、组件关系图

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

    subgraph "Physical Resources"
        FS[(文件系统)]
        Network[(网络)]
        Processes[(子进程)]
    end

    %% Connections
    Agent --> Model
    Agent --> Registry
    
    Registry --> Code & Terminal & Git & File & WebSearch & Browser & MCP & SubAgent
    
    Code --> Context
    Terminal --> Context
    Git --> Context
    File --> Context
    WebSearch --> Context
    Browser --> Context
    MCP --> Context
    SubAgent --> Context
    
    Context --> FileSB & ProcessSB & NetworkSB & Storage & Threads
    Context --> Policy & Quota & Audit
    
    FileSB --> FS
    ProcessSB --> Processes
    Terminal --> Processes
    Storage --> FS
    NetworkSB --> Network
    WebSearch --> Network
    Browser --> Network
```

### 4.2 配置继承与覆盖关系

```mermaid
graph BT
    subgraph "Default Configuration"
        Default[default-config.json
        - 系统默认值
        - 安全基线]
    end

    subgraph "Tenant Configuration"
        Tenant[tenant-config.json
        - 租户自定义
        - 继承+覆盖]
    end

    subgraph "Session Configuration"
        Session[session-config
        - 运行时动态
        - 临时覆盖]
    end

    subgraph "Effective Configuration"
        Process[ProcessSandboxConfig
        - commandWhitelist
        - timeoutSeconds]
        
        Network[NetworkPolicy
        - hostWhitelist
        - rateLimit]
        
        Quota[TenantQuota
        - maxMemoryBytes
        - maxStorageBytes]
        
        Security[TenantSecurityPolicy
        - allowNetwork
        - allowCodeExecution]
    end

    Default -.->|继承| Tenant
    Tenant -.->|继承| Session
    
    Session --> Process
    Session --> Network
    Session --> Quota
    Session --> Security
    
    Tenant --> Process
    Tenant --> Network
    Tenant --> Quota
    Tenant --> Security
```

---

## 五、部署架构图

```mermaid
graph TB
    subgraph "Client Layer"
        WebUI[Web UI]
        CLI[CLI Client]
        Mobile[Mobile App]
    end

    subgraph "Load Balancer"
        LB[Nginx/ALB
        - SSL终止
        - 负载均衡
        - 静态资源缓存]
    end

    subgraph "Hermes Agent Cluster"
        Node1[Hermes Node 1
        - GatewayServer
        - TenantManager
        - Agent Engine]
        
        Node2[Hermes Node 2
        - GatewayServer
        - TenantManager
        - Agent Engine]
        
        Node3[Hermes Node 3
        - GatewayServer
        - TenantManager
        - Agent Engine]
    end

    subgraph "Shared Storage"
        NFS[NFS/EFS
        - 租户文件持久化
        - /sandbox/{tenantId}/]
        
        Redis[Redis Cluster
        - 会话缓存
        - 速率限制计数]
        
        DB[(PostgreSQL
        - 租户元数据
        - 审计日志)]
    end

    subgraph "External Services"
        OpenAI[OpenAI API]
        Anthropic[Anthropic API]
        Search[Search APIs
        - Brave/Tavily]
        MCP[MCP Servers]
    end

    subgraph "Monitoring"
        Prometheus[Prometheus
        - 指标采集]
        Grafana[Grafana
        - 可视化面板]
        ELK[ELK Stack
        - 日志分析]
    end

    WebUI & CLI & Mobile --> LB
    LB --> Node1 & Node2 & Node3
    
    Node1 & Node2 & Node3 --> NFS
    Node1 & Node2 & Node3 --> Redis
    Node1 & Node2 & Node3 --> DB
    
    Node1 & Node2 & Node3 --> OpenAI & Anthropic & Search & MCP
    
    Node1 & Node2 & Node3 --> Prometheus
    Prometheus --> Grafana
    Node1 & Node2 & Node3 --> ELK
```

---

## 六、数据流图

### 6.1 请求处理数据流

```mermaid
flowchart LR
    A[用户请求] --> B{消息平台}
    B -->|Discord| C[DiscordAdapter]
    B -->|Telegram| D[TelegramAdapter]
    B -->|Feishu| E[FeishuAdapter]
    B -->|QQ| F[QQBotAdapter]
    
    C & D & E & F --> G[GatewayServer]
    G --> H[TenantAuthFilter]
    H -->|认证通过| I[HermesAgentV2]
    H -->|认证失败| J[返回401错误]
    
    I --> K{请求类型}
    K -->|聊天| L[ModelClient]
    K -->|工具调用| M[ToolRegistry]
    
    L --> N[TransportFactory]
    N --> O[LLM API]
    
    M --> P[Tool执行]
    P --> Q[TenantContext]
    Q --> R[沙箱检查]
    R -->|通过| S[实际执行]
    R -->|拒绝| T[返回错误]
    
    S --> U[AuditLogger]
    S --> V[返回结果]
    
    O --> I
    V --> I
    I --> G
    G --> B
    B --> W[返回给用户]
```

---

## 七、类图

### 7.1 核心类关系图

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
        -ConcurrentHashMap~String,TenantContext~ tenants
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

    class ToolEntry {
        -String name
        -String description
        -Function~args,result~ executor
        +execute(args, context)
    }

    class ProcessSandbox {
        -ProcessSandboxConfig config
        -TenantContext context
        +exec(command, options)
        -isCommandAllowed(command)
        -sanitizeEnvironment(env)
    }

    class NetworkSandbox {
        -NetworkPolicy policy
        -RateLimiter rateLimiter
        +get(url)
        +post(url, body)
        -isHostAllowed(host)
    }

    class TenantFileSandbox {
        -Path sandboxRoot
        +readFile(path)
        +writeFile(path, data)
        -resolvePath(path)
    }

    class StorageQuotaEnforcer {
        -TenantQuota quota
        -Path tenantDirectory
        +writeFile(path, data)
        +createOutputStream(path, size)
        +canWrite(bytes)
    }

    class TenantThreadPool {
        -String tenantId
        -ThreadPoolExecutor executor
        +submit(task)
        +getStatistics()
    }

    class ProcessSandboxConfig {
        -Set~String~ commandWhitelist
        -Set~String~ commandBlacklist
        -Path workDirectory
        -int defaultTimeoutSeconds
    }

    class NetworkPolicy {
        -Set~Pattern~ hostWhitelist
        -Set~Pattern~ hostBlacklist
        -int maxRequestsPerSecond
        -int connectTimeoutSeconds
    }

    class TenantQuota {
        -long maxMemoryBytes
        -long maxStorageBytes
        -int maxRequestsPerMinute
    }

    HermesAgentV2 --> TenantManager
    HermesAgentV2 --> ToolRegistry
    TenantManager --> TenantContext
    ToolRegistry --> ToolEntry
    TenantContext --> ProcessSandbox
    TenantContext --> NetworkSandbox
    TenantContext --> TenantFileSandbox
    TenantContext --> StorageQuotaEnforcer
    TenantContext --> TenantThreadPool
    ProcessSandbox --> ProcessSandboxConfig
    NetworkSandbox --> NetworkPolicy
    TenantContext --> TenantQuota
```

---

## 八、状态机图

### 8.1 租户生命周期状态机

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
    
    note right of Active
        正常运行状态
        - 可以执行工具
        - 受配额限制
        - 记录审计日志
    end note
    
    note right of Suspended
        暂停状态
        - 只读访问
        - 禁止执行
        - 保留数据
    end note
```

### 8.2 工具执行状态机

```mermaid
stateDiagram-v2
    [*] --> Validating: 接收调用
    
    Validating --> Rejected: 权限检查失败
    Validating --> QuotaCheck: 权限检查通过
    
    QuotaCheck --> Rejected: 配额超限
    QuotaCheck --> SandboxCheck: 配额充足
    
    SandboxCheck --> Rejected: 沙箱检查失败
    SandboxCheck --> Executing: 沙箱检查通过
    
    Executing --> Succeeded: 执行成功
    Executing --> Failed: 执行错误
    Executing --> TimedOut: 超时
    
    Succeeded --> Logging: 记录结果
    Failed --> Logging: 记录错误
    TimedOut --> Logging: 记录超时
    Rejected --> Logging: 记录拒绝
    
    Logging --> [*]
```

---

## 九、下一步迭代规划建议

基于架构图分析，建议按以下优先级进行迭代：

### Phase 1: 基础安全加固（已完成✅）
- ✅ 文件沙箱隔离
- ✅ 进程沙箱（命令白名单、超时控制）
- ✅ 网络沙箱（URL白名单、速率限制）
- ✅ 存储配额强制执行

### Phase 2: 高级隔离（建议1-2周内完成）
- 🔄 Linux cgroups 集成（CPU/内存/PID限制）
- 🔄 网络代理模式（透明拦截所有出站连接）
- 🔄 JVM 内存隔离（ByteBuffer分配池）

### Phase 3: 可观测性（建议2-3周内完成）
- 📊 JMX指标暴露（MBean）
- 📊 Prometheus集成
- 📊 Grafana仪表板
- 📊 实时资源监控告警

### Phase 4: 高可用性（建议1个月内完成）
- 🔄 租户状态持久化（数据库）
- 🔄 分布式会话管理
- 🔄 优雅重启/热升级
- 🔄 多节点租户迁移

### Phase 5: 高级功能（长期规划）
- 🔮 容器化隔离（Docker/Podman）
- 🔮 GPU资源隔离
- 🔮 多租户资源调度算法
- 🔮 自动扩缩容

---

*文档生成时间: 2026-04-29*
*版本: v1.0*
