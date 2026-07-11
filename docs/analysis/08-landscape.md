# 阶段 8：全景汇总与迭代路线图

> 分析完成时间：2026-07-12 00:40
> 分析范围：hermes-agent-java 全栈（后端 95 927 行 Java + 前端 31 741 行 TS/TSX/CSS + 测试 21 686 行，总计约 **149 354 行**）
> HEAD：`e7c2b5b`（后端）/ web `dd8a155`（前端）

---

## 1. 项目本质一句话

> **单体 JVM + 本地文件系统的"AI Agent 操作系统"**，四层结构（Agent Runtime → 多租户隔离 + 沙箱 → 业务层 → 三个 SPA 前端），单实例 Beta 可用，多实例/分布式/GA 有清晰骨架但底层未接线。

不是微服务、不是云原生、不是 k8s-native——是一个**重型单体应用**，用 Javalin 起两个 HTTP 端口（Gateway 8080 + Dashboard 9119/容器内 8080），所有 Service 手工 new、没有 DI 容器、没有数据库（除 SQLite 存会话），业务数据全部 JSON 文件落盘，消息总线/协作/审批/指标全是内存 ConcurrentHashMap。

这种架构的好处是：**开发快、代码集中、调试简单、单实例部署便宜**（一个 fat JAR + 一个卷就跑）。坏处是：**水平扩展、HA、容灾、合规审计全部要重构底层**。

## 2. 总体架构图

```
┌────────────────────────────────────── 浏览器 ──────────────────────────────────────┐
│                                                                                     │
│  web_dist/                ┌─ hub (/): 三卡入口 (262KB)                               │
│  ├── index.html           ├─ portal/ (319KB gzip 104KB)  H5 业务前店 · 暖色 glass   │
│  ├── portal/index.html    │  8 页面 · BottomTabBar · Jarvis 右下角 orb              │
│  ├── ops/index.html       ├─ ops/ (387KB gzip 127KB)    控制台 · dark teal           │
│  └── noc/index.html       │  13 页面 · SidebarLayout · 插件系统                     │
│                           └─ noc/ (384KB gzip 125KB)    NOC 告警中心 · amber        │
│                               6 页面 · glow-bottom 氛围                              │
│                                                                                     │
│  跨 SPA：<a href> 整页跳 · BroadcastChannel 同步 Jarvis 状态 · 不共享 React tree      │
└────────────────────────────────────────┬────────────────────────────────────────────┘
                                         │ Bearer token (injected into index.html)
                                         ▼
┌──────────────────── JVM (hermes.jar) ──────────────────────────────────────────────┐
│                                                                                     │
│  ┌─── IM webhook (QQ/Feishu/Telegram/Discord/Wecom) ────┐                           │
│  │  PlatformAdapter.verifyWebhook + message dispatch    │                           │
│  └───────────────────────┬──────────────────────────────┘                           │
│                          │                                                          │
│  ┌─ Javalin :8080 (GatewayServerV2) ─┐  ┌─ Javalin :9119 (DashboardServer) ───────┐ │
│  │                                  │  │                                         │ │
│  │  /webhook/{platform}             │  │  SPA 静态资源（hub+portal+ops+noc）       │ │
│  │  /api/message                    │  │  /api/config/*                           │ │
│  │  /api/chat, /api/chat/stream 🛡️  │  │  /api/sessions/*                         │ │
│  │  /v1/chat/completions (OpenAI)   │  │  /api/logs/* (+ tail SSE)                │ │
│  │  /api/compare/*                  │  │  /api/cron/* (+ run stream SSE)          │ │
│  │  /api/tenants/* 🔴 无鉴权         │  │  /api/skills, /api/tools, /api/gateway   │ │
│  │  /api/sessions/* 🔴 无鉴权        │  │  /api/analytics/usage                    │ │
│  │  /api/config 🔴 无鉴权            │  │  /api/v1/business/* (Foundation facade)  │ │
│  │                                  │  │  /api/jarvis/chat|intent|approval|stream │ │
│  │  middleware:                     │  │  /api/org/* (OrgApi + NOC control)       │ │
│  │   - CORS                         │  │  /api/tenants (proxy to Gateway?)        │ │
│  │   - checkChatAuth (只保护 chat*)  │  │  /api/canary/*, /api/evalsets/*          │ │
│  │   - extractTenantContext (🔴 裸)  │  │  /providers/oauth/*                      │ │
│  │                                  │  │                                         │ │
│  │  🚨 admin API 无鉴权 P0           │  │  middleware:                             │ │
│  └──────────────┬───────────────────┘  │   - Host 校验                            │ │
│                 │                       │   - Bearer token (SecureRandom 启动生成)│ │
│                 │ sessionToken 互通     │   - CORS (localhost only)                │ │
│                 ▼                       │   - SSE ?token= 白名单                    │ │
│  ┌──────────────────────────────────┐  │                                         │ │
│  │ Service 层（手工 new，无 DI）     │  │  AcpIntegration 构造了但没 start()        │ │
│  │  构造器在 DashboardServer 里 ~250│  └──────────────┬──────────────────────────┘ │
│  │  行硬编码装配                    │                 │                            │
│  │                                 │                 ▼                            │
│  │  TenantManager ──┐              │  ┌────────── SSE ──────────┐                 │
│  │  WorkspaceService│              │  │  BusinessEventBus          │                │
│  │  TeamBlueprintService          │  │  RunEventBus (平行)        │                │
│  │  ScenarioService               │  │  Approval 三级订阅          │                │
│  │  BusinessRunService            │  │  Jarvis SSE 按 workspace 过滤│               │
│  │  BusinessApprovalService       │  └────────────────────────────┘                │
│  │  SLAManager / DLQ              │                                                 │
│  │  CanaryReleaseService          │                                                 │
│  │  EvalSetService                │                                                 │
│  │  PolicyService                 │                                                 │
│  │  BusinessTemplateService       │                                                 │
│  │  PlanReflectionService         │                                                 │
│  │  QuickTeamBuilderService       │                                                 │
│  │  MeteringService ⚠️ 未接线      │                                                 │
│  │  EvolutionProposalService      │                                                 │
│  │  TenantAwareAIAgent pool (Jarvis)                                              │
│  │  + 11 个 Org 组件 (memory CHM) │                                                 │
│  └──────────────┬─────────────────┘                                                 │
│                 │                                                                   │
│                 ▼                                                                   │
│  ┌─── 核心执行：TenantAwareAIAgent.processMessage() ───────────────────────┐        │
│  │                                                                          │        │
│  │  buildSystemPrompt(identity+memory+toolHints+evolution+team)             │        │
│  │    ↓                                                                     │        │
│  │  while iterationBudget:                                                 │        │
│  │    PRE_LLM_CALL hook → ModelClient.chatCompletion → POST_LLM_CALL hook  │        │
│  │    tool calls?                                                           │        │
│  │      ├→ TenantToolRegistry.checkPermission                               │        │
│  │      ├→ ToolCallPrelude (explain/dry-run/warn)                           │        │
│  │      ├→ ApprovalSystem/PolicyService → NEEDS_APPROVAL?                   │        │
│  │      │    ↓ yes → ToolApprovalRequiredException + checkpoint             │        │
│  │      │         → resumeToolApproval() 后继续 while 循环                  │        │
│  │      ├→ Negotiator.autoNegotiate (confidence 阈值)                       │        │
│  │      └→ TenantAwareToolDispatcher.dispatch()                             │        │
│  │           ├─ 19 个工具走沙箱路径（File/Code/Terminal/Memory/Org）         │        │
│  │           └─ 47 个工具走 dispatchGenericTool 🔴 绕过沙箱                  │        │
│  │    no tools → final answer                                               │        │
│  │  endSession → ReflectionEngine + KnowledgeExtractor + saveSession       │        │
│  └──────────────────────────────────────────────────────────────────────────┘        │
│                 │                                                                   │
│                 ▼                                                                   │
│  ┌─── 多 Agent 协作：ScenarioOrchestrator ────────────────────────────────┐         │
│  │  5 种模式：SEQUENTIAL / PARALLEL / REVIEW / COMPETITIVE / MASTER_WORKER│         │
│  │  通过 TenantBus.sendAndWait 同 JVM 消息传递                            │         │
│  │  TeamBlueprintRuntime.ensureTeamRuntime() 为每个 agent 起 TenantAware- │         │
│  │  AIAgent 实例挂到 bus 上                                               │         │
│  │  CanaryReleaseService 按 sticky hash 分流量                           │         │
│  └───────────────────────────────────────────────────────────────────────┘         │
│                 │                                                                   │
│                 ▼                                                                   │
│  ┌─── Tenant（多租户隔离）──────────────────────────────────────────────┐           │
│  │  每 workspace = 1 TenantContext                                     │           │
│  │  独立：FileSandbox / ProcessSandbox(Cgroup v2) / NetworkSandbox /    │           │
│  │        MemoryPool / QuotaManager / AuditLogger / SecurityPolicy /    │           │
│  │        ToolRegistry / SkillManager(4 层) / Metrics(MBean+Prometheus) │           │
│  │                                                                      │           │
│  │  ✅ FileSandbox 11 步路径校验（扎实）                                 │           │
│  │  ✅ NetworkSandbox RestrictedHttpClient + 令牌桶限流                 │           │
│  │  ✅ ProcessSandbox（cgroup v2 可用时限 CPU/MEM，否则降级）             │           │
│  │  ⚠️ ContainerSandbox 是 UnsupportedOperationException 骨架           │           │
│  └──────────────────────────────────────────────────────────────────────┘           │
│                 │                                                                   │
│                 ▼                                                                   │
│  ┌─── 持久化 ───────────────────────────────────────────────────────────┐           │
│  │  Workspace/Team/Scenario/Run/Approval/DLQ/Canary/EvalSet:           │           │
│  │    → JSON 文件 ~/.hermes/business/workspaces/{id}/{type}/*.json     │           │
│  │  Tenant 数据: ~/.hermes/tenants/{id}/state/*.json                   │           │
│  │  Skills: <hermesHome>/skills/{name}/SKILL.md                        │           │
│  │  Session (对话): SQLite ~/.hermes/sessions.db  ⚠️ 存 role+content   │           │
│  │  Audit: <tenantDir>/logs/audit-YYYY-MM-DD.log (JSONL)               │           │
│  │  Trajectory: gzip JSONL                                             │           │
│  │                                                                      │           │
│  │  🔴 零数据库（除 SQLite sessions），业务数据全文件                    │           │
│  │  🔴 Redis*/Postgres*/Distributed* 类写了但没接线、pom 无依赖         │           │
│  └──────────────────────────────────────────────────────────────────────┘           │
│                                                                                     │
│  ┌─── 外部 ────────────────────────────────────────────────────────────┐           │
│  │  LLM API（OpenAI 兼容，ModelClient JDK HttpClient）                  │           │
│  │  浏览器自动化：BrowserBridge（mock/kimi/openclaw/webbridge）         │           │
│  │  外部工具：HTTP/Email（SMTP）                                       │           │
│  └──────────────────────────────────────────────────────────────────────┘          │
└────────────────────────────────────────────────────────────────────────────────────┘
```

## 3. 模块成熟度矩阵（重评分）

| 模块 | 代码行 | 单实例成熟度 | 多实例/GA | 评估 |
|---|---:|---|---|---|
| **基础设施**（Config/启动/Javalin） | ~2 500 | 🟢 Beta 80% | 🟡 50% | 双 Javalin 实例清晰；双配置类是债；Dockerfile 只启 Dashboard |
| **Agent Runtime**（agent/model/prompt/trajectory） | ~7 600 | 🟢 Beta 75% | 🟡 50% | ReAct loop + checkpoint + 记忆都对；无 token 保护；流式无审批 |
| **多租户安全**（tenant/auth/policy/approval） | ~16 700 | 🟢 Beta 80% | 🔴 35% | 沙箱扎实；Redis/Postgres/Distributed 是骨架；3 个 P0 鉴权漏洞 |
| **工具与沙箱**（tools/browser/terminal） | ~11 800 | 🟠 Beta- 70% | 🟠 45% | 8 关卡横切正确；关卡 5 沙箱覆盖率 29%；3 个 P0 漏洞 |
| **业务层**（business/workspace/scenario/blueprint/org/metering） | ~19 600 | 🟢 Beta 75% | 🔴 20% | 闭环能跑；全 JSON+内存；metering 未接；Connector 0 实现 |
| **生态扩展**（plugin/skills/collaboration/acp/evolution/learning/gateway/connector） | ~24 000 | 🟢 Beta- 70% | 🟡 40% | Hook+协作+Skill 是真东西；ACP 默认不起；Gateway 有 P0 漏洞；Transport 半接 |
| **可观测与运维**（dashboard+handlers+monitoring/canary/evalset/compare/governance） | ~7 700 | 🟢 Beta- 65% | 🟡 40% | Cron/Canary/Eval 真能跑；NOC actor 可伪造；ACP/Prometheus/Notifier 半接 |
| **前端**（hub+3 SPA+@hermes/ui+jarvis） | ~31 700 | 🟢 Beta+ 85% | 🟢 Beta+ 85% | 前端不涉及多实例（由后端解决）；代码重复是债 |

### 整体成熟度

| 维度 | 评级 |
|---|---|
| **单实例 Demo/Beta** | 🟢 **Beta 75%** |
| **单实例生产（内部/Beta 客户）** | 🟠 **Beta- 65%**（先修 P0 安全） |
| **多副本水平扩展/HA** | 🔴 **Alpha 25%**（Redis/Postgres/Sticky routing 全要接） |
| **行业标杆（GA+合规）** | 🔴 **Alpha 35%**（缺测试/OTEL/计费/审计中心化/SSO/容器沙箱） |

## 4. 债清单（按优先级排序）

### 🔴 P0 安全（必须上线前修，估计 2-3 人日）

| # | 问题 | 位置 | 修复方向 |
|---|---|---|---|
| S1 | Gateway `/api/tenants/*`、`/api/sessions/*`、`/api/config`、`/api/compare/*` 无鉴权，X-Tenant-ID 即可伪造 | `GatewayServerV2.extractTenantContext` | 所有 `/api/*` 加 Bearer token 校验，跟 DashboardServer 用同一 sessionToken 机制 |
| S2 | NOC 高危操作（replay/reroute/override/browser approval）的 actor 参数可伪造，默认归一化为 "dashboard" 放行 | `OrgControlCenterHandler` + `ControlActionPolicy` | actor 必须从 session token 解析（不是 query/header），未知 actor 默认拒绝 |
| S3 | SSE `?token=` query string 鉴权，token 会进 access log/浏览器历史/反向代理日志 | DashboardServer middleware + jarvisApi | 用短期签名 token（HMAC-SHA256 签发 5 分钟有效），或上 cookie |
| T1 | GitTool 8 个命令全部裸 ProcessBuilder，cwd 任意、无路径校验、无 cgroup、无审批 | `GitTool.runGit()` | 把 git_* 加进 Dispatcher switch case，走 tenantContext.exec + validateTenantPath；或改 GitTool 自己用 sandbox |
| T2 | BrowserToolV2 12 个细粒度动作绕过 BrowserBridgePolicy/audit | BrowserToolV2（browser_open/click/...）| 要么删这些细粒度工具只保留 browser_bridge，要么让它们也走 BrowserBridgePolicy+audit |
| T3 | grep_files 只拦 4 个黑名单路径（子串 contains），可读任意文件 | `FileTool.grepFiles()` | 把 grep_files 加进 dispatchFileTool 走 11 步校验 |

**S1+S2+S3 合计应该能在 1 天内修完**，是纯粹的鉴权接线问题。T1/T2/T3 是工具路径问题，1-2 天。

### 🟠 P1 架构与正确性（1-2 周，Beta 客户可接受前修）

| # | 问题 | 影响 |
|---|---|---|
| A1 | **MeteringService 没接线**，tokensUsed/estimatedCost 全是 0 | 成本/计费空，Beta+ 阶段必须接 |
| A2 | **ModelClient 硬编码 OpenAI 协议**，TransportProvider（Anthropic/Bedrock/Codex）注册了但不消费 | README 声称多模型支持是假的，要么删 transports 要么接 ModelClient |
| A3 | **流式路径 doProcessMessageStream 没有审批 checkpoint 支持**，撞审批直接 RuntimeException | 流式对话（playground、H5 聊天）HITL 体验挂 |
| A4 | **主循环无 token 计数/上下文超限保护**，只靠 max_turns=90，长会话必然爆 context | 长对话 400 错误 |
| A5 | **persistSession 每次 new SessionManager**，只存 role+content 不存 tool_call_id，SQLite 连接抖动+恢复后 tool 关联丢 | 会话恢复 bug |
| A6 | **两套事件总线**（RunEventBus vs BusinessEventBus），Jarvis SSE 订阅 BusinessEventBus 收不到 run 状态更新 | 浮窗实时反馈缺失 |
| A7 | **AgentCronRunner 用 createDefault(config) 路径起 agent**，独立 new TenantManager，不跑 workspace agent | 业务 cron 任务不生效 |
| A8 | **Running 状态全内存**（IntentRun/Approval Checkpoint/CompareRun），JVM 重启全丢 | HA 重启后状态不一致 |
| A9 | **Compare 直连 ModelClient 不走 Agent loop**（无工具/审批/沙箱） | 多模型对比功能无效 |
| A10 | **sessionToken 启动随机生成、不持久化**，重启全登出；多副本无法共享 | 多副本部署不可能 |
| A11 | **ContextCompressor 没接主循环**，config compression 配置无消费者 | 长会话保护缺失 |
| A12 | **dashboard sessionToken 无 refresh 机制、无过期** | token 长期有效，泄漏后永久访问 |
| N1 | **NetworkSandbox 缺 DNS rebinding 防护**，能访问云 metadata/内网 | SSRF |

### 🟡 P2 代码质量与死代码清理（1 周，随时可做）

| # | 问题 |
|---|---|
| C1 | **3 套配置类**（ConfigManager vs HermesConfig vs gateway/config/HermesConfig），功能重叠 |
| C2 | **无 DI 容器**，DashboardServer 构造器 ~250 行手工 new 全 Service |
| C3 | **SessionManager 两套**（gateway/SessionManager SQLite vs agent 的 sessionManager）路径不一致 |
| C4 | **Gateway V1(749 行) vs V2(1431 行)** 并存，老代码未清理 |
| C5 | **JSON 双库**（fastjson2 + Jackson）并存 |
| C6 | **Dispatcher 内联实现 vs tools/impl/*.java 老 handler** 19 个工具双份实现（一份死代码） |
| C7 | **死代码清单**（未被任何构造调用点引用）：
  - `tenant/tools/TenantAwareCodeTool` + `TenantAwareSkillTool`（883 行）
  - `terminal/` 包 Local/SSH/Docker 抽象（658 行）
  - `tools/impl/PathSecurity`（190 行）
  - `RedisQuotaStore`、`RedisApprovalStore`、`PostgresTenantRepository`、`DistributedSessionManager`（无 pom 依赖）
  - `org.distributed.AgentRegistry` 虽然有代码但单 JVM CHM，无网络通信
  - `auth/SsoService` 没接登录链路
  - `insights/` 空包
  - ACP Integration 构造了但没 start
  - Connector 接口 0 实现
  - TaskOrchestrator 127 行没调用点
  - Evolution/Learning/CuratorJob 的定时调度都没接 |
| C8 | **Frontend 重复代码**：4 份 fetchJSON、2 份 useSSE/useToast/plugins、portal 独立 i18n |
| C9 | **两套 Org 路由**（/api/organization/* 老 + /api/org/* 新） |
| C10 | **SkillManager（全局）与 TenantSkillManager（租户）并存**未抽象共享基类 |
| C11 | ScenarioService setter 循环注入 |
| C12 | WorkspaceId sanitize 是 replace（`[^...]-→_`）非 reject，可能导致路径碰撞 |
| C13 | @hermes/ui/themes/presets 写了但三个 SPA 都在自己 theme.css 定义 @theme |

### 🟢 P3 功能完善与产品化（GA 前，2-4 周）

| # | 功能 | 说明 |
|---|---|---|
| F1 | Prometheus `/metrics` endpoint | TenantMetrics.toPrometheus 写了但没挂 HTTP |
| F2 | 告警/通知真正发邮件/IM | AlertChannel、BusinessNotifier 只写日志 |
| F3 | 插件热加载 | HookEngine 目前需要重启 |
| F4 | SSO/OIDC 接入 | SsoService 骨架已有，需要接登录链路 |
| F5 | ContainerSandbox（docker 隔离） | 现在是 UnsupportedOperationException |
| F6 | 审计中心化（ELK/ Loki） | 现在写本地文件 |
| F7 | Connector 内置实现（至少 1 个示例） | 0 内置，产品化时至少要有一个真实案例 |
| F8 | Evolution Proposal 定时调度 | Generator 没接定期执行 |
| F9 | CuratorJob/LearningPipeline 定时调度 | 会话结束触发有，定期无 |
| F10 | Jarvis crossSpaceLink 自动导航 | 字段返回了但前端没整页跳 |
| F11 | 前端 bundle 去重（npm workspace + rollup external） | ~75KB gzip 节省 |
| F12 | ACP 可配置启停 | 要么配置开关启动，要么删掉 Integration 引用 |
| F13 | 测试覆盖率补全（业务 service 单测） | 目前 992 单测主要是工具和审批，业务 service 覆盖薄 |

## 5. 分阶段迭代路线建议

### Sprint 1：安全止血（3 天，必做）
**目标：堵住 P0 漏洞，能放到公网给 Beta 客户用**
- [ ] S1 Gateway admin API 加 Bearer 校验
- [ ] S2 NOC actor 从 session token 解析，未知拒绝
- [ ] S3 SSE 换短期签名 token（或至少加文档说明风险 + 反代 access log 脱敏）
- [ ] T1+T3 接 GitTool 和 grep_files 进沙箱路径
- [ ] T2 删除或改造 BrowserToolV2 细粒度动作，强制走 BrowserBridgePolicy

**验收**：公网部署 8080/9119 端口后，未带 token 请求 401；带 token 的普通用户无法跨 tenant 操作；git/grep/browser 工具都走沙箱审计。

### Sprint 2：核心功能接线（1 周）
**目标：让 Beta 演示/内部使用不踩坑**
- [ ] A1 Metering 接 ModelClient 和 ToolDispatcher（recordLlmCall/recordToolExec）
- [ ] A3 doProcessMessageStream 加审批 checkpoint 支持
- [ ] A4 主循环加 token 计数 + ContextCompressor 接线（简单截断也行）
- [ ] A6 合并 RunEventBus 和 BusinessEventBus（或互相桥接）
- [ ] A7 AgentCronRunner 改为用 workspace 对应的 agent 实例
- [ ] N1 NetworkSandbox 加内网 IP / metadata 黑名单
- [ ] A10 sessionToken 持久化到文件（多副本先不做，单实例重启能保持登录）
- [ ] C8 前端抽 fetchJSON/useSSE/useToast 到 @hermes/ui

**验收**：
- Portal Insights 成本数据不为 0
- 流式对话撞审批能暂停+恢复
- Cron job 跑在正确 workspace
- Jarvis 浮窗能看到 run 状态实时更新
- 重启后登录态保留
- LLM 无法访问 169.254.169.254

### Sprint 3：架构清理（1 周）
**目标：技术债减负，为 GA 和多实例做准备**
- [ ] C1 合并 3 套配置类到 ConfigManager
- [ ] C6 删除被 Dispatcher shadow 的 19 个老 handler，让它们 delegate 到 Dispatcher 或直接删
- [ ] C7 批量删死代码（terminal/PathSecurity/TenantAware*Tool/空包/ACP dead ref/TaskOrchestrator）
- [ ] C4 删 Gateway V1
- [ ] C5 统一 JSON 库到 Jackson
- [ ] C2 考虑引入一个轻量 DI（Guice 或手动 ServiceLocator 模式），把 DashboardServer 构造器瘦身
- [ ] C9 删除老 /api/organization/* 路由
- [ ] F1 Prometheus /metrics 挂出来
- [ ] F12 ACP 要么 start 要么删 Integration

**验收**：`cloc` 统计净减 3000+ 行死代码；启动时没有 "Failed to load" 警告；新增 service 不需要改 DashboardServer 构造器。

### Sprint 4：多实例/GA 准备（2-3 周）
**目标：从"单 JVM 能跑"到"生产可部署"**
- [ ] pom 加 Redis client（Lettuce）+ Postgres JDBC
- [ ] 实现 RedisQuotaStore、RedisApprovalStore 并在生产 profile 下启用
- [ ] 实现 Postgres 版 Workspace/Team/Scenario/Run/Approval/Canary/EvalSet Repository（保留 File 版做单实例 profile）
- [ ] sticky routing：按 workspaceId hash 保证同一 workspace 请求路由到同一实例（会话亲和）
- [ ] DistributedSessionManager 实现（基于 Postgres + Redis 缓存）
- [ ] RateLimiter 改 Redis 版
- [ ] SSE 的 token 改 JWT（带过期+签名）
- [ ] sessionToken 支持配置（从文件/env 读固定 token 或多实例共享密钥）
- [ ] F2 Notifier 真接 SMTP/IM webhook
- [ ] F4 SSO/OIDC 接入登录
- [ ] F5 ContainerSandbox 实现（docker 隔离）
- [ ] F13 补业务 service 单测到 70%+ coverage

**验收**：两个 JVM 实例 + 一个 Redis + 一个 Postgres 组成集群，挂在 nginx 后面；一个 workspace 的 session/审批/run 能在重启/切换实例后恢复；速率限制是跨实例的总速率。

### Sprint 5：行业能力（2-4 周，按客户需求）
- [ ] F7 1-2 个真实 Connector 实现（电商/ERP）
- [ ] F8/F9 定时 Evolution/Curator/Learning
- [ ] F11 bundle 去重
- [ ] F10 Jarvis 自动跨页导航
- [ ] 垂直场景模板（电商/客服/运维）
- [ ] F6 审计中心化（Loki/ELK 输出配置）
- [ ] 计费对接（Stripe/国内）

## 6. 哪些设计是真的好（保留/发扬）

盘点过程中发现不少设计是真的有想法、做得扎实的，不要重构时丢掉：

1. **Dispatcher 8 关卡横切流水线**（PRE hook → permission → prelude → approval → negotiation → execute → POST hook → transform）——这是企业级 agent platform 的正确分层，模型/工具/审批/协商/审计都是可插拔的。
2. **ToolCallPrelude 的 dry-run + explain + reject 模式**——AI 执行工具前先"想清楚"、能主动拒绝，比纯 ReAct 安全。
3. **审批 checkpoint 设计**——11 字段快照（assistantMessage/toolCalls/pendingIndex/completedResults/remainingIterations/...）+ resumeToolApproval 续跑，是真 HITL 的关键。
4. **FileSandbox 11 步路径校验**——符号链接/硬链接/traversal/白名单/黑名单/类型/大小全考虑了，教科书级。
5. **normalizeCommandForDetection 防 shell 绕过**——续行/转义/IFS 归一化，这是真实 CVE 的补丁。
6. **5 种协作模式 + TenantBus 消息传递**——SEQUENTIAL/PARALLEL/REVIEW/COMPETITIVE/MASTER_WORKER 覆盖企业常见协作场景，bus 做内存消息传递简单有效。
7. **Canary 金丝雀发布**——sticky hash 路由 + promote/rollback，真实能用。
8. **Plugin 系统 3 种加载策略 + 16 HookType**——插件能拦工具/LLM/API/会话/网关/审批，扩展面足够。
9. **Skill 生态链完整**——创建→补丁→Curator→Provenance→Hub→Fine-tune 导出，是往 Agent 技能市场铺路。
10. **前端 SPA 解耦架构**——整页跳 + BroadcastChannel 同步 Jarvis，简单干净不复杂，三个 SPA 独立迭代不互相影响。
11. **Jarvis 粒子 FSM 设计**——6+4 形态、硬锁定 800ms 切换、设计文档严格落地，是产品差异化的记忆点。
12. **双态记忆（snapshot+live）**——系统 prompt 冻结不刷新（prefix cache 友好）、工具写入立即落盘，性能与一致性兼顾。
13. **ReflectionEngine 不自动应用提案**——人工审批才进化，保守安全。
14. **DelegatedExecutorSafetyPolicy 防委派环/深度限制**——多 Agent 递归委派有终止条件。
15. **原子写 JSON（tmp+rename）**——所有 FileRepository 都用，崩溃不产生半截文件。

## 7. 哪些地方被我之前高估了（盘点修正）

阶段 4/5/6 纠正了之前 MEMORY 里的几处判断：

| 之前的判断 | 实际情况 |
|---|---|
| "多租户成熟度 90%" | 单实例多租户是 **80%**（扎实但 ProcessSandbox 不是容器、NetworkSandbox 缺 DNS rebinding）；多实例是 35% |
| "TenantAwareToolDispatcher 是 defense-in-depth 教科书" | 架构是对的，但关卡 5 沙箱覆盖率只有 29%（19/66），大量老工具绕过沙箱 |
| "org.distributed.AgentRegistry 跨集群" | 是单 JVM CHM，没网络层，cluster 是名不副实 |
| "Metering 能记账" | MeteringService 没接 ModelClient，成本字段全是 0 |
| "ACP 服务器已挂载" | AcpIntegration 在 Dashboard 构造器 new 了但没调 start()，默认不起 |
| "Cron 是假 UI" | Cron 真能跑！ScheduledExecutorService+AgentCronRunner，只是 deliver 只有 local 能用，且走的是 default tenant 而非 workspace agent |
| "Compare 是多模型对比" | Compare 直连 raw ModelClient，不走 Agent loop，对比的是裸模型输出不是 agent 效果 |

## 8. 文件清单（产出物）

本次分析产出 8 份文档：

```
docs/analysis/
├── 00-infrastructure.md       （~13KB）基础设施：依赖/启动/双 Javalin/配置/静态资源/部署
├── 01-agent-runtime.md        （~13KB）Agent 引擎：主循环/模型/记忆/审批恢复/子 Agent
├── 02-tenant-security.md      （~18KB）多租户：13 子系统/沙箱/配额/审批/Redis 死代码
├── 03-tools-sandbox.md        （~13KB）工具层：8 关卡/覆盖率 29%/3 个 P0 漏洞/三套平行框架
├── 04-business-layer.md       （~18KB）业务层：Workspace/Scenario/Blueprint/Run/Org/Metering/全 JSON 持久化
├── 05-ecosystem.md            （~16KB）生态：Plugin/Hook/Skill/协作/ACP/Evolution/Learning/Gateway P0
├── 06-observability-ops.md    （~13KB）运维：15 handlers/Cron/Canary/Eval/Compare/NOC actor P0
├── 07-frontend.md             （~13KB）前端：4 SPA 架构/Jarvis/主题/i18n/重复代码
└── 08-landscape.md            （本文件，~22KB）全景汇总：架构图/成熟度矩阵/债清单/路线图
```

合计约 **139KB 分析文档**。

## 9. 一句话总结

> **hermes-agent-java 是一个"单体 JVM 就能跑起来"的 AI Agent 平台，核心的 ReAct loop + checkpoint + 多租户沙箱 + 多 Agent 协作 + 三 SPA 前端骨架都是真东西、完成度 70-80%；但它同时是一个"写了很多分布式骨架但底层没接"的项目——Redis/Postgres/Transport/ContainerSandbox/SSO 这些类都在文件里，但 pom 没依赖、没构造调用点。加上 6 个 P0 安全漏洞（3 个鉴权+3 个工具沙箱绕过），现在适合内部 Demo 和受控 Beta，不适合直接公网生产。按 Sprint 1-3 的顺序修，3 周内能到"可对外 Beta"的状态。**
