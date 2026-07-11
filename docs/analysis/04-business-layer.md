# 阶段 4：业务层

> 分析时间：2026-07-11
> 分析范围：`business/` 8 439 行、`workspace/` 1 164 行、`scenario/` 1 322 行、`blueprint/` 2 120 行、`org/` 5 847 行、`metering/` 711 行，合计约 **19 600 行**
> HEAD：`e7c2b5b`

---

## 1. 总览

业务层是 Portal/Ops/Noc 三个 SPA 的后端，把底层 Agent Runtime 包装成 B 端用户能理解的概念：
- **Workspace**（工作空间）= 1:1 映射到多租户里的 Tenant，是 B 端隔离单元
- **Team Blueprint**（团队蓝图）= 一组"数字员工"角色定义（sales/researcher/writer/...）+ 版本+金丝雀
- **Scenario**（场景模板）= 可复用的工作流模板（包含 entry team、SLA、协作模式）
- **Business Run**（业务运行）= 一次场景执行的完整业务记录（标题/输入/结果/步骤/成本/审批）
- **Business Approval**（业务审批）= 高风险操作的持久化审批记录（带 SSE 通知）
- **SLA / DLQ / Human Override / Safety Valve**= 运行时治理
- **Template Gallery**= 预置+用户上传的 Agent/Scenario 模板（行业垂直）
- **Org AI-Native**= 身份/协商/权限/工作流/市场/成本/可观测/分布式路由/进化/合规/MultiModal
- **Metering**= Token/工具调用用量计量

**核心结论：业务层全部 JSON 文件持久化 + 进程内事件总线 + 内存状态**。这是一套"单 JVM 单磁盘"的业务系统，不是数据库驱动的服务——架构简单、易于开发，但多实例部署要重做持久化层。

## 2. Workspace 层（1 164 行）— 业务租户

| 类 | 行数 | 职责 |
|---|---|---|
| `WorkspaceService` | 156 | 创建/查询 workspace，1:1 映射到 Tenant |
| `WorkspaceRecord` | 60 | 数据对象：workspaceId/tenantId/name/description/owner/createdAt/metadata |
| `FileWorkspaceRepository` | 94 | JSON 文件持久化（原子写 tmp+rename），存 `~/.hermes/business/workspaces/{id}/workspace.json` |
| `WorkspaceDashboardIntegration` | 90 | HTTP endpoint handler（list/create/get） |
| `BusinessPortalDashboardIntegration` | 467 | Portal 专用 API：dashboard 统计、最近活动、agent 列表、scenario 下拉等 |
| `BusinessPortalExtendedIntegration` | 196 | 扩展 API（团队、template、协作、审批、runs 的聚合） |
| `BusinessEventSSEHandler` | 101 | 旧 SSE endpoint（`/api/v1/business/events/stream`）；新代码走 JarvisHandler 的 `/api/jarvis/stream` |

**关键设计**：
- Workspace 创建时自动调用 `tenantManager.createTenant(request)`，B 端隔离复用多租户层
- Workspace ID 校验：2–64 字符，仅字母/数字/点/下划线/横线
- ID sanitize：`[^a-zA-Z0-9._-]` 替换为 `_`（目录安全）
- DashboardIntegration 直接挂在 DashboardServer，没有独立 controller 层

## 3. Business Run（435+252+130+190+142 = 1 149 行）— 运行记录

| 类 | 行数 | 职责 |
|---|---|---|
| `BusinessRunService` | 435 | 核心 CRUD：createRun/updateRunStatus/listRuns/getRun + TRIAL run 创建 + 事件推送 |
| `BusinessRunRecord` | 130 | 数据对象：runId/workspaceId/teamId/teamVersion/scenarioId/taskTitle/taskInput/resultSummary/steps/status/tokensUsed/estimatedCost/sla*/approvalId/metrics/metadata/timestamps |
| `BusinessRunStep` | ~100 | 单步记录：title/body/status/startedAt/durationMs/evidence/actor |
| `FileBusinessRunRepository` | （模式同 FileWorkspaceRepository） | 存 `workspaces/{id}/runs/{runId}.json` |
| `BusinessRunDashboardIntegration` | 206 | HTTP 端点 |
| `BusinessRunProjectionAdapter` | 295 | 把 ScenarioOrchestrator.IntentRun（执行态）投影成 BusinessRunRecord（持久化态） |
| `RunEventBus` | 142 | 进程内事件总线（publish/subscribe，跟 BusinessEventBus 是两套） |
| `CompensationEngine` | ~80 | SLA 失败后的补偿（重试/回滚，基础实现） |

**状态常量**：RUNNING / COMPLETED / FAILED / NEEDS_APPROVAL / TRIAL

**来源**（metadata.source 字段）：manual / demo / smoke / foundation:intent-run / foundation:agent-trace

**成本字段**：tokensUsed（long）、estimatedCost（double），由 projection 填充，但 Dispatcher 没接 metering，实际都是 0。

## 4. Business Approval（370+419+139+215+190+237+182+183 = 1 935 行）— 审批

| 类 | 行数 | 职责 |
|---|---|---|
| `BusinessApprovalService` | 370 | 业务审批 CRUD：create/approve/reject/requestInfo/listApprovals，发布事件 |
| `BusinessApprovalRecord` | 180 | 数据对象（PENDING/APPROVED/REJECTED/INFO_REQUESTED + timeline 事件列表） |
| `FileBusinessApprovalRepository` | ~80 | JSON 持久化：`workspaces/{id}/approvals/{approvalId}.json` |
| `ToolApprovalCoordinator` | 215 | L3 协调：requestToolApproval（抛 ToolApprovalRequiredException + 挂 checkpoint）+ resumeToolApproval |
| `ApprovalStore`（接口）+ `LocalApprovalStore` | ~140 | **进程内** pending + callback（CHM）；Redis 版没接线 |
| `RedisApprovalStore` | 139 | 抽象了 Redis 存储，但缺 pom 依赖且无构造调用点——死代码 |
| `BusinessApprovalDashboardIntegration` | 419 | Portal 三色桶（pending/recent/stats）+ HTTP |
| `BusinessApprovalNotifier` | 237 | 事件→通知（目前只是日志级别，没接邮件/IM） |
| `BusinessApprovalAdapter` | 190 | 适配到 ToolApprovalCallback，让 agent 主循环可挂起 |

**3 级事件订阅**：global subscribers / workspaceSubscribers / approvalSubscribers（CopyOnWriteArrayList+CHM）。

⚠️ **观察**：
- FileBusinessApprovalRepository 做持久化，LocalApprovalStore 做"待审批内存索引"。新实例启动可以从 JSON 恢复历史审批，但**正在 pending 的 checkpoint 无法恢复**（TenantAwareAIAgent 里的 checkpoint 是纯内存字段）。
- RedisApprovalStore 类写了但没接线——又是"骨架先有"模式。

## 5. Scenario（场景模板）（621+93+171+156+170 = 1 211 行）

| 类 | 行数 | 职责 |
|---|---|---|
| `ScenarioService` | 621 | 场景 CRUD + **executeScenario()**——业务执行主入口 |
| `ScenarioRecord` | 77 | 数据对象：scenarioId/name/description/entryTeamId/slaName/collaborationPattern/successCriteria/... |
| `FileScenarioRepository` | 93 | JSON 持久化 |
| `ScenarioDashboardIntegration` | 156 | HTTP 端点 |
| `ScenarioIntentAdapter` | 170 | 把 Scenario 翻译成 ScenarioOrchestrator 能执行的 IntentRun（通过 modelClient） |
| `PlanReflectionService` | 171 | 场景执行后反思（用 LLM 打分+提建议） |

**executeScenario 关键链路**（阶段 3 提到过但这里补完）：
```
executeScenario(workspaceId, scenarioId, userInput)
│
├─ scenarioIntentAdapter 必须已接线（否则 throw IllegalStateException）
├─ teamBlueprintRuntime.ensureTeamRuntime()  启动团队 agent 实例（挂到 TenantBus）
├─ canaryVersion 路由（金丝雀发布）
├─ PolicyService 审批门控（checkApprovalRequired → 抛 ApprovalRequiredException）
├─ scenarioIntentAdapter.execute(scenario, userInput) → IntentRun
├─ BusinessRunProjectionAdapter.fromIntentRun(...) → BusinessRunRecord
├─ activeMemoryService.recall(...) 记忆注入
├─ runService.createRun(projection) 持久化
├─ startRunWatcher() 后台线程 200ms 轮询 IntentRun 状态，推 RUN_STARTED/STEP_*/RUN_COMPLETED/FAILED 事件
└─ 最终 runService.updateRunStatus 到终态
```

这是 Portal "跑第一笔任务" 按钮背后真实做的事。

## 6. Team Blueprint（团队蓝图）（282+359+206+93+169+131 = 1 240 行）

| 类 | 行数 | 职责 |
|---|---|---|
| `TeamBlueprintService` | 282 | 蓝图 CRUD、版本管理、activeVersion 切换 |
| `TeamBlueprintRecord` | 59 | 数据：teamId/name/description/agents（List<AgentBlueprintRecord>）/activeVersion/versions |
| `AgentBlueprintRecord` | 50 | 单个 agent 定义：agentId/role/skills/tools/systemPrompt/model |
| `FileTeamBlueprintRepository` | 93 | JSON 持久化：`workspaces/{id}/teams/{teamId}.json` |
| `TeamBlueprintCompiler` | 206 | 把蓝图编译成 RuntimeProfile 集合（校验 role/tool 存在、注入默认值） |
| `TeamBlueprintRuntime` | 359 | **核心**：ensureTeamRuntime 真正 new TenantAwareAIAgent.forBlueprint() 并挂到 TenantBus |
| `TeamBlueprintVersion` | 42 | 版本号对象 |
| `TeamBlueprintDashboardIntegration` | 169 | HTTP 端点 |
| `FoundationCapabilityValidator` | 299 | 校验蓝图是否满足场景的 capability 需求 |
| `FoundationCapabilityValidationReport` | 102 | 校验报告结构 |
| `QuickTeamBuilderService` | 251 | 用 LLM 从自然语言生成 TeamBlueprint（一键建团队） |
| `QuickTeamBuilderDashboardIntegration` | 131 | HTTP 端点 |

**TeamBlueprintRuntime.ensureTeamRuntime** 关键逻辑：
1. 三层嵌套 ConcurrentHashMap：`activeAgents[workspaceId][teamId][version] = Map<agentId, TenantAwareAIAgent>`
2. 通过 workspaceService.resolveTenantContext(workspaceId) 拿到 TenantContext
3. 对每个 AgentBlueprintRecord 调 `createAgentFromBlueprint(...)`：
   - new Role(agentId, roleName, level, capabilities, allowedTools, toolApprovalRules)
   - TenantAwareAIAgent.forBlueprint(tenantCtx, agentId, role, sessionId, config)
   - 注册到 TenantBus（消息订阅）
   - 设置 toolApprovalCallback（撞审批时回调到 ToolApprovalCoordinator）
4. version 做 DCL 懒启动，金丝雀版本可以并行存在

**金丝雀**：`resolveVersionForRequest()` 按 canaryReleaseService 配置的百分比做 sticky 路由（requestKey hash），一部分流量导到新版本。

## 7. 运行时治理（262+190+182+217+212 = 1 063 行）

| 类 | 行数 | 职责 |
|---|---|---|
| `SLAManager` | 262 | SLA 监控：attach(run, sla) 启动两个 ScheduledFuture，到 warnThresholdMs/breachThresholdMs 触发事件；支持 auto-retry 策略 |
| `DeadLetterQueue` | 190 | DLQ：失败 run 入队、retry、resolve；持久化到 `workspaces/{id}/dlq/*.json` |
| `HumanOverrideService` | 182 | 人工接管：pause/takeover/release，让人类接管 agent 执行 |
| `BusinessSafetyValveAdapter` | 217 | 熔断：错误率过高时暂停团队/场景 |
| `BusinessWorkflowService` | 212 | 工作流实例化（基于 WorkflowEngine 做 DAG 执行） |
| `CompensationEngine`（在 run/ 下） | ~80 | SLA breach 后补偿动作（重试/回滚/通知） |
| `ApprovalAnalytics`（在 business/analytics/） | ~120 | 审批统计（通过率/平均响应时间） |

SLA 超时事件最终通过 BusinessEventBus.slaWarn/slaBreach 推到前端，NOC 页能看到红/黄警报。

## 8. Template Gallery（218+209+136+204+183+317+245 = 1 512 行）

| 类 | 行数 | 职责 |
|---|---|---|
| `BusinessTemplateService` | 55 | 门面，包 AgentTemplateLoader |
| `AgentTemplateLoader` | 212 | 从 classpath `agents/`+`scenarios/`+用户目录加载 YAML 模板 |
| `AgentTemplate`/`ScenarioTemplate` | 218/199 | 模板数据对象（YAML 绑定） |
| `AgentTemplateMapper`/`ScenarioTemplateMapper` | 136/? | 模板→AgentBlueprintRecord/ScenarioRecord 映射 |
| `UserTemplateRepository` | 245 | 用户上传模板存 `~/.hermes/business/templates/agents/`、`scenarios/`（YAML） |
| `TemplateCloneService` | 317 | 一键 clone：选 ScenarioTemplate → 创建 workspace（可选）→ 创建 TeamBlueprint → 创建 Scenario → 返回 workspaceId |
| `BusinessTemplateDashboardIntegration` | 183 | HTTP 端点 |
| `BusinessIndustryDashboardIntegration` | 209 | 行业模板分类 |
| `BusinessRiskPolicyDashboardIntegration` | ? | 风险策略展示 |
| `EcommerceScenarioFactory` | 276 | 电商垂直场景工厂（预置 templates） |

**TemplateCloneService 是 H5→Portal 闭环的后端**：H5 注册后 POST `/api/scenario-templates/{id}/clone` → 创建 workspace + team + scenario，前端跳 `/business-portal?workspaceId=xxx&firstTime=1`。

## 9. Business Event Bus（135 行）— 进程内事件总线

- `CopyOnWriteArrayList<Consumer<BusinessEvent>> subscribers`
- 快捷方法：runStatusChanged / workflowCheckpoint / slaBreach / dlqEnqueued / takeoverRequested / approval* / insight*
- `for (sub : subscribers) { try sub.accept(e) } catch (logger.warn) }`——一个 subscriber 抛异常不影响别人
- 不持久化、不跨实例

**问题**：JarvisHandler 订阅 BusinessEventBus + BusinessApprovalService.subscribeGlobal，把事件转成 Jarvis Suggestion 推 SSE。但 BusinessEventBus 里 publish 的事件类型与 BusinessRunService 用的 RunEventBus 是**两套平行总线**——run 状态更新走 RunEventBus，SLA/DLQ/审批/洞察走 BusinessEventBus，Jarvis 订阅 BusinessEventBus 但 run 事件走 RunEventBus，会导致 Jarvis 浮窗收不到 run 状态更新？要在 DashboardServer wiring 里验证是否有 bridge。

## 10. Business Foundation Facade（172 行）— V1 API 门面

`BusinessPortalFoundationFacade` 聚合 v1 API：
- listTemplates / getTemplate / cloneTemplate
- listTeams / createTeam / listScenarios
- createRun / getRun / listRuns
- listApprovals / approve / reject
- getInsights / proposeEvolution

配套 `BusinessPortalAdapterRegistry`（适配器注册点）和 `BusinessPortalFoundationDiagnostics`（健康诊断）。

**v1 API 挂在 `/api/v1/business/*`**，Portal 新旧代码都能调。Dashboard 时代的老 API 还留着，两套并存。

## 11. Org（AI-Native Organization，5 847 行）— 12 个组织能力模块

全部是**进程内 ConcurrentHashMap 状态**，重启全丢，但功能实现度比想象的高（不是纯空壳）。

| 模块 | 类数/行数 | 实质能力 |
|---|---|---|
| **identity** | 2/428 | AgentIdentity（角色/能力/目标/自治度 0-1）+ AgentIdentityManager（CRUD，内存 Map） |
| **handoff** | 3/578 | HandoffProtocol（人机/agent 间交接请求+审批，超时自动处理）+ HandoffContext（Priority NORMAL/HIGH/CRITICAL） |
| **auth** | 2/515 | RBAC（角色→permission）+ ABAC（AttributeBasedAccessControl 策略匹配：priority/role/action/risk/time） |
| **knowledge** | 1/303 | OrganizationalKnowledgeBase：entries Map+按 type/tag/classification 索引，search 做简单关键词包含匹配（无 embedding） |
| **workflow** | 4/969 | Workflow（DAG 定义）+ WorkflowStep+WorkflowEngine（拓扑排序+parallel 并行执行+resume） |
| **market** | 3/712 | AgentTemplate（市场模板）+ AgentMarketplace（list/buy/install）+ **CostAttribution（token 成本分摊到 agent/department/team）** |
| **observe** | 2/453 | AgentTrace（OODA step+meta+error 记录）+ AgentObservability（CHM of traces+anomalies 环形缓冲+异常检测） |
| **distributed** | 3/590 | AgentRegistry（多 agent 注册/能力索引/心跳/cleanupStale）+ AgentRouter（route by capability/latency/load） |
| **evolution** | 1/237 | SelfEvolutionEngine：FailureCase 库+successPatterns+detectPatterns 共现频率统计（无 LLM 进化，只是统计） |
| **compliance** | - | 存在但行数很少（可能在 ABAC 里） |
| **multimodal** | 1/199 | MultiModalSession：text/image/audio 多模态会话上下文 |
| **eval** | 1/266 | AgentEvaluation：简单评分框架 |

**重要观察**：
- 模块都有真实逻辑，但**都是内存 CHM/List**，无持久化
- KnowledgeBase.search 是 substring 匹配，不是 embedding 语义检索
- SelfEvolutionEngine 只是失败模式计数，没有真的改代码/prompt
- AgentRegistry 宣称"across the cluster"但实际还是单 JVM 的 CHM——没有网络通信
- WorkflowEngine 的 DAG 执行是正经的拓扑排序+并行 CompletableFuture，这个是真东西

## 12. Metering（711 行）— 用量计量

| 类 | 行数 | 职责 |
|---|---|---|
| `MeteringService` | 99 | 门面：recordLlmCall/recordToolExec/recordSandboxUsage + queryUsage |
| `UsageStore`（接口） | 47 | 存储抽象（record/query/summary） |
| `InMemoryUsageStore` | 108 | 进程内 Map<tenantId,Map<day,UsageDay>> 实现 |
| `UsageEvent` | 74 | 事件数据 |
| `ModelPricing` | 54 | 模型价格表（input/output per-1k） |
| `UsagePricingService` | 174 | 按 ModelPricing 算金额 |
| `UsageInsightsService` | 155 | 用量聚合（按 agent/model/day/week 统计、趋势） |

⚠️ **MeteringService.recordLlmCall 没被 ModelClient/Agent loop 调用**——cost 字段全是 0。现在只有接口没接线。

## 13. 持久化全景

把所有业务层持久化点拉一张表：

| 数据 | 持久化方式 | 位置 | 多实例共享？ |
|---|---|---|---|
| Workspace | JSON 文件 | `workspaces/{id}/workspace.json` | ❌ 本地磁盘 |
| Team Blueprint | JSON 文件 | `workspaces/{id}/teams/{tid}.json` | ❌ |
| Scenario | JSON 文件 | `workspaces/{id}/scenarios/{sid}.json` | ❌ |
| Business Run | JSON 文件 | `workspaces/{id}/runs/{rid}.json` | ❌ |
| Business Approval | JSON 文件 | `workspaces/{id}/approvals/{aid}.json` | ❌ |
| DLQ items | JSON 文件 | `workspaces/{id}/dlq/*.json` | ❌ |
| User Templates | YAML 文件 | `templates/agents/`、`templates/scenarios/` | ❌ |
| 运行中 Run 状态/Steps | 内存 RunEventBus | CHM | ❌ |
| Pending Approval checkpoint | 内存 LocalApprovalStore + TenantAwareAIAgent | CHM + 字段 | ❌ |
| SLA 监控 | 内存 ScheduledFuture | SLAManager 字段 | ❌ |
| SSE subscribers | 内存 CopyOnWriteArrayList | BusinessEventBus | ❌ |
| Org 所有模块（identity/knowledge/workflow/...） | 内存 CHM | 各模块字段 | ❌ |
| Metering 用量 | 内存 InMemoryUsageStore | CHM | ❌ |
| Session（对话历史） | SQLite | `~/.hermes/sessions.db` | ❌（本地文件） |
| Tenant 数据 | JSON 文件 | `tenants/{tid}/state/*.json` | ❌ |

**确认**：业务层**完全没有数据库**，全部是本地文件系统+内存。pom 里的 sqlite-jdbc 只给 SessionManager 用。PostgresTenantRepository/Redis*Store/RedisApprovalStore 这些分布式持久化代码都是骨架，没接线、没依赖。

## 14. 调用关系（业务侧全景）

```
HTTP (DashboardServer :8080)
  │
  ├── /api/workspaces ─────────────► WorkspaceService
  ├── /api/teams ──────────────────► TeamBlueprintService
  ├── /api/scenarios ──────────────► ScenarioService
  ├── /api/runs ───────────────────► BusinessRunService
  ├── /api/approvals ──────────────► BusinessApprovalService
  ├── /api/templates ──────────────► BusinessTemplateService
  ├── /api/jarvis/chat ────────────► JarvisHandler → ChatService → TenantAwareAIAgent.processMessage()
  ├── /api/jarvis/stream ──────────► JarvisHandler SSE（订阅 BusinessEventBus + ApprovalService）
  └── /api/v1/business/* ──────────► BusinessPortalFoundationFacade
                                           │
                                           ▼
                         ┌─────── ScenarioService.executeScenario() ───────┐
                         │                                                 │
                         │  ┌─ ScenarioIntentAdapter (LLM 意图→执行计划)   │
                         │  ├─ TeamBlueprintRuntime.ensureTeamRuntime()    │
                         │  │    └─ new TenantAwareAIAgent.forBlueprint()   │
                         │  │        └─ 挂到 TenantBus                     │
                         │  ├─ PolicyService 审批门                         │
                         │  ├─ ScenarioOrchestrator.IntentRun              │
                         │  ├─ BusinessRunProjectionAdapter                │
                         │  ├─ BusinessRunService.createRun (JSON 落盘)    │
                         │  └─ RunWatcher 线程 200ms 轮询 → RunEventBus     │
                         │                                                 │
                         └─→ BusinessEventBus.publish() ──→ Jarvis SSE     │
                                                                           │
              底层 agent 执行：                                            │
              TenantAwareAIAgent.processMessage() ─► ToolDispatcher         │
                                                          │                │
                                                          ▼                │
                                                   关卡 0-8 (阶段 3) ──────┘
```

## 15. 阶段 4 识别到的债/风险

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🔴 | **业务层零数据库** | 所有 Repository | JSON 文件+内存状态。多实例部署业务数据立即分裂，DLQ/SLA/审批/RUNNING 状态全丢 |
| 🔴 | MeteringService 没接 ModelClient/Agent loop | MeteringService | tokensUsed/estimatedCost 永远是 0，成本统计是空的 |
| 🟠 | 两套平行事件总线（RunEventBus vs BusinessEventBus） | business.event vs business.run | Jarvis SSE 只订阅 BusinessEventBus，可能收不到 run 状态更新；需要 bridge 或合并 |
| 🟠 | Running Run 状态全内存，重启丢失 | RunEventBus、watcher 线程 | JVM 重启后所有 RUNNING run 永远停在 RUNNING 状态，没有恢复机制 |
| 🟠 | DLQ retry/resolution 是文件+内存混合状态 | DLQ | 重启后 retry 计数可能不准 |
| 🟡 | RedisApprovalStore/PostgresTenantRepository/DistributedSessionManager 都是死代码 | 各包 | 跟阶段 2 发现的 RedisQuotaStore 是同一模式——"分布式骨架先写了但没接" |
| 🟡 | BusinessNotifier 只写日志，没真正发邮件/IM | BusinessApprovalNotifier | 审批通知不会真正触达用户，只能靠前端轮询/SSE |
| 🟡 | KnowledgeBase.search 是 substring 匹配，没接 embedding | OrganizationalKnowledgeBase | 知识库语义检索是空架子 |
| 🟡 | AgentRegistry/Distributed 模块名"cluster"但实际单 JVM | org.distributed | 误导；"跨集群"能力没有网络层 |
| 🟡 | Org 12 个模块全内存无持久化 | org/* | JVM 重启所有身份/知识/工作流/成本/进化数据全丢 |
| 🟡 | ScenarioService 大量 setter 注入（setPolicyService/setPlanReflectionService/...） | ScenarioService | 典型循环依赖+setter 注入，测试/启动顺序脆弱 |
| 🟢 | WorkspaceId sanitize 是 replace 不是 reject（非法字符变下划线） | FileWorkspaceRepository.sanitize | 可能导致不同 ID 映射到同一目录（ab/c 和 ab_c 同名） |
| 🟢 | v1 business API 和三套 DashboardIntegration 并存 | business/run/DashboardIntegration + foundation facade | HTTP API 重复暴露，维护负担 |

## 16. 阶段 4 小结

**业务层成熟度：Beta（75%）单实例 / Pre-Alpha（20%）多实例**

单实例场景下：
- ✅ Workspace→Tenant 1:1 隔离复用了多租户层
- ✅ Scenario→TeamBlueprint→Run 闭环完整（H5 一键 clone 能跑通）
- ✅ 审批/DLQ/SLA/金丝雀/HITL 都有真实实现
- ✅ TemplateCloneService 是 H5→Portal 闭环的关键后端
- ✅ Org 模块比预想的多（12 个），WorkflowEngine 的 DAG 执行、CostAttribution 成本分摊、ABAC 权限这些是真东西
- ✅ FileRepository 都用原子写（tmp+rename），崩溃不会半截文件

但：
- ❌ 持久化全 JSON+内存，Beta 内部用 OK，GA 生产必须接 Postgres/Redis
- ❌ Metering 没接线
- ❌ 两套事件总线需要合并
- ❌ 运行中状态无恢复
- ❌ Redis/Postgres 分布式骨架全是死代码
- ❌ Notifier 只写日志，没真正发通知

### 下一步
→ **阶段 5：生态层与扩展**（plugin/hooks、skills、acp、collaboration、evolution、learning、gateway、connector）——理解插件系统、子 agent 协议 ACP、多 agent 协作总线、Gateway 路由、外部 connector 是怎么接上的。
