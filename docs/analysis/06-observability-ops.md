# 阶段 6：可观测性与运维

> 分析时间：2026-07-12
> 分析范围：`dashboard/handlers/`（5 578 行）、`dashboard/jarvis/`、`monitoring/`（216 行）、`canary/`（423 行）、`evalset/`（468 行）、`compare/`（405 行）、`governance/`（31 行）、`insights/`（空目录）、以及各模块 *DashboardIntegration 类
> HEAD：`e7c2b5b`

---

## 1. 总览

可观测/运维层总代码约 **7 700 行**（含各业务模块的 *DashboardIntegration）。核心是 DashboardServer 挂的 15 个 HTTP handler，外加 Jarvis 5 个类、监控/金丝雀/评估集/对比/治理模块。

**核心结论**：Dashboard 的 HTTP API 覆盖全面（config/session/log/cron/tool/skill/gateway/oauth/analytics/org），但：
- **有两个 API 鉴权漏洞**：阶段 5 发现的 Gateway 8080 裸奔；阶段 0 发现的 Dashboard 9119 Bearer token 是启动随机生成、SSE 走 query token。
- **Cron 真能跑**（ScheduledExecutorService + AgentCronRunner），但 feishu/telegram/discord 投递未接线。
- **ACP Integration 构造了但没 start**——ACP 服务器默认不启。
- **Canary 金丝雀发布有真实实现**（流量百分比+sticky routing+promote/rollback）。
- **EvalSet 评估集能跑**（跑 scenario + 关键词断言）。
- **Compare 多模型对比能跑**（并行跑多个 tenant/agent）。
- **insights/ 顶层包是空的**，insights 代码都在 `business/insight/`（阶段 4 盘过）。
- **governance/ 只有一个 31 行的 ControlActionPolicy**，非常轻量。
- **AnalyticsHandler 直连 SQLite** 跑 SQL 聚合用量，不走 service 层。

## 2. Dashboard HTTP Handlers（5 578 行）

| Handler | 行数 | 职责 | 评估 |
|---|---|---|---|
| **OrgControlCenterHandler** | 1 313 | NOC 控制台：overview/teams/intents/replay/reroute/agent override/delegated tasks/browser approval | ✅ 真实现，调用 ControlActionPolicy 做 RBAC |
| **SessionHandler** | 633 | 会话 CRUD + search + JSON 文件直接读（不走 SessionManager！）| 🟡 直接扫 sessions 目录 JSON，两套会话读取路径 |
| **CronHandler** | 615 | 定时任务 CRUD + schedule/trigger/pause/resume/run stream | ✅ 真调度（ScheduledExecutorService+AgentCronRunner），local deliver 能用 |
| **ConfigHandler** | 477 | 配置查看/修改/env/reveal | ✅ 基本能用，改 config 后热更新路径要细查 |
| **LogsHandler** | 466 | 日志列表/查看/聚合/tail SSE | ✅ 直接读 log 文件 |
| **EnvHandler** | 390 | 环境变量管理、API key 安全存储 | ✅ 写 .env 文件 |
| **CronJobExecutor** | 247 | 调度引擎（ScheduledExecutorService + schedule/cancel/reschedule）| ✅ 真东西 |
| **SkillsHandler** | 226 | 技能列表/开关（直接调 SkillManager）| ✅ |
| **AnalyticsHandler** | 220 | 用量统计（直连 SQLite 跑 SQL SUM/COST）| 🟡 绕过 service 层直连 DB |
| **ToolsHandler** | 216 | 工具集列表/详情 | ✅ |
| **OrgApiHandler** | 203 | Org 12 模块 summary API（identity/handoff/auth/kb/workflow/market/cost/observe/distributed/evolution/compliance）| 🟡 每个模块 `get("xxx")` 返回 `status:"not_wired"` 或真实 summary，半接 |
| **OrgOverviewHandler** | 189 | Org 总览 | ✅ |
| **OAuthProvidersHandler** | 181 | OAuth 登录流程 | 🟡 阶段 2 发现 SsoService 没接；这里是 provider 列表+callback 处理 |
| **GatewayHandler** | 137 | Gateway 重启/更新/action 状态 | ✅ |
| **AgentCronRunner** | 65 | Cron 调用 Agent 的 JobRunner 实现 | ✅ 真跑 agent.processMessage |

### 2.1 OrgControlCenterHandler（1 313 行，最大）— NOC 控制台
提供的操作：
- `GET /api/org/control/overview`：全局概览（所有 tenant 健康汇总）
- `GET /api/org/control/teams`：团队状态
- `GET /api/org/control/intents`：运行中 intent 列表（可按 tenant 过滤）
- `POST /api/org/control/intents/{tenantId}/{runId}/replay`：重放失败 run
- `POST /api/org/control/intents/{tenantId}/{runId}/reroute`：把 subtask 改派
- `POST /api/org/control/agents/{tenantId}/{agentId}/override`：人工接管 agent
- `GET/POST /api/org/control/delegated-tasks/...`：委派任务查看/提交/执行
- Browser bridge 审批：`POST /api/org/control/browser/{tenantId}/{approvalId}/approve|reject`
- `GET /api/org/control/browser/queue`：浏览器审批队列

所有写操作过 `ControlActionPolicy.isAllowed(actor, action)`，FULL_ACCESS 角色（dashboard/operator/admin/system）才允许，READ_ONLY 被拒。actor 从哪取？从 `ctx.queryParam("actor")` 或 header——待确认是否真有鉴权（否则任何人带 actor=admin 就能操作）。

### 2.2 Cron 真实工作机制
```
CronHandler.scheduleJob()
  → CronJobExecutor.schedule(job)
      → scheduler.schedule(runAndReschedule, delaySeconds)
          → runAndReschedule(jobId)
              → AgentCronRunner.run(job)
                  → new TenantAwareAIAgent(config, sessionId)
                  → agent.processMessage(job.prompt)
                  → 结果通过 SSE 推到 /api/cron/runs/{id}/stream
              → schedule(job)  // 再次调度
```
**注意**：
- 每次 cron 跑都 `new TenantAwareAIAgent(config, sessionId)`——这是走 `createDefault(config)` 路径！我在阶段 1 标记过 createDefault 会 new TenantManager()，这意味着 cron job **不会用业务 workspace 的 agent**，而是自己起个 default tenant agent。这对业务 cron（workspace 内任务）不适用。
- deliver 只支持 "local"，feishu/telegram/discord 抛 UnsupportedOperationException。

### 2.3 SessionHandler 的两套读取路径
- SessionHandler 直接扫 `<hermesHome>/sessions/*.json` 文件读消息
- gateway/SessionManager 是 SQLite 版本
- 两套并存，路径可能不一致

### 2.4 OrgApiHandler 的"not_wired"模式
每个 org 模块先 `get("moduleName")`，拿到 null 就返回 `{"status":"not_wired"}`。这意味着大部分 org 模块（cost/market/distributed/evolution/compliance）在 Dashboard 视角是未完全接线的，返回 not_wired。阶段 4 看过这些模块代码本身是内存实现，但没通过 TenantContext 初始化+挂到 Dashboard 可见的地方。

## 3. Jarvis 后端（dashboard/jarvis/ 5 文件）

| 类 | 行数 | 职责 |
|---|---|---|
| **JarvisHandler** | 最核心 | 注册 `/api/jarvis/chat`、`/intent`、`/approval/{id}`、`/stream` 四个 endpoint；订阅 BusinessEventBus + ApprovalService 推 SSE |
| **ChatService** | 阶段 1 盘过 | per-workspace agentPool (CHM)，processMessage 走 TenantAwareAIAgent |
| **IntentRouter** | 阶段 1 盘过 | classifyIntent → ProductQueryService.dispatch 真执行 |
| **ProductQueryService** | 阶段 1 盘过 | portal/ops/noc 各 2-3 个 action，真调底层 service |
| **ApprovalBridge** | 阶段 1 盘过 | 双路径 resolve（ToolApprovalCoordinator + BusinessApprovalService）|

这 5 个类阶段 1 已详细分析过，不再复述。SSE Stream 用 StreamContext（workspaceId/allAccess）做过滤，P0 安全点在阶段 1/2 都标记过（?token=query、?workspaceId=可伪造，没从 session 解析真实身份）。

## 4. Monitoring（216 行）— 评估指标

| 类 | 行数 | 职责 |
|---|---|---|
| **AgentEvalMetrics** | 178 | Agent 维度的评估指标（toolCallCount/tokenCount/latency/errorRate），每 session 一份，logSnapshot 写 JSON |
| **EvalSnapshot** | 38 | 快照数据对象 |

⚠️ 没有 Prometheus `/metrics` endpoint 直接暴露这些指标（TenantMetrics 有 toPrometheus 方法但没挂 HTTP endpoint）。也没有定时调度 logSnapshot——只在 endSession 时被调用。

## 5. Canary 金丝雀发布（423 行）— 真实现

| 类 | 行数 | 职责 |
|---|---|---|
| **CanaryReleaseService** | 180 | 金丝雀生命周期 start/update/promote/rollback/getActiveCanary |
| **CanaryReleaseDashboardIntegration** | 122 | HTTP endpoint（list/start/update/promote/rollback）|
| **FileCanaryReleaseRepository** | 74 | JSON 文件持久化 |
| **CanaryReleaseRecord** | 47 | 数据对象（releaseId/teamId/fromVersion/toVersion/trafficPercent/status）|

**流量路由**：`resolveVersionForRequest(workspaceId, teamId, requestKey)` 做 sticky hash——
1. 查 team 有无 active canary
2. 没有 → activeVersion
3. 有 → 对 requestKey 做 hash，`hash % 100 < trafficPercent` 路由到 canary，否则 active
4. requestKey 用 scenarioId + nanoTime / runId 等保证同一 run 粘到同一版本

**生命周期**：
- start: 从 fromVersion(active) → toVersion，初始流量 0-100%
- updateTraffic: 调整百分比
- promote: toVersion 设为 active，canary 标记 COMPLETED
- rollback: 回滚 fromVersion 为 active，canary 标记 ROLLED_BACK

这是**真能用的金丝雀**，且跟 TeamBlueprintRuntime.resolveVersionForRequest 已经接好（ScenarioService.executeScenario 里调了）。

## 6. EvalSet 评估集（468 行）

| 类 | 行数 | 职责 |
|---|---|---|
| **EvalSetService** | 228 | 评估集 CRUD + runEvaluation（执行每个 case 跑 scenario）|
| **EvalSetDashboardIntegration** | 112 | HTTP endpoint |
| **FileEvalSetRepository** | 76 | JSON 持久化 |
| **EvalSetRecord** | 52 | 数据（evalSetId/name/cases: List<EvalCase>）|

**runEvaluation 流程**：
1. 校验 workspace + scenario + evalSet 存在
2. 对每个 EvalCase（input + expectedKeywords + forbiddenKeywords）：
   - runService.createRun(TRIAL) 创建 NEEDS_APPROVAL 占位
   - scenarioService.executeScenario(skipApprovalCheck=true)
   - 拿 resultSummary 小写化
   - 检查 expectedKeywords 全包含、forbiddenKeywords 全无
   - 记录 pass/fail
3. 返回 EvalResult(passRate, caseResults, startedAt, durationMs)

**问题**：EvalCase 只有关键词包含断言，没有 LLM-as-judge 或精确比对；且 skipApprovalCheck=true 绕开了审批门控，评估不验证审批路径。**但框架是真能跑的**。

## 7. Compare 多模型/多租户对比（405 行）

| 类 | 行数 | 职责 |
|---|---|---|
| **TenantComparisonOrchestrator** | 234 | 创建 compare run，并行跑多个 tenant 同一 topic，结果存 JSON |
| **TenantComparisonRun** | 171 | 运行记录（topic/rounds/tenantIds/responses/completedAt）|

**流程**：
1. createRun(topic, rounds, tenantIds)：为每个 tenant 起一个 thread（裸 `new Thread()`，非虚拟线程）
2. 每个 thread 里：拿/创建 tenant → createChild tenant if needed → ModelClient 调 LLM（直接 chatCompletion，不走 Agent loop！）
3. 每轮收集所有 tenant 响应，persistRun 写 JSON 到 `~/.hermes/compare/runs/`
4. 支持 stopRun（interrupt 线程）

⚠️ **问题**：
- Compare 直接 new ModelClient 调 raw LLM，**不走 Agent loop**（不调用工具、不审批、不沙箱）——所以它对比的是 raw 模型响应，不是 agent 执行效果
- 用裸 `new Thread()` + thread.interrupt()，非虚拟线程
- DashboardServer 里挂了 SSE endpoint 推进度

## 8. Governance（31 行）— 轻量 RBAC

唯一文件 `ControlActionPolicy`：
- Action 枚举：REPLAY_INTENT/REROUTE_INTENT/OVERRIDE_AGENT/CONFIGURE_BROWSER_BRIDGE/CHECK_BROWSER_BRIDGE/APPROVE_BROWSER_ACTION/REJECT_BROWSER_ACTION
- 角色：FULL_ACCESS（dashboard/operator/admin/system）、READ_ONLY（viewer/readonly/guest）
- isAllowed(actor, action)：FULL_ACCESS 放行，READ_ONLY 拒绝，未知拒绝
- actor 为 null 时归一化为 "dashboard"（即默认放行——这本身是个漏洞：HTTP 请求不带 actor 就获得 dashboard 权限）

## 9. Insights（空目录）

`src/main/java/com/nousresearch/hermes/insights/` 是空的——没有 InsightsApiHandler 或任何内容。insights 功能实际在 `business/insight/`（BusinessInsightService + ProjectionAdapter + DashboardIntegration），阶段 4 盘过：
- BusinessInsightService 从 run/approval/metering 数据聚合洞察
- 但 metering 没接线（阶段 4 发现），InsightService 拿到的数据不完整

## 10. 空/未接线模块清单

| 模块 | 状态 |
|---|---|
| insights/ 顶层包 | 空目录，功能在 business/insight/ |
| ACP Server | AcpIntegration 构造了但**没调 start()**，默认不启 |
| SsoService | 阶段 2 发现，没接登录链路 |
| Org 模块（market/cost/distributed/evolution/compliance）| OrgApiHandler 返回 "not_wired" |
| Cron feishu/telegram/discord deliver | 抛 UnsupportedOperationException |
| Prometheus metrics endpoint | TenantMetrics.toPrometheus() 写了但没挂 HTTP 路由 |
| Email/Webhook AlertChannel | 写了通道类但没阈值调度触发（阶段 2） |
| Notifier | 只写日志，不发邮件/IM（阶段 4） |

## 11. DashboardServer 路由全景（完整清单）

阶段 0 看过部分，这里整理完整：

| Prefix | 数量 | 来源 |
|---|---|---|
| `/health` | 1 | inline |
| `/api/config/*` | 7 | ConfigHandler + inline |
| `/api/model/info` | 1 | inline |
| `/api/providers/oauth/*` | 7 | OAuthProvidersHandler |
| `/api/sessions/*` | 4 | SessionHandler |
| `/api/logs/*` | 5 + 1 SSE | LogsHandler |
| `/api/skills` | 2 | SkillsHandler |
| `/api/learning/*` | 7 | inline 调 LearningGraphService |
| `/api/tools/*` | 3 | ToolsHandler |
| `/api/gateway/*` | 3 | GatewayHandler |
| `/api/analytics/usage` | 1 | AnalyticsHandler（直连 SQLite） |
| `/api/cron/*` | 9 + 1 SSE | CronHandler + CronJobExecutor |
| `/api/v1/business/*` | ~10 | 各 *DashboardIntegration |
| `/api/v1/business/events/stream` | 1 SSE | BusinessEventSSEHandler（老接口） |
| `/api/organization/*` | 3 | OrgOverviewHandler（老接口） |
| `/api/org/summary`、`/api/org/{module}` | ~25 | OrgApiHandler + OrgControlCenterHandler |
| `/api/jarvis/chat|intent|approval|stream` | 4 | JarvisHandler |
| `/api/tenants/*` (proxy?) | ? | 部分转发到 Gateway |
| `/api/canary/*` | ~5 | CanaryReleaseDashboardIntegration |
| `/api/evalsets/*` | ~5 | EvalSetDashboardIntegration |
| `/workspace/*`、`/business-portal/*`、`/business/*`、`/runs/*` | SPA fallback | inline |
| `/portal`、`/ops`、`/noc` + 各自 `/*` | 6 | SPA fallback |
| `/`、`/*`、`/assets/*`、... | 静态 + hub | inline |

## 12. 阶段 6 识别到的债/风险

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🔴 | **Gateway /api/tenants/* 等管理 API 无鉴权** | GatewayServerV2.extractTenantContext | 阶段 5 发现，再次确认：X-Tenant-ID 即可伪造租户身份 |
| 🔴 | **OrgControl 动作的 actor 可伪造** | OrgControlCenterHandler | actor 从 query/header 取，ControlActionPolicy 未知 actor 归一化为 "dashboard"（FULL_ACCESS），等于默认放行 |
| 🟠 | **Cron Job 每次 new TenantAwareAIAgent(config,sessionId)，走 createDefault 路径** | AgentCronRunner | 不会用 workspace 的 agent，cron 跑的是 default tenant，业务 cron 不适用；而且 createDefault 会 new 独立的 TenantManager |
| 🟠 | **ACP Server 构造了但没 start** | DashboardServer 构造器里 new AcpIntegration() 但没调 start | ACP 协议服务端默认不启 |
| 🟠 | **Compare 直接 raw ModelClient 调 LLM，不走 Agent loop** | TenantComparisonOrchestrator | 对比的是裸模型输出，不是 agent 工具调用效果；无审批/沙箱 |
| 🟡 | SessionHandler 直接扫 JSON 文件 vs gateway/SessionManager 用 SQLite | SessionHandler | 两套会话读取路径，数据可能不一致 |
| 🟡 | AnalyticsHandler 直连 SQLite 跑 SQL，绕过 service 层 | AnalyticsHandler | 未来切换 Postgres 要重写 |
| 🟡 | Org 大部分模块在 OrgApiHandler 返回 not_wired | OrgApiHandler | 代码写了但没接到 Dashboard 可见处 |
| 🟡 | Compare/ScenarioOrchestrator 都用裸 `new Thread()` | 多处 | 没用虚拟线程，高并发下线程数可能爆 |
| 🟡 | TenantMetrics.toPrometheus() 没挂 HTTP endpoint | TenantMetrics | Prometheus 指标没有 scrape endpoint |
| 🟡 | AlertChannel/Notifier 没接调度/真发通知 | monitoring/business/notification | 告警/通知都是日志 |
| 🟢 | insights/ 空目录 | src/.../insights/ | 应删掉或合并到 business/insight/ |
| 🟢 | governance 只有 31 行单文件 | governance/ | 比想象中轻量很多 |

## 13. 阶段 6 小结

**可观测/运维层成熟度：Beta-（65%）**

能用的：
- ✅ 15 个 HTTP handler 覆盖配置/会话/日志/工具/技能/网关/cron/分析/组织/OAuth
- ✅ Cron 真调度（ScheduledExecutorService+AgentCronRunner）
- ✅ Canary 金丝雀发布（流量百分比+sticky hash+promote/rollback）
- ✅ EvalSet 评估集（scenario 跑+关键词断言）
- ✅ Compare 多租户对比（虽然是 raw LLM）
- ✅ Logs 实时 tail SSE
- ✅ Jarvis 4 endpoint 全接
- ✅ NOC 控制台（OrgControlCenterHandler）覆盖 replay/reroute/override/browser approval

未接/半接：
- ❌ ACP server 默认不起
- ❌ SSO 不接
- ❌ Org 多模块返回 not_wired
- ❌ Cron 非 local deliver 不支持
- ❌ Prometheus /metrics endpoint 未暴露
- ❌ 告警/通知不发
- ❌ Compare 不走 Agent loop
- ❌ 两个鉴权漏洞（Gateway admin API 裸奔、NOC actor 伪造）
- ❌ AgentCronRunner 用 default tenant 而非 workspace agent

### 下一步
→ **阶段 7：前端全景** — 深入 web/ 目录（根 hub + portal + ops + noc + @hermes/ui + @hermes/jarvis），理解 SPA 架构、路由、数据流、API 对接、组件体系、设计系统。
