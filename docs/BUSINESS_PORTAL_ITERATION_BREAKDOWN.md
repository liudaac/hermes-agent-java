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
