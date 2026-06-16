# Business Portal MVP 迭代拆解

本文档把 `BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md` 的“下一刀”拆成可开发、可测试、可验收的步骤。当前迭代基于 `main` 分支进行，远端备份分支为 `backup/2026-06-16`。

## 0. 当前迭代目标

先跑通最小业务闭环：

```text
创建业务空间
  ↓
自动创建/绑定底层 Tenant
  ↓
创建智能体团队蓝图 v1
  ↓
基于 v1 创建 v2 草稿
  ↓
激活 v2
  ↓
API 能支撑 Business Portal 的团队入口和后续五个入口扩展
```

本轮不做复杂 UI，不做真实任务运行，不做进化提案执行；但 API、对象模型、文件持久化和测试必须先定住。

## 1. 迭代顺序

### Step 1：Workspace façade

目标：给业务用户一个“业务空间”对象，不让业务入口直接暴露 Tenant。

交付：

```text
[x] WorkspaceRecord
[x] WorkspaceService
[x] FileWorkspaceRepository
[x] WorkspaceDashboardIntegration
[x] GET /api/v1/workspaces
[x] POST /api/v1/workspaces
[x] GET /api/v1/workspaces/{workspaceId}
[x] POST /api/v1/workspaces 自动创建底层 tenant
[x] 测试：创建 workspace 自动创建 tenant
[x] 测试：重复 workspace 返回明确错误
```

核心约束：

```text
workspaceId 是业务空间 ID
tenantId 是底层隔离 ID
第一版默认 workspaceId == tenantId，但响应中两个字段都保留
```

### Step 2：Team Blueprint Versioning

目标：让业务团队配置具备版本化和可回滚基础。

交付：

```text
[x] AgentBlueprintRecord
[x] TeamBlueprintVersion
[x] TeamBlueprintRecord
[x] FileTeamBlueprintRepository
[x] TeamBlueprintService
[x] GET /api/v1/workspaces/{workspaceId}/team-blueprints
[x] POST /api/v1/workspaces/{workspaceId}/team-blueprints
[x] GET /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
[x] POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
[x] POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions/{version}/activate
[x] 测试：创建 blueprint v1 ACTIVE
[x] 测试：创建 v2 DRAFT
[x] 测试：激活 v2 后 activeVersion 切换
[ ] 测试：ACTIVE version 不允许原地修改（下一步补“更新版本”API 时实现；当前没有原地修改接口）
```

### Step 3：Prompt Asset / Team Operating Manual 占位

目标：先在版本对象里预留提示词资产和团队操作手册字段，后续接 Prompt Stack。

交付：

```text
[x] TeamBlueprintVersion.promptAssetRefs
[x] TeamBlueprintVersion.operatingManual
[x] API 响应包含 promptAssets / operatingManual
[x] 测试：团队蓝图能关联 prompt asset refs
```

### Step 4：Business Portal 五入口 API 占位

目标：让前端可以先搭业务入口壳，不等运行系统完全完成。

交付：

```text
[x] GET /api/v1/business/home 返回真实 workspace/team 统计和空状态
[x] GET /api/v1/business/approvals 返回空状态
[x] GET /api/v1/business/runs 返回空状态
[x] GET /api/v1/business/insights 返回空状态
[x] GET /api/v1/business/teams 复用 blueprint 数据
```


### Step 4.5：Approval Center / 移动端审批卡

目标：让 Business Portal 的“审批”入口从空状态升级为可真实创建、查询、处理的业务审批卡。

交付：

```text
[x] BusinessApprovalRecord
[x] FileBusinessApprovalRepository
[x] BusinessApprovalService
[x] BusinessApprovalDashboardIntegration
[x] GET /api/v1/workspaces/{workspaceId}/approvals
[x] POST /api/v1/workspaces/{workspaceId}/approvals
[x] GET /api/v1/workspaces/{workspaceId}/approvals/{approvalId}
[x] POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/approve
[x] POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/reject
[x] POST /api/v1/workspaces/{workspaceId}/approvals/{approvalId}/request-info
[x] GET /api/v1/business/approvals 读取真实审批卡，不再只是空状态
[x] 测试：创建审批卡
[x] 测试：同意 / 拒绝 / 要求补充信息
[x] 测试：已处理审批不能重复处理
```

移动端审批卡字段：

```text
这是什么事：title / summary
为什么需要审批：reasonRequired
如果同意会发生什么：approveEffect
如果拒绝会发生什么：rejectEffect
系统推荐怎么做：recommendation
风险等级：riskLevel
相关依据：evidence
操作按钮：approve / reject / request-info
```


### Step 4.6：Business Run API / 业务故事化 Trace

目标：让 Business Portal 的“运行”入口从空状态升级为真实业务运行记录，并默认展示业务能看懂的故事化 Trace。

交付：

```text
[x] BusinessRunRecord
[x] BusinessRunStep
[x] FileBusinessRunRepository
[x] BusinessRunService
[x] BusinessRunDashboardIntegration
[x] GET /api/v1/workspaces/{workspaceId}/runs
[x] POST /api/v1/workspaces/{workspaceId}/runs
[x] GET /api/v1/workspaces/{workspaceId}/runs/{runId}
[x] GET /api/v1/business/runs 读取真实运行记录，不再只是空状态
[x] 测试：创建业务运行记录
[x] 测试：按 workspace/team/status 查询运行记录
[x] 测试：读取运行详情
```

业务故事化 Trace 字段：

```text
任务是什么：taskTitle / taskInput
结果是什么：resultSummary
为什么得出这个结论：conclusionReason
系统做了什么：systemAction
风险判断是什么：riskJudgement
后续建议是什么：nextSuggestion
业务步骤：steps[].title / summary / actor / evidence / status
技术追溯：technicalTraceRef（默认不在业务层展开）
```


### Step 4.7：Business Insights API / 最小真实洞察

目标：让 Business Portal 的“洞察”入口从空状态升级为基于 workspace、team blueprint、runs、approvals 的真实聚合洞察。

交付：

```text
[x] BusinessInsightRecord
[x] BusinessInsightSummary
[x] BusinessInsightService
[x] BusinessInsightDashboardIntegration
[x] GET /api/v1/business/insights
[x] GET /api/v1/workspaces/{workspaceId}/insights
[x] 洞察指标：workspaceCount / teamCount / runCount / failedRunCount / pendingApprovalCount / highRiskApprovalCount / failureRate
[x] 洞察建议：创建 workspace / 创建团队 / 试运行 / 处理审批 / 复盘失败运行 / 继续积累样本
[x] 测试：空系统建议创建 workspace
[x] 测试：有 workspace 无团队建议创建团队
[x] 测试：失败运行和高风险审批生成对应洞察
```

最小洞察表达：

```text
发现：finding
可能原因：possibleCause
建议动作：recommendation
预期收益：expectedBenefit
下一步动作：suggestedAction
严重程度：severity
支撑指标：metrics
```


### Step 4.8：首页聚合升级 / 业务驾驶舱

目标：让 `/api/v1/business/home` 不再只是 workspace/team 基础计数，而是聚合 runs、approvals、insights 的业务驾驶舱。

交付：

```text
[x] BusinessPortalDashboardIntegration.home 注入 BusinessInsightService
[x] 首页 summary 包含 workspaceCount / teamCount / runCount / pendingApprovals / openInsights
[x] 首页 today 包含 processedTasks / failedRuns / needsApprovalRuns / pendingApprovals / highRiskApprovals / failureRate / autoCompletionRate
[x] 首页 needsAttention 聚合待审批、高风险审批、失败运行
[x] 首页 risk 返回 LOW / MEDIUM / HIGH
[x] 首页 teamStatus 返回 total / normal / needsAttention / emptyState
[x] 首页 insights 复用 BusinessInsightService 的真实洞察
[x] 首页 nextActions 复用 BusinessInsightService 的建议动作
```

首页现在负责回答：

```text
今天整体是否正常？
哪里需要我处理？
风险高不高？
团队状态如何？
系统建议我下一步做什么？
```


### Step 4.9：Business Portal 前端页面壳联调

目标：把已闭环的 Business Portal 后端五入口接入现有 React/Vite Dashboard UI，形成一个业务驾驶舱页面。

交付：

```text
[x] 新增 web/src/pages/BusinessPortalPage.tsx
[x] App.tsx 新增 /business 路由和 Business 导航入口
[x] web/src/lib/api.ts 新增 Business Portal API client 方法
[x] 前端页面接入 /api/v1/business/home
[x] 前端页面接入 /api/v1/business/teams
[x] 前端页面接入 /api/v1/business/runs
[x] 前端页面接入 /api/v1/business/approvals
[x] 前端页面接入 /api/v1/business/insights
[x] 页面展示 summary / today / needsAttention / risk / teamStatus / teams / runs / approvals / insights / nextActions
[x] 支持 workspace 下拉过滤和手动刷新
[x] npm run build 通过
```

页面定位：

```text
不是技术 Dashboard，而是业务驾驶舱：
- 今天整体是否正常
- 哪些地方需要处理
- 哪些团队在运行
- 最近运行为什么得出结论
- 哪些审批需要看
- 系统建议下一步做什么
```


### Step 4.10：真实 smoke 联调与截图检查准备

目标：用真实 Dashboard 服务跑通 Business Portal 后端闭环，并记录 UI 截图检查状态。

交付：

```text
[x] mvn -q -DskipTests package 成功生成 fat jar
[x] Dashboard 使用 java -jar target/hermes-agent-java-0.1.0-SNAPSHOT.jar dashboard --port 9119 --host 127.0.0.1 启动成功
[x] scripts/smoke-business-portal.sh 指向 http://127.0.0.1:9119 跑通
[x] smoke 成功创建 workspace / team blueprint / run / approval
[x] smoke 成功 approve approval card
[x] smoke 成功读取 home / teams / runs / approvals / insights
[x] 新增 docs/BUSINESS_PORTAL_SMOKE_RESULT.md 记录联调结果
[ ] 浏览器截图检查（当前环境无可用浏览器，Kimi WebBridge extension_connected=false）
```

smoke 结果摘要：

```text
Workspace: customer-service-demo
Team:      after-sales-team
Run:       run-05dfe880-c
Approval:  apv-cf8a7f43-d
Home risk: LOW
Insights:  1
OK
```


### Step 4.11：Business Portal 前端组件拆分与稳定性优化

目标：在不依赖浏览器截图的情况下，提升 `/business` 页面代码可维护性和字段缺失防御能力。

交付：

```text
[x] 新增 web/src/components/business/BusinessPortalSections.tsx
[x] 抽出 MetricCard / MiniStat / ActionList / EmptyLine
[x] 抽出 TodayAndAttentionSection
[x] 抽出 TeamsSection
[x] 抽出 RunsAndApprovalsSection
[x] 抽出 InsightsAndActionsSection
[x] 页面保留数据加载与 workspace 过滤逻辑，展示逻辑下沉到组件
[x] 展示组件增加缺字段 fallback，避免空字符串/null 导致 UI 直接异常
[x] cd web && npm run build 通过
```

当前限制：

```text
浏览器截图检查仍暂停；用户暂时不连接浏览器。
```


### Step 4.12：页面内 demo 数据引导入口

目标：在不让浏览器直接执行本地脚本的前提下，让 `/business` 页面告诉用户如何快速填充 demo 数据。

交付：

```text
[x] BusinessPortalSections.tsx 新增 DemoDataGuide
[x] /business 页面展示 smoke 脚本命令
[x] 命令根据当前 workspaceId 自动替换 WORKSPACE_ID，未选择时使用 customer-service-demo
[x] 说明 smoke 会创建 workspace / team blueprint / run story / approval / insights
[x] 明确说明为什么不是按钮：浏览器不直接执行本地脚本，避免安全边界混乱
[x] cd web && npm run build 通过
```


### Step 4.13：Run / Approval / Insight 详情展开

目标：让 `/business` 页面不只是展示摘要，也能在卡片内展开业务依据、动作影响和指标细节。

交付：

```text
[x] Run Story 卡片新增 details 展开区
[x] Run 展示 taskInput / conclusionReason / systemAction / riskJudgement / nextSuggestion / technicalTraceRef
[x] Run 展示 steps 和 metrics JSON preview
[x] Approval 卡片新增 details 展开区
[x] Approval 展示 reasonRequired / approveEffect / rejectEffect / recommendation / resolvedBy / resolutionReason / requestedInfo
[x] Approval 展示 evidence JSON preview
[x] Insight 卡片新增 details 展开区
[x] Insight 展示 possibleCause / expectedBenefit / suggestedAction / metrics
[x] web/src/lib/api.ts 补充详情字段类型
[x] cd web && npm run build 通过
```

实现方式：

```text
使用原生 <details>/<summary>，不增加额外状态管理；默认仍保持摘要卡片，用户需要时展开。
```


### Step 4.14：Create Workspace 表单 MVP

目标：让业务用户可以在 `/business` 页面直接创建第一个业务空间，而不是只能通过 smoke 脚本或 curl。

交付：

```text
[x] web/src/lib/api.ts 新增 createBusinessWorkspace
[x] 新增 CreateBusinessWorkspacePayload / CreateBusinessWorkspaceResponse 类型
[x] 新增 web/src/components/business/BusinessPortalForms.tsx
[x] 新增 CreateWorkspaceForm
[x] 表单字段：workspaceId / name / owner / description
[x] 创建成功后自动切换到新 workspace，并触发页面刷新
[x] 创建失败时在表单内展示错误
[x] metadata 标记 source=business-portal-ui
[x] cd web && npm run build 通过
```

当前范围：

```text
只做 Workspace 创建；Team / Run / Approval 创建表单后续分步补齐。
```


### Step 4.15：Create Team Blueprint 表单 MVP

目标：让业务用户可以在 `/business` 页面完成 `Create Workspace → Create Team` 的第二步。

交付：

```text
[x] web/src/lib/api.ts 新增 createBusinessTeamBlueprint
[x] 新增 CreateBusinessTeamBlueprintPayload / CreateBusinessTeamBlueprintResponse / AgentBlueprintPayload 类型
[x] CreateTeamBlueprintForm 接入 /business 页面
[x] 表单字段：teamId / name / scenario / description / operatingManual
[x] 未选择 workspace 时禁用团队创建，并提示先选择或创建 workspace
[x] 默认生成一个基础 Agent role card：business-analyst
[x] 默认 promptAssetRefs: prompt://business-portal/default-team
[x] metadata 标记 source=business-portal-ui
[x] 创建成功后刷新页面数据
[x] cd web && npm run build 通过
```

当前范围：

```text
只做最小团队蓝图创建；复杂岗位编辑器、版本草稿编辑和激活切换后续再补。
```


### Step 4.16：Create Run Story 表单 MVP

目标：让业务用户可以在 `/business` 页面完成 `Create Workspace → Create Team → Create Run Story`。

交付：

```text
[x] web/src/lib/api.ts 新增 createBusinessRun
[x] 新增 CreateBusinessRunPayload / CreateBusinessRunResponse 类型
[x] CreateRunStoryForm 接入 /business 页面
[x] 表单字段：team / status / taskTitle / taskInput / resultSummary / conclusionReason
[x] 未选择 workspace 或没有 team 时禁用 run 创建，并提示先创建 team
[x] 默认生成一条 BusinessRunStep
[x] 默认 systemAction / riskJudgement / nextSuggestion / technicalTraceRef
[x] metadata 和 metrics 标记 source=business-portal-ui
[x] 创建成功后刷新页面数据
[x] cd web && npm run build 通过
```

当前范围：

```text
只做最小运行故事创建；复杂步骤编辑、真实 Agent 执行绑定和技术 Trace 关联后续再补。
```


### Step 4.17：Create Approval Card 表单 MVP

目标：让业务用户可以在 `/business` 页面完成 `Create Workspace → Create Team → Create Run Story → Create Approval Card` 的完整基础闭环。

交付：

```text
[x] web/src/lib/api.ts 新增 createBusinessApproval
[x] 新增 CreateBusinessApprovalPayload / CreateBusinessApprovalResponse 类型
[x] CreateApprovalCardForm 接入 /business 页面
[x] 表单字段：team / riskLevel / title / summary / reasonRequired / recommendation / approveEffect / rejectEffect
[x] 未选择 workspace 或没有 team 时禁用 approval 创建，并提示先创建 team
[x] evidence 和 metadata 标记 source=business-portal-ui
[x] 创建成功后刷新页面数据
[x] cd web && npm run build 通过
```

当前范围：

```text
只创建审批卡；approve / reject / request-info 页面动作下一步再补。
```


### Step 4.18：Approval 操作按钮 MVP

目标：让业务用户可以在 `/business` 页面完成 `创建审批卡 → 处理审批卡 → 首页/洞察联动刷新`。

交付：

```text
[x] web/src/lib/api.ts 新增 approveBusinessApproval
[x] web/src/lib/api.ts 新增 rejectBusinessApproval
[x] web/src/lib/api.ts 新增 requestBusinessApprovalInfo
[x] 新增 ResolveBusinessApprovalPayload / RequestBusinessApprovalInfoPayload / ResolveBusinessApprovalResponse 类型
[x] ApprovalRow 对 PENDING 审批显示 Approve / Reject / Request info 操作
[x] Request info 支持简短输入框
[x] 操作中按钮禁用并显示 working 状态
[x] 操作成功后刷新页面数据
[x] cd web && npm run build 通过
```

当前范围：

```text
Approve/Reject 使用固定 reason：from Business Portal UI。
Request info 支持用户输入 requestedInfo。
更复杂的审批理由、二次确认和高风险确认短语后续再补。
```


### Step 4.19：创建区折叠/分组优化

目标：避免 `/business` 页面顶部被多个创建表单淹没，把创建能力组织成一个业务对象创建区。

交付：

```text
[x] 新增 BusinessCreationPanel
[x] 将 Create Workspace / Team Blueprint / Run Story / Approval Card 放入四个 details 折叠面板
[x] 默认展开逻辑：无 workspace 时展开 Workspace；有 workspace 但无 team 时展开 Team
[x] Run / Approval 创建区默认收起
[x] 页面主文件只渲染一个 BusinessCreationPanel，降低表单堆叠感
[x] 修复 ReactNode type-only import
[x] cd web && npm run build 通过
```


### Step 4.20：增强 smoke 覆盖三类审批动作

目标：让 smoke 脚本覆盖当前页面支持的审批处理能力，而不是只验证单一 approval action。

交付：

```text
[x] scripts/smoke-business-portal.sh 支持 APPROVAL_ACTION=all
[x] all 模式创建三张审批卡
[x] all 模式分别执行 approve / reject / request-info
[x] 保留旧模式：approve / reject / request-info / none
[x] docs/BUSINESS_PORTAL_API_EXAMPLES.md 记录 all 模式
[x] docs/BUSINESS_PORTAL_SMOKE_RESULT.md 记录真实 all 模式 smoke 结果
[x] 真实启动 Dashboard 并执行 APPROVAL_ACTION=all 成功
```

真实 smoke 结果摘要：

```text
Workspace: customer-service-demo
Team:      after-sales-team
Run:       run-86b3a85c-a
Approvals: apv-8e2d5cf1-3 apv-dcfc9b72-e apv-15d53506-4
Home risk: LOW
Insights: 1
OK
```


### Step 4.21：Business Portal 当前状态总结文档

目标：把已经完成的后端、前端、smoke、限制和下一阶段路线图收拢成一份可读总览，方便后续验收和接手。

交付：

```text
[x] 新增 docs/BUSINESS_PORTAL_CURRENT_STATE.md
[x] 总结 Backend API 能力
[x] 总结 Frontend /business 页面能力
[x] 总结 create forms 和 approval actions
[x] 总结 smoke 脚本与最新真实 smoke 结果
[x] 记录浏览器截图检查暂停原因
[x] 记录当前 MVP 限制
[x] 给出下一阶段路线图
[x] 列出关键文件和当前提交链
```


### Step 4.22：P1 表单质量优化第一轮

目标：改善 `/business` 页面表单的可用性，减少长文本被单行输入框挤压，并让审批处理理由可编辑。

交付：

```text
[x] 新增 TextAreaField 复用组件
[x] Workspace description 改为 textarea
[x] Team description / operatingManual 改为 textarea
[x] Run taskInput / resultSummary / conclusionReason 改为 textarea
[x] Approval summary / reasonRequired / recommendation / approveEffect / rejectEffect 改为 textarea
[x] Workspace ID 增加格式提示
[x] Team ID 增加格式提示
[x] Approval approve/reject 操作增加 reason 输入框
[x] approve/reject 不再只能使用固定 reason
[x] cd web && npm run build 通过
```


### Step 4.23：P1 表单质量优化第二轮

目标：增强 `/business` 页面表单的客户端校验和错误友好度，减少无效请求和难懂错误。

交付：

```text
[x] 新增 BUSINESS_ID_PATTERN / BUSINESS_ID_HELP
[x] 新增 isValidBusinessId
[x] 新增 friendlyBusinessError
[x] Workspace ID 提交前做客户端格式校验
[x] Team ID 提交前做客户端格式校验
[x] Workspace duplicate / invalid / not found 错误转为友好提示
[x] Team duplicate / invalid / not found 错误转为友好提示
[x] cd web && npm run build 通过
```

校验规则：

```text
2-64 chars: letters, numbers, dot, underscore or dash. Start with a letter or number.
```


### Step 4.24：HIGH / CRITICAL Approval 确认短语

目标：补齐高风险审批防误触安全阀，让 HIGH / CRITICAL 审批在 Approve / Reject 前必须输入确认短语。

交付：

```text
[x] ApprovalRow 检测 riskLevel=HIGH / CRITICAL
[x] HIGH / CRITICAL Approve 前要求输入 APPROVE HIGH / APPROVE CRITICAL
[x] HIGH / CRITICAL Reject 前要求输入 REJECT HIGH / REJECT CRITICAL
[x] 未输入正确确认短语时禁用 Approve / Reject 按钮
[x] LOW / MEDIUM 审批不受确认短语影响
[x] 保留可编辑 approve/reject reason
[x] cd web && npm run build 通过
```

这一步对应原方案中的安全要求：

```text
高风险：要求输入确认短语或走多人审批
```


### Step 4.25：Scenario 对象后端第一版

目标：从 UI 表单小步快跑收敛回平台核心，把“业务场景”从散落字符串升级为正式业务对象。

交付：

```text
[x] 新增 ScenarioRecord
[x] 新增 FileScenarioRepository
[x] 新增 ScenarioService
[x] 新增 ScenarioDashboardIntegration
[x] DashboardServer 注册 ScenarioDashboardIntegration
[x] GET /api/v1/workspaces/{workspaceId}/scenarios
[x] POST /api/v1/workspaces/{workspaceId}/scenarios
[x] GET /api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
[x] 文件持久化路径：$HERMES_HOME/business/workspaces/{workspaceId}/scenarios/{scenarioId}.json
[x] 新增 ScenarioServiceTest
[x] 测试：创建 / 列表 / 详情 / duplicate / missing workspace
[x] mvn -q -Dtest=ScenarioServiceTest,WorkspaceServiceTest,TeamBlueprintServiceTest test 通过
```

当前范围：

```text
Scenario 先作为正式业务对象落地；Team Blueprint / Run / Insights 与 Scenario 的强绑定下一步再补。
```


### Step 4.26：Scenario 绑定 Team / Run / Insights 第一版

目标：让 Scenario 从独立对象开始进入业务闭环，成为 Team Blueprint、Run Story 和 Insight 聚合的业务骨架。

交付：

```text
[x] TeamBlueprintRecord 新增 scenarioId
[x] TeamBlueprintService / TeamBlueprintDashboardIntegration 创建 team 时接收 scenarioId
[x] BusinessRunRecord 新增 scenarioId
[x] BusinessRunService / BusinessRunDashboardIntegration 创建 run 时接收 scenarioId
[x] BusinessRunService 支持按 scenarioId 过滤 run
[x] FileBusinessRunRepository 支持 scenarioId 过滤
[x] BusinessInsightService 支持 summarize(workspaceId, scenarioId)
[x] BusinessInsightDashboardIntegration 支持 queryParam scenarioId
[x] 前端 BusinessTeamCard / CreateBusinessTeamBlueprintPayload / BusinessRunRecord / CreateBusinessRunPayload 补 scenarioId 类型
[x] CreateTeamBlueprintForm 可输入 Scenario ID
[x] CreateRunStoryForm 自动从 selected team 透传 scenarioId
[x] targeted backend tests 通过
[x] cd web && npm run build 通过
```

当前范围：

```text
Scenario 已能绑定 Team 和 Run，并用于 Insights 运行记录过滤。
下一步可以继续做 Scenario UI 列表/创建，或把现有 Team/Run UI 增加 Scenario 过滤器。
```


### Step 4.27：Scenario UI 最小接入

目标：让业务用户能在 `/business` 页面看到、创建并选择 Scenario，让 Scenario 从后端对象进入业务工作台。

交付：

```text
[x] web/src/lib/api.ts 新增 getBusinessScenarios
[x] web/src/lib/api.ts 新增 createBusinessScenario
[x] 新增 BusinessScenarioRecord / BusinessScenariosResponse / CreateBusinessScenarioPayload / CreateBusinessScenarioResponse 类型
[x] BusinessPortalPage 新增 scenarios state
[x] BusinessPortalPage 新增 scenarioId 过滤 state
[x] workspace 切换时清空 scenarioId
[x] 顶部新增 Scenario 下拉过滤器
[x] getBusinessRuns / getBusinessInsights 透传 scenarioId
[x] 新增 CreateScenarioForm
[x] BusinessCreationPanel 增加 Scenario 折叠项
[x] 新增 ScenariosSection 展示 scenarios
[x] Team 卡片展示 scenarioId
[x] BusinessPortalDashboardIntegration /api/v1/business/runs 支持 queryParam scenarioId
[x] cd web && npm run build 通过
```

当前范围：

```text
Scenario 已可在 UI 中创建、查看、选择，并用于 Runs / Insights 过滤。
Home 聚合仍保持 workspace 级口径，后续如有需要再增加 scenario-aware home。
```

### Step 5：文档与验收

交付：

```text
[ ] 更新 BUSINESS_AGENT_TEAM_PLATFORM_PLAN.md 的 Checklist 状态（提交前）
[ ] 补充 API 示例
[x] mvn test 通过新增测试
[ ] git commit
```

## 2. 文件持久化约定

第一版使用文件持久化，路径放在 Hermes Home：

```text
$HERMES_HOME/business/workspaces/{workspaceId}/workspace.json
$HERMES_HOME/business/workspaces/{workspaceId}/team-blueprints/{teamId}.json
```

写入要求：

```text
先写 .tmp
再原子 move 到目标文件
读写使用 Jackson
时间使用 Instant
```

## 3. API 响应风格

统一返回业务字段，避免技术对象泄漏：

```json
{
  "ok": true,
  "workspaceId": "customer-service",
  "tenantId": "customer-service",
  "message": "Workspace created"
}
```

错误响应至少包含：

```json
{
  "ok": false,
  "error": "Workspace already exists",
  "workspaceId": "customer-service"
}
```

## 4. 当前优先级

本轮优先完成：

```text
P0: Workspace façade
P0: Team Blueprint Versioning
P1: Prompt Asset / Team Operating Manual 占位
P1: Business Portal 五入口空状态 API
```
