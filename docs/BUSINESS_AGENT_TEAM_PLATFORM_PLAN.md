# Hermes Agent Java：业务智能体团队平台方案

> 一句话：让业务人员像"搭建一个小团队"一样创建智能体团队；让系统像"有经验的业务主管"一样自动分工、跟进结果、发现问题、提出改进，但所有高风险动作都可审计、可审批、可回滚。

---

## 1. 背景与目标

很多业务团队不缺 AI 聊天工具，缺的是能理解业务目标、按流程协作、调用业务系统、留下过程记录、风险可控、持续变好的 AI 团队。

Hermes Agent Java 已具备多租户、Agent 团队、Org Control Center、Skill、BrowserBridge、Delegated Task、安全审计等工程基础。下一步是把这些能力包装成业务人员能理解、能配置、敢上线的"智能体团队平台"。

**业务方最终能做的六件事：**

1. 创建属于自己部门/项目的 **业务空间**
2. 像搭建岗位一样创建 **智能体团队**（分类员、政策专家、质检员、审批员等）
3. 给每个智能体配置职责、知识、可用工具、审批边界
4. 通过页面或 API 把真实业务任务交给团队处理
5. 查看每次任务的处理过程、依据、成本和风险
6. 根据系统自动生成的优化建议，审批新版本并灰度上线

**愿景：** 业务定义目标和边界，系统自动组织智能体团队完成任务，并在治理约束下持续自我改进。

---

## 2. 总体定位：智能组织操作系统

### 三层架构

| 层级 | 业务理解 | 平台能力 |
|---|---|---|
| 业务层 | 我有什么业务、目标、规则和权限边界 | Workspace / Scenario / 目标 / 权限 / 数据源 |
| 协作层 | 谁来做、怎么分工、什么时候交接 | Agent Team / Orchestrator / Workflow / Tool & Skill Routing |
| 进化层 | 做得好不好、哪里错了、怎么变好 | Eval / Trace / Feedback / Evolution Proposal / Versioning / Canary |

### 核心原则

```text
业务方定义：场景、目标、成功标准、权限边界、审批规则
系统负责：自动拆解任务、选择智能体、调用工具、记录过程、生成结果
进化系统负责：发现问题、生成优化建议、跑评估、提出版本草案
人或治理策略负责：审批高风险变更、灰度发布、回滚
```

### 平台坚决不做

```text
AI 自己直接改生产智能体
AI 自己直接扩大权限
AI 自己直接发布高风险变更
AI 在没有记录的情况下执行关键动作
```

推荐模式：**AI 发现问题 → 生成进化提案 → 评估验证 → 安全检查 → 人工/策略审批 → 版本化发布**

### 业务友好术语

| 技术词 | 业务说法 | 解释 |
|---|---|---|
| Tenant | 业务空间 | 部门/项目独立工作区，数据和权限隔离 |
| Agent | 智能体 / 数字员工 | 承担明确职责的 AI 角色 |
| Agent Team | 智能体团队 | 多个智能体按分工协作完成任务 |
| Blueprint | 团队蓝图 | 岗位、职责、规则、工具和知识范围的配置方案 |
| Version | 团队版本 | 每次发布后的配置快照，可回滚 |
| Scenario | 业务场景 | 具体业务任务，如售后处理、线索跟进 |
| Run | 一次处理记录 | 任务从输入到输出的完整执行过程 |
| Trace | 过程轨迹 | 每一步怎么判断、调用了什么、依据是什么 |
| Eval | 效果评估 | 用案例集测试团队是否做得更好 |
| Evolution Proposal | 优化建议 | 基于错误和反馈提出的改进草案 |
| Skill / Tool | 可用能力 | 查询订单、打开网页、发消息等能力 |
| Approval | 人工审批 | 高风险动作必须经过确认 |
| Canary | 灰度发布 | 新版本先小范围试用 |

---

## 3. 平台能力与产品边界

### 3.1 业务侧核心能力

| 业务动作 | 背后能力 |
|---|---|
| 创建业务空间 | 多租户隔离、权限、审计、配额 |
| 选择业务场景（模板） | Scenario / Workflow |
| 搭建智能体团队 | Agent Team Blueprint |
| 配置规则和知识 | Instructions / Knowledge / Eval |
| 设置审批边界 | Approval Policy / Tool Policy |
| 试运行看过程 | Trace / Audit |
| 发布版本 | Versioning / Canary |
| 推动团队进化 | Evolution Proposal |

### 3.2 业务使用旅程

```text
说清目标 → 选/生成团队模板 → 补充业务规则 → 试运行 → 发布 → 复盘进化
```

### 3.3 双入口隔离：Business Portal vs Technical Dashboard

| 入口 | 面向用户 | 核心目标 | 典型内容 |
|---|---|---|---|
| Business Portal | 业务负责人、运营、客服主管 | 创建团队、配置场景、查看效果、处理审批 | 业务空间、场景、智能体团队、运行看板、待审批、优化建议 |
| Technical Dashboard | 开发、运维、平台团队 | 配置、诊断、观测、治理 | 模型、工具、日志、Session、Gateway、Tenant、Trace |

**设计原则：**
- 底层复用同一组服务（WorkspaceService / TeamBlueprintService 等）
- UI 层必须分离，业务入口用业务语言，技术入口用工程语言
- 权限后端隔离：业务用户不能改模型配置、环境变量、高风险工具
- 成熟度分层：STABLE → BETA → EXPERIMENTAL → INTERNAL，业务入口只看成熟能力
- 业务 Trace 必须翻译为故事化过程说明，不暴露原始技术 Trace

---

## 4. 技术基础现状

当前已具备的核心能力：

- **多租户**：Tenant 隔离、配额、审计、沙箱
- **Agent 组织**：TeamManager、AgentRole、IntentOrchestrator
- **Skill / 外部能力**：Skill 管理、BrowserBridge、Delegated Task
- **安全执行**：进程/网络/存储隔离、审批队列、验证链路
- **两大闭环**：业务执行闭环（输入→编排→执行→验证→输出）；智能进化闭环（运行→发现问题→生成提案→评估→发布）

---

## 5. 核心业务对象设计

**对象关系：**
```text
业务空间 → 多个业务场景 → 每个场景绑定一个智能体团队
  → 团队有多个版本 → 每次运行记录使用哪个版本
  → 运行结果进入评估和优化建议
```

### 5.1 Workspace（业务空间）

部门/项目级别的 AI 工作区，底层映射到 Tenant。

```json
{
  "workspace_id": "customer-support",
  "tenant_id": "customer-support",
  "name": "客服业务空间",
  "owner": "business-admin",
  "status": "ACTIVE"
}
```

负责隔离：数据、智能体、技能、工具、知识库、运行记录、权限、审计、成本、配额。

**第一版 API：**
```
GET    /api/v1/workspaces
POST   /api/v1/workspaces
GET    /api/v1/workspaces/{workspaceId}
PUT    /api/v1/workspaces/{workspaceId}
DELETE /api/v1/workspaces/{workspaceId}
```

创建 Workspace 时自动创建 Tenant、初始化默认目录/安全策略/审计/配额。

### 5.2 Scenario（业务场景）

可被反复执行的业务任务（如售后工单处理、销售线索初筛、合同初审）。是业务目标与智能体团队之间的桥。

```json
{
  "scenario_id": "after_sales_ticket",
  "workspace_id": "customer-support",
  "name": "售后工单处理",
  "entry_team_id": "after-sales-team",
  "success_criteria": ["正确识别工单类型", "高风险退款必须人工审批"],
  "status": "ACTIVE"
}
```

### 5.3 Agent Team Blueprint（智能体团队蓝图）

一份"团队编制表 + 工作说明书"。描述团队有哪些岗位、各自负责什么、能使用哪些工具和知识、交接规则、审批规则、当前线上版本。

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
          "instructions": "...",
          "allowed_tools": [],
          "allowed_skills": [],
          "handoff_policy": { "default_target": "policy-agent" }
        }
      ]
    }
  ]
}
```

**关键原则：** Blueprint 必须版本化；运行记录必须绑定 blueprint version；自进化只能生成 candidate version，不能直接覆盖 active version。

**API：**
```
GET  /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/team-blueprints
GET  /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

### 5.4 Agent Blueprint（单个智能体蓝图）

数字员工的岗位说明书。核心字段：agent_id、name、role、mission、instructions、success_criteria、allowed_tools、allowed_skills、knowledge_scope、approval_policy、handoff_policy。

### 5.5 Run（一次业务运行）

一张任务处理单。无论成功/失败/转人工，都留下记录：谁提交的、哪个团队处理的、用了哪个版本、处理过程、最终结果、是否触发审批、耗时成本。

```json
{
  "run_id": "run_xxx",
  "workspace_id": "customer-support",
  "scenario_id": "after_sales_ticket",
  "team_id": "after-sales-team",
  "team_version": "v1",
  "status": "COMPLETED",
  "input": { "ticket_id": "T123", "message": "我想退货" },
  "output": { "reply": "..." },
  "trace_id": "trace_xxx"
}
```

### 5.6 Eval Set（评估集）

一组"标准考题"。用历史案例和标准答案验证新版本是否真的更好。没有评估集，自进化不可控。

### 5.7 Evolution Proposal（进化提案）

系统写给业务负责人的"优化建议单"。不直接改线上团队，而是说明：发现了什么问题、证据是什么、建议怎么改、风险有多高、用哪些案例测过、是否建议灰度上线。

状态机：`DRAFT → PENDING_EVAL → EVAL_PASSED → EVAL_FAILED → PENDING_APPROVAL → APPROVED → REJECTED → ROLLED_OUT → ROLLED_BACK`

目标类型：AGENT_PROFILE / TEAM_BLUEPRINT / SCENARIO_WORKFLOW / SKILL_POLICY / TOOL_POLICY / SYSTEM_PATCH

---

## 6. 自动编排三层设计

| 层级 | 方式 | 适用阶段 | 特点 |
|---|---|---|---|
| 规则编排 | 条件路由（if→agent） | 初期 | 稳定、可解释、易上线 |
| 模型编排 | IntentOrchestrator 动态选择 | 中期 | 灵活、自适应 |
| 经验驱动编排 | 基于历史 Run/Trace/Eval 优化策略 | 后期 | 数据驱动、持续优化 |

---

## 7. 自进化分级

| 级别 | 内容 | 风险 |
|---|---|---|
| Level 1 | 进化提示词和操作手册（Agent instructions、Skill usage guide 等） | 低 |
| Level 2 | 进化团队编排（新增/删除 Agent、改顺序、改 handoff policy） | 中 |
| Level 3 | 进化工具权限（浏览器、邮件、数据库写入等） | 高，必须审批 |
| Level 4 | 进化代码或系统配置 | 最高，走 delegated local_patch 链路 |

---

## 8. 安全阀设计

### 8.1 版本化
所有关键对象（Agent Blueprint、Team Blueprint、Scenario Workflow、Skill Policy、Tool Policy）都必须版本化。Run 必须记录版本。

### 8.2 回放测试
上线前用历史 case 回放，对比新旧版本表现：差异、风险、成本变化、失败率变化。

### 8.3 灰度发布
支持：5% / 10% / 50% / 100% 流量；仅测试 workspace；仅内部用户。

### 8.4 自动回滚
触发条件：失败率上升、人工纠正率上升、用户满意度下降、成本暴涨、工具错误增加、审批拒绝率升高。

### 8.5 审批
必须审批的变更：工具权限扩大、外部发送、数据库写入、浏览器真实账号操作、财务/法律/医疗建议、生产流程修改、系统代码变更。

---

## 9. API 分层

| 层级 | 路径前缀 | 内容 |
|---|---|---|
| 管理 API | `/api/v1/workspaces` | 空间、成员、API Key、设置 |
| 团队 API | `/api/v1/workspaces/{id}/team-blueprints` | 团队蓝图、版本、激活 |
| 场景 API | `/api/v1/workspaces/{id}/scenarios` | 场景 CRUD |
| 运行 API | `/api/v1/workspaces/{id}/runs` | 运行、事件、取消 |
| 进化 API | `/api/v1/workspaces/{id}/evolution/proposals` | 提案、评估、审批、发布、回滚 |

---

## 10. 推荐开发路线

> 收敛原则：先把"业务空间 + 团队蓝图版本化"做成可运行、可测试、可复用的核心底座，再向外扩展。

| 阶段 | 目标 | 核心任务 |
|---|---|---|
| Phase 1 | Workspace + Team Blueprint Versioning 最小骨架 | Workspace（Tenant façade）、TeamBlueprint 版本化、文件持久化、API + 测试 |
| Phase 2 | Scenario 与 Run API | Scenario 绑定 Team Blueprint、Run 调用编排器、记录轨迹 |
| Phase 3 | Skill / Tool Policy 产品化 | Workspace/Agent 级权限策略、风险守卫、审批 |
| Phase 4 | Evolution Proposal 最小闭环 | 提案状态机、生成 → 评估 → 审批 → 发布 |
| Phase 5 | 部署与生产治理 | Docker、数据库、监控、备份、认证 |

### Phase 1 MVP 细节

**Workspace：** Tenant 的业务化 façade，workspaceId == tenantId，不重新实现租户系统。内部调用 TenantManager。

**Team Blueprint：**
- 包结构：`com.nousresearch.hermes.blueprint`
- 持久化：`~/.hermes/tenants/{workspaceId}/business/team-blueprints/{teamId}.json`
- 版本状态（第一版）：DRAFT / ACTIVE / ARCHIVED
- 关键不变量：同 workspace 内 teamId 唯一；至多一个 ACTIVE version；ACTIVE version 不可原地修改；新变更建新 DRAFT version；activate 时旧 ACTIVE → ARCHIVED

**测试必须覆盖：**
- 创建 workspace 同时创建 tenant、重复创建 409
- 创建 blueprint 自动生成 active v1
- 创建 v2 为 DRAFT、不影响 activeVersion
- 激活 v2 后 v1=ARCHIVED v2=ACTIVE
- workspace 不存在 404、空 agents 400、重复 agentId 400

**暂不做（Phase 1）：** Scenario 执行入口、Run 记录、Eval Set、Evolution Proposal、灰度发布、自动回滚、复杂审批流、复杂前端、SQLite/PostgreSQL repository。这些依赖 Team Blueprint Versioning 底座，先做会导致模型返工。

---

## 11. 设计约束

1. **自进化不能直接修改生产**：必须走 Proposal → Eval → Approval → Version → Rollout
2. **业务不直接面对底层 Tenant**：业务层看到 Workspace / Scenario / Agent Team，底层才是 Tenant / ToolRegistry / IntentOrchestrator
3. **外部能力不硬编码死**：核心只做 adapter / diagnostics / policy，真实能力通过 skill-backed / connector-backed 接入
4. **高风险能力必须有审批和审计**：浏览器真实账号操作、消息/邮件发送、数据库写入、代码变更、权限扩大等

---

## 12. 与现有模块映射

| 业务平台概念 | 现有模块 | 后续动作 |
|---|---|---|
| Business Portal | DashboardServer / React web | 增加业务入口壳，与技术 Dashboard 隔离 |
| Workspace | TenantContext / TenantManager | 增加业务包装层 |
| Agent Team Blueprint | TeamManager / AgentRole | 增加版本化蓝图 |
| Scenario | IntentOrchestrator / ScenarioService | 绑定 Team/Run/Insights |
| Run | AgentTrace / IntentRun / BusinessRunService | 增加业务运行记录与故事化 Trace |
| Skill Policy | SkillManager / TenantSkillManager | 增加 workspace/agent 级策略 |
| Tool Policy | ToolRegistry / TenantAwareToolDispatcher | 增加 allowed_tools 校验 |
| Evolution Proposal | org/evolution + delegated task | 增加 proposal 状态机 |
| Eval Set | org/eval | 增加 scenario eval set |
| Approval | BrowserApprovalQueue / DelegatedTask verification | 统一审批中心 |
| Audit | TenantAuditLogger | 扩展业务事件审计 |

---

## 13. 最终目标画面

产品有两个清晰入口：
- **Business Portal**：给业务用，聚焦空间、场景、团队、规则、审批、效果、优化
- **Technical Dashboard**：给平台团队用，聚焦模型、工具、日志、网关、Trace、系统配置

**第一次创建团队：** 说目标 → 平台自动生成团队草案 → 追问关键规则 → 上传知识库 → 试运行 → 查看依据 → 发布 v1

**日常运行：** 业务首页呈现处理量、自动完成率、转人工率、需审批数、平均时长、人工纠正率、主要风险、系统建议

**持续进化：** 系统发现失败模式 → 生成优化建议 → 历史案例回放 → 新旧对比 → 审批 → 5% 灰度 → 稳定扩大 → 异常自动回滚

> 业务人员只需要定义目标、规则和边界；平台负责组织智能体团队执行、复盘和进化；高风险动作始终留在人和治理规则手里。

这就是从"Agent 工具"升级为"业务智能组织平台"的路径。

---

## 14. AI 组织原生化模块（org）

将 AI Agent 从匿名工具提升为组织正式成员的核心模块。

### 模块结构

```
org/
├── identity/    # P0: Agent 身份与认证
├── handoff/     # P0: 人机交接协议
├── auth/        # P0: RBAC + ABAC 权限
├── knowledge/   # P1: 组织知识库
├── workflow/    # P1: 持久化工作流引擎
└── eval/        # P1: Agent 评估框架
```

### 预定义角色

| 角色 | 典型权限 | 适用场景 |
|------|---------|---------|
| VIEWER | 只读文件 + Web 搜索 | 信息查询 Agent |
| CONTRIBUTOR | 读写文件 + 代码执行 + 数据操作 | 开发 Agent |
| MAINTAINER | + 删除文件 + 部署 + Agent 管理 | 运维 Agent |
| ADMIN | 全部权限 + 审计 + 计费 + 策略 | 管理 Agent |

### 人机交接协议

Agent 触发审批 → 人类审批 → 恢复执行。支持 SLA、超时升级、决议记录。交接上下文包含：发起 Agent、摘要、详情、审批人、超时秒数。

### 知识库

组织级知识库，支持 SOP / 政策 / 经验等类型，带分类、标签、权限等级，可生成 RAG 上下文。

### 工作流引擎

持久化多步骤工作流，支持工具调用、人工审批、条件分支等步骤类型，失败可恢复。

### Agent 评估

支持多维度评分（准确性、安全性等）、A/B 对比、漂移检测，用于版本发布前的质量门禁。

---

## 15. 业务 API 示例（端到端流程）

> 基础路径：`/api/v1/workspaces/{workspaceId}/...`，对应五个业务入口：Home / Teams / Runs / Approvals / Insights

### 15.1 创建业务空间

```bash
curl -X POST "$HERMES_BASE_URL/api/v1/workspaces" \
  -H 'Content-Type: application/json' \
  -d '{
    "workspaceId": "customer-service-demo",
    "name": "Customer Service Demo",
    "owner": "ops"
  }'
```

第一版 `workspaceId == tenantId`，但同时返回两个字段，保留未来解耦空间。

### 15.2 创建团队蓝图

```bash
curl -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/team-blueprints" \
  -H 'Content-Type: application/json' \
  -d '{
    "teamId": "after-sales-team",
    "name": "After-sales Team",
    "agents": [
      {
        "agentId": "ticket-classifier",
        "displayName": "Ticket Classifier",
        "responsibility": "Classify customer requests",
        "allowedTools": ["order.query"],
        "approvalRules": ["Refunds above 1000 CNY require human approval"]
      },
      {
        "agentId": "policy-specialist",
        "displayName": "Policy Specialist",
        "responsibility": "Judge after-sales policies",
        "allowedTools": ["policy.search"]
      }
    ]
  }'
```

创建后自动生成 activeVersion = 1，状态 ACTIVE。

### 15.3 创建业务运行记录

运行记录是业务可读的 trace，不需要业务用户读技术日志：

```bash
curl -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "teamId": "after-sales-team",
    "scenario": "refund request",
    "taskTitle": "Customer requested a refund",
    "resultSummary": "Suggest allowing return request",
    "conclusionReason": "Signed 3 days ago, 89 CNY, not special category",
    "riskJudgement": "No manual approval required",
    "status": "COMPLETED",
    "steps": [
      { "title": "Classify request", "actor": "ticket-classifier", "summary": "Refund request" },
      { "title": "Check policy", "actor": "policy-specialist", "summary": "Matches 7-day return" }
    ]
  }'
```

### 15.4 创建审批卡

审批卡为移动端快速审批优化：

```bash
curl -X POST "$HERMES_BASE_URL/api/v1/workspaces/customer-service-demo/approvals" \
  -H 'Content-Type: application/json' \
  -d '{
    "teamId": "after-sales-team",
    "title": "High value refund approval",
    "summary": "1200 CNY refund requires manual approval",
    "recommendation": "Approve after checking product return condition",
    "riskLevel": "HIGH"
  }'
```

操作：`approve` / `reject` / `request-info`

### 15.5 五个业务入口

| 入口 | 路径 | 内容 |
|---|---|---|
| Home | `/api/v1/business/home?workspaceId=...` | 汇总：今日、待关注、风险、团队状态、洞察、下一步 |
| Teams | `/api/v1/business/teams?workspaceId=...` | 团队蓝图列表 |
| Runs | `/api/v1/business/runs?workspaceId=...` | 运行记录 |
| Approvals | `/api/v1/business/approvals?workspaceId=...&status=ALL` | 审批卡 |
| Insights | `/api/v1/business/insights?workspaceId=...` | 业务洞察：失败率、待审批数、高风险数、下一步建议 |
