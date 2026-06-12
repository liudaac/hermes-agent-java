# Hermes Agent Java：业务智能体团队平台方案

> 目标：将 hermes-agent-java 从“开发者自用 Agent 控制台”演进为“可服务器端部署、面向业务自助创建智能体团队、支持自动编排与受控自进化的智能组织平台”。

## 1. 背景与目标

当前 hermes-agent-java 已经具备多租户、Agent 团队、Org Control Center、Skill、BrowserBridge、Delegated Task、安全审计等基础能力。但这些能力更多还是工程层面的模块。

下一阶段的目标是将这些能力产品化，让业务方能够：

1. 在服务器端创建自己的业务空间；
2. 自助创建属于自己的智能体团队；
3. 为智能体团队配置技能、工具、知识库、业务数据源和审批规则；
4. 通过 API 或 Dashboard 调用智能体团队完成业务任务；
5. 基于运行 Trace、评估集和人工反馈，让系统持续提出进化建议；
6. 在版本化、评估、审批、灰度和回滚约束下实现可控自进化。

一句话愿景：

> 业务定义目标和边界，系统自动组织智能体团队执行任务，并在治理约束下持续自进化。

---

## 2. 总体定位

平台不只是“Agent 执行器”，而是一个智能组织操作系统。

整体分三层：

```text
业务层：Workspace / Scenario / 目标 / 权限 / 数据源
编排层：Agent Team / Orchestrator / Workflow / Tool & Skill Routing
进化层：Eval / Trace / Feedback / Evolution Proposal / Versioning / Canary
```

核心原则：

```text
业务方定义：场景、目标、成功标准、权限边界、审批规则
系统负责：自动拆解任务、选择智能体、调用工具、记录过程、生成结果
进化系统负责：发现问题、生成优化建议、跑评估、提出版本草案
人或治理策略负责：审批高风险变更、灰度发布、回滚
```

不允许：

```text
AI 自己直接改生产智能体、直接扩大权限、直接发布高风险变更
```

推荐模式：

```text
AI 生成进化提案
  ↓
评估验证
  ↓
安全检查
  ↓
人工/策略审批
  ↓
版本化发布
```

---

## 3. 当前已有基础

### 3.1 多租户基础

现有模块：

```text
TenantManager
TenantContext
TenantFileSandbox
TenantProcessSandbox
TenantNetworkSandbox
TenantQuotaManager
TenantAuditLogger
TenantSessionManager
TenantMemoryManager
TenantSkillManager
```

未来映射：

```text
Workspace = Tenant 的业务化包装
```

每个业务方/业务线/业务场景可以拥有独立 Workspace，对应底层 Tenant。

---

### 3.2 Agent 组织基础

现有模块：

```text
TeamManager
AgentRole
IntentOrchestrator
CapabilityScorer
OrgManage
OrgControlCenter
AgentTrace
Audit
```

未来演进：

```text
Agent Team Blueprint
Scenario Runtime
业务流程编排
智能体版本管理
```

---

### 3.3 Skill 与外部能力基础

近期已完成：

```text
OpenClaw skill discovery
skill_get
skill_invoke
kimi-webbridge skill-backed provider
BrowserBridge -> Kimi WebBridge daemon execution
Org Control Center WebBridge diagnostics
```

未来演进：

```text
业务方可选择启用哪些 skills
workspace 级 skill policy
tool / skill 权限治理
connector 化业务数据源
```

---

### 3.4 安全执行基础

现有：

```text
DelegatedTask
LocalPatchExecutor
PatchSandboxPlan
ParentVerificationPolicy
Org Control Center delegated task execute UI
```

未来可用于：

```text
系统级自进化提案
代码/配置变更草案
沙箱验证
人工审批
禁止自动 merge
```

---

## 4. 两个核心闭环

平台要同时支持业务执行闭环和智能进化闭环。

---

### 4.1 业务执行闭环

```text
业务输入
  ↓
Scenario / Workflow
  ↓
IntentOrchestrator 拆解
  ↓
Agent Team 自动协作
  ↓
Tool / Skill / Connector 调用
  ↓
结果生成
  ↓
审批 / 交付 / 回写业务系统
```

示例：售后工单处理。

```text
用户投诉
  ↓
分类 Agent 判断为退款问题
  ↓
订单 Agent 查询订单
  ↓
政策 Agent 匹配售后规则
  ↓
文案 Agent 生成回复
  ↓
高风险金额触发人工审批
  ↓
最终回复客户
```

---

### 4.2 智能进化闭环

```text
运行 Trace
  ↓
结果评价
  ↓
错误归因
  ↓
发现瓶颈
  ↓
生成 Evolution Proposal
  ↓
离线评估 / 回放测试
  ↓
人工或策略审批
  ↓
发布新版本
```

示例：退款政策 Agent 漏判。

```text
系统发现：
- 最近 50 条售后工单中 12 条被人工纠正
- 错误集中在“7天无理由退款”场景

系统生成 Evolution Proposal：
- 建议修改 policy-agent instruction
- 补充判断步骤
- 增加高风险金额审批规则
- 生成 v4_candidate
- 进入 eval
```

---

## 5. 核心业务对象设计

### 5.1 Workspace：业务空间

业务方看到的是 Workspace，底层映射到 Tenant。

示例：

```json
{
  "workspace_id": "customer-support",
  "tenant_id": "customer-support",
  "name": "客服业务空间",
  "description": "售前售后智能体团队",
  "owner": "business-admin",
  "status": "ACTIVE",
  "created_at": "2026-06-12T00:00:00Z",
  "updated_at": "2026-06-12T00:00:00Z"
}
```

Workspace 负责隔离：

```text
数据
智能体
技能
工具
知识库
运行记录
权限
审计
成本
配额
```

第一版 API：

```http
GET    /api/v1/workspaces
POST   /api/v1/workspaces
GET    /api/v1/workspaces/{workspaceId}
PUT    /api/v1/workspaces/{workspaceId}
DELETE /api/v1/workspaces/{workspaceId}
```

创建 Workspace 时应自动：

```text
创建 Tenant
初始化默认目录
初始化默认安全策略
初始化默认 Agent Team Blueprint
初始化审计与配额
```

---

### 5.2 Scenario：业务场景

Scenario 是业务目标与智能体团队之间的桥。

示例：

```json
{
  "scenario_id": "after_sales_ticket",
  "workspace_id": "customer-support",
  "name": "售后工单处理",
  "description": "自动分析售后工单并生成处理建议",
  "entry_team_id": "after-sales-team",
  "entry_agent_id": "ticket-router-agent",
  "success_criteria": [
    "正确识别工单类型",
    "生成可执行处理建议",
    "高风险退款必须人工审批"
  ],
  "status": "ACTIVE"
}
```

第一版 API：

```http
GET  /api/v1/workspaces/{workspaceId}/scenarios
POST /api/v1/workspaces/{workspaceId}/scenarios
GET  /api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
PUT  /api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
```

---

### 5.3 Agent Team Blueprint：智能体团队蓝图

业务方配置的是团队蓝图，而不是运行态临时 Agent。

示例：

```json
{
  "team_id": "after-sales-team",
  "workspace_id": "customer-support",
  "name": "售后智能体团队",
  "active_version": "v1",
  "versions": [
    {
      "version": "v1",
      "status": "ACTIVE",
      "agents": [
        {
          "agent_id": "ticket-router-agent",
          "name": "工单分类智能体",
          "role": "分类与路由",
          "mission": "判断用户问题类型并选择后续处理路径",
          "instructions": "先判断工单类型，再选择合适处理智能体。",
          "allowed_tools": [],
          "allowed_skills": [],
          "handoff_policy": {
            "default_target": "policy-agent"
          }
        },
        {
          "agent_id": "policy-agent",
          "name": "售后政策智能体",
          "role": "政策判断",
          "mission": "根据售后政策判断可执行方案",
          "instructions": "判断退款、换货、补偿等售后策略。",
          "allowed_tools": ["order_query"],
          "allowed_skills": []
        }
      ],
      "created_at": "2026-06-12T00:00:00Z",
      "created_by": "business-admin"
    }
  ]
}
```

第一版 API：

```http
GET  /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/team-blueprints
GET  /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

关键原则：

```text
Blueprint 必须版本化
运行记录必须绑定 blueprint version
自进化只能生成 candidate version，不能直接覆盖 active version
```

---

### 5.4 Agent Blueprint：单个智能体蓝图

建议字段：

```json
{
  "agent_id": "policy-agent",
  "name": "售后政策智能体",
  "role": "政策判断",
  "mission": "根据售后政策判断可执行方案",
  "instructions": "...",
  "success_criteria": [
    "明确说明判断依据",
    "高风险退款触发人工审批"
  ],
  "allowed_tools": ["order_query"],
  "allowed_skills": ["kimi-webbridge"],
  "knowledge_scope": ["after-sales-policy"],
  "approval_policy": {
    "requires_human_approval_when": [
      "refund_amount > 1000",
      "external_send == true"
    ]
  },
  "handoff_policy": {
    "on_low_confidence": "human-agent",
    "on_policy_missing": "policy-admin"
  }
}
```

---

### 5.5 Run：一次业务运行

每次业务调用都生成 Run。

```json
{
  "run_id": "run_xxx",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "team_id": "after-sales-team",
  "team_version": "v1",
  "status": "COMPLETED",
  "input": {
    "ticket_id": "T123",
    "message": "我想退货"
  },
  "output": {
    "reply": "..."
  },
  "trace_id": "trace_xxx",
  "created_at": "...",
  "completed_at": "..."
}
```

第一版 API：

```http
POST /api/v1/workspaces/{workspaceId}/runs
GET  /api/v1/workspaces/{workspaceId}/runs
GET  /api/v1/workspaces/{workspaceId}/runs/{runId}
POST /api/v1/workspaces/{workspaceId}/runs/{runId}/cancel
GET  /api/v1/workspaces/{workspaceId}/runs/{runId}/events
```

---

### 5.6 Eval Set：评估集

没有评估集，自进化不可控。

示例：

```json
{
  "eval_set_id": "after-sales-basic",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "cases": [
    {
      "case_id": "case_001",
      "input": {
        "message": "我收到货三天了，想退货"
      },
      "expected": {
        "must_include": ["7天无理由", "商品状态", "退货流程"],
        "must_not_include": ["直接赔付"]
      }
    }
  ]
}
```

---

### 5.7 Evolution Proposal：进化提案

自进化不能直接改生产对象，必须先生成 Proposal。

示例：

```json
{
  "proposal_id": "evo_xxx",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "target_type": "AGENT_PROFILE",
  "target_id": "policy-agent",
  "from_version": "v3",
  "to_version": "v4_candidate",
  "reason": "退款政策判断准确率偏低",
  "evidence": [
    "最近50次运行中12次被人工纠正",
    "主要错误集中在7天无理由场景"
  ],
  "changes": [
    "补充退款判断步骤",
    "增加高风险金额审批规则"
  ],
  "risk_level": "MEDIUM",
  "status": "PENDING_EVAL",
  "created_by": "system",
  "created_at": "..."
}
```

状态机：

```text
DRAFT
PENDING_EVAL
EVAL_PASSED
EVAL_FAILED
PENDING_APPROVAL
APPROVED
REJECTED
ROLLED_OUT
ROLLED_BACK
```

目标类型：

```text
AGENT_PROFILE
TEAM_BLUEPRINT
SCENARIO_WORKFLOW
SKILL_POLICY
TOOL_POLICY
SYSTEM_PATCH
```

---

## 6. 自动编排设计

自动编排分三层演进。

---

### 6.1 第一层：规则编排

初期最稳。

示例：

```text
如果是退款问题 → refund-agent
如果是物流问题 → logistics-agent
如果涉及赔付 → human-approval
```

优点：

```text
稳定
可解释
容易上线
适合业务方理解
```

---

### 6.2 第二层：模型编排

使用 IntentOrchestrator 根据任务动态选择 Agent。

可增强现有模块：

```text
IntentOrchestrator
TeamManager
AgentRole
CapabilityScorer
```

能力：

```text
根据任务意图选择 Agent
根据能力评分分配子任务
根据失败原因 reroute
根据上下文压力 delegated task
```

---

### 6.3 第三层：经验驱动编排

系统基于历史 Run / Trace / Eval 结果优化编排策略。

示例：

```text
退款金额 < 100 元：policy-agent 自动处理
退款金额 > 1000 元：policy-agent + risk-agent + human approval
用户情绪强烈：empathy-agent 先生成安抚话术
```

需要数据：

```text
成功率
人工纠正率
工具失败率
平均耗时
平均成本
用户满意度
风险事件
```

---

## 7. 自进化分级

不要一开始允许系统进化所有东西。

---

### Level 1：进化提示词和操作手册

低风险，优先做。

```text
Agent instructions
Skill usage guide
Scenario playbook
Error handling checklist
```

---

### Level 2：进化团队编排

中风险。

```text
新增 Agent
删除 Agent
改变 Agent 调用顺序
增加人工审批节点
修改 handoff policy
```

---

### Level 3：进化工具权限

高风险，必须审批。

```text
允许调用浏览器
允许发邮件
允许修改数据库
允许发布内容
```

---

### Level 4：进化代码或系统配置

最高风险。

通过现有 delegated local_patch 执行链路实现：

```text
sandbox
tests
parent verification
human approval
no auto merge
```

---

## 8. 安全阀设计

### 8.1 版本化

所有关键对象都必须版本化：

```text
Agent Blueprint
Team Blueprint
Scenario Workflow
Skill Policy
Tool Policy
```

Run 必须记录版本：

```text
team_version
scenario_version
agent_versions
```

---

### 8.2 回放测试

上线前用历史 case 回放。

```text
旧版本表现
新版本表现
差异
风险
成本变化
失败率变化
```

---

### 8.3 灰度发布

支持：

```text
5% 流量
10% 流量
50% 流量
100% 流量
仅测试 workspace
仅内部用户
```

---

### 8.4 自动回滚

触发条件：

```text
失败率上升
人工纠正率上升
用户满意度下降
成本暴涨
工具错误增加
审批拒绝率升高
```

---

### 8.5 审批

必须审批的变更：

```text
工具权限扩大
外部发送能力
数据库写入能力
浏览器真实账号操作
财务/法律/医疗建议
生产流程修改
系统代码变更
```

---

## 9. 服务器端部署目标

### 9.1 第一阶段部署形态

```text
单机 / 单服务器
JAR + systemd
Docker / docker-compose
文件存储 + SQLite 可选
Dashboard + API
```

必须配置：

```text
server.port
data.dir
model.provider
model.api_key
database.url
storage.path
auth.enabled
admin.password
```

---

### 9.2 后续部署形态

```text
PostgreSQL
对象存储
Redis / Queue
多实例 API
独立 worker
独立 browser bridge / skill workers
监控告警
```

---

## 10. API 分层建议

### 10.1 管理 API

```http
/api/v1/workspaces
/api/v1/workspaces/{workspaceId}/members
/api/v1/workspaces/{workspaceId}/api-keys
/api/v1/workspaces/{workspaceId}/settings
```

### 10.2 智能体团队 API

```http
/api/v1/workspaces/{workspaceId}/team-blueprints
/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

### 10.3 场景 API

```http
/api/v1/workspaces/{workspaceId}/scenarios
/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}
```

### 10.4 运行 API

```http
/api/v1/workspaces/{workspaceId}/runs
/api/v1/workspaces/{workspaceId}/runs/{runId}
/api/v1/workspaces/{workspaceId}/runs/{runId}/events
/api/v1/workspaces/{workspaceId}/runs/{runId}/cancel
```

### 10.5 进化 API

```http
/api/v1/workspaces/{workspaceId}/evolution/proposals
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/eval
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/approve
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/reject
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/rollout
/api/v1/workspaces/{workspaceId}/evolution/proposals/{proposalId}/rollback
```

---

## 11. 推荐开发路线

### Phase 1：业务空间与团队蓝图骨架

目标：业务方能创建业务空间和版本化团队蓝图。

任务：

```text
1. 新增 WorkspaceRecord / WorkspaceService / WorkspaceHandler
2. Workspace 创建时自动创建 Tenant
3. 新增 TeamBlueprint / AgentBlueprint / BlueprintVersion
4. 新增 TeamBlueprintService
5. API: /api/v1/workspaces, /team-blueprints
6. Dashboard 增加 Workspace 列表与 Team Blueprint 简单页面
7. Run 记录预留 workspace_id / team_version 字段
```

---

### Phase 2：Scenario 与 Run API

目标：业务方能通过 API 调用某个业务场景。

任务：

```text
1. 新增 ScenarioRecord / ScenarioService
2. Scenario 绑定 Team Blueprint active version
3. 新增 RunRecord / RunService
4. Run 调用 IntentOrchestrator
5. Run 记录 trace_id、team_version、status、input、output
6. 提供 run events 查询接口
```

---

### Phase 3：Skill / Tool Policy 产品化

目标：业务方能配置每个 Agent 的技能与工具权限。

任务：

```text
1. Workspace 级 skill registry
2. Agent Blueprint allowed_skills / allowed_tools
3. Tool policy validation
4. 高风险工具审批
5. Skill-backed capability diagnostics
```

---

### Phase 4：Evolution Proposal 最小闭环

目标：系统可以产生、评估、审批、发布进化提案。

任务：

```text
1. EvolutionProposal 数据结构
2. Proposal 状态机
3. Proposal 绑定 target_type / target_id / version
4. 人工创建 proposal
5. 从 trace 生成 proposal 草案
6. Eval stub
7. Approve / Reject
8. 生成 candidate version
9. Activate / Rollback
```

---

### Phase 5：部署与生产治理

目标：服务器端可部署、可运维、可审计。

任务：

```text
1. Dockerfile
2. docker-compose
3. systemd service 示例
4. SQLite / PostgreSQL 持久化规划
5. API Key
6. Admin auth
7. Audit dashboard
8. Cost / quota dashboard
9. Backup / restore
```

---

## 12. 下一刀建议

建议下一刀直接做：

> Workspace + Team Blueprint Versioning 最小骨架。

原因：

```text
Workspace 是业务空间的入口
Team Blueprint 是业务自助创建智能体团队的载体
Versioning 是自进化的前置条件
```

最小交付：

```text
1. WorkspaceRecord
2. WorkspaceService
3. WorkspaceHandler
4. 创建 Workspace 自动创建 Tenant
5. TeamBlueprintRecord
6. TeamBlueprintService
7. Team Blueprint 创建 / 查询 / 新版本 / 激活
8. 基础测试
9. 文档更新
```

第一版无需做复杂 UI，可以先做 API 和测试。

---

## 13. 设计约束

### 13.1 不要让自进化直接修改生产

所有自进化都必须走：

```text
Proposal → Eval → Approval → Version → Rollout
```

### 13.2 不要让业务直接面对底层 Tenant

业务方看到：

```text
Workspace
Scenario
Agent Team
```

底层才是：

```text
Tenant
ToolRegistry
IntentOrchestrator
```

### 13.3 不要把外部能力硬编码死

例如 Kimi WebBridge：

```text
核心只做 adapter / diagnostics / policy
真实能力优先通过 skill-backed / connector-backed 接入
```

### 13.4 高风险能力必须有审批和审计

包括：

```text
浏览器真实账号操作
发送消息/邮件/发帖
数据库写入
支付/财务操作
代码变更
权限扩大
```

---

## 14. 与现有模块映射表

| 业务平台概念 | 现有模块 | 后续动作 |
|---|---|---|
| Workspace | TenantContext / TenantManager | 增加业务包装层 |
| Agent Team Blueprint | TeamManager / AgentRole | 增加版本化蓝图 |
| Scenario | IntentOrchestrator | 增加业务场景入口 |
| Run | AgentTrace / IntentRun | 增加业务运行记录 |
| Skill Policy | SkillManager / TenantSkillManager | 增加 workspace/agent 级策略 |
| Tool Policy | ToolRegistry / TenantAwareToolDispatcher | 增加 allowed_tools 校验 |
| Evolution Proposal | org/evolution + delegated task | 增加 proposal 状态机 |
| Eval Set | org/eval | 增加 scenario eval set |
| Approval | BrowserApprovalQueue / DelegatedTask verification | 统一审批中心 |
| Audit | TenantAuditLogger | 扩展业务事件审计 |

---

## 15. 明日继续开发 Checklist

建议明天从下面开始：

```text
[ ] 新建 docs/WORKSPACE_API.md 或直接在本方案基础上展开 API
[ ] 新增 package: com.nousresearch.hermes.workspace
[ ] 新增 WorkspaceRecord
[ ] 新增 WorkspaceService
[ ] 新增 WorkspaceHandler
[ ] DashboardServer 注册 /api/v1/workspaces
[ ] POST /api/v1/workspaces 自动调用 TenantManager.createTenant
[ ] GET /api/v1/workspaces 返回 workspace 列表
[ ] 新增 package: com.nousresearch.hermes.blueprint
[ ] 新增 TeamBlueprintRecord / AgentBlueprintRecord
[ ] 新增 TeamBlueprintService
[ ] API: 创建 team blueprint / 新版本 / 激活版本
[ ] 测试：创建 workspace 自动创建 tenant
[ ] 测试：创建 blueprint v1 并激活
```

---

## 16. 最终目标画面

业务方打开平台后：

```text
1. 创建“客服业务空间”
2. 选择“售后工单处理”模板
3. 平台自动生成一个售后智能体团队草案
4. 业务方调整 Agent 职责、工具权限、知识库
5. 点击发布 v1
6. 业务系统通过 API 提交工单
7. 智能体团队处理任务
8. 平台记录 trace、audit、run result
9. 系统发现失败模式，生成 evolution proposal
10. 业务方审核并灰度发布 v2
```

这就是从“Agent 工具”升级为“业务智能组织平台”的路径。
