# Hermes Agent Java：业务智能体团队平台方案

> 一句话：让业务人员像“搭建一个小团队”一样创建智能体团队；让系统像“有经验的业务主管”一样自动分工、跟进结果、发现问题、提出改进，但所有高风险动作都可审计、可审批、可回滚。

---

## 0. 阅读指南：不同角色怎么看这份方案

这份文档同时服务三类读者：

| 读者 | 建议重点阅读 | 你会得到什么 |
|---|---|---|
| 业务负责人 / 运营 / 客服主管 / 非技术同学 | 1、2、3、4、16 | 看懂平台能解决什么问题、怎么使用、为什么安全可靠 |
| 产品 / 解决方案 / 售前 | 1、2、4、5、6、8、16 | 看懂产品卖点、业务场景、智能体验和边界 |
| 技术 / 架构 / 开发 | 3、5、9、10、11、12、13、14、15 | 看懂对象模型、API、MVP 范围和开发路线 |

文档表达约定：

```text
业务空间 = 某个业务部门/项目自己的工作区
智能体团队 = 一组有分工的 AI 数字员工
场景 = 一个具体业务任务，例如“售后工单处理”
运行记录 = 一次任务从输入到结果的完整过程
进化提案 = 系统发现问题后提出的优化建议
```

---

## 1. 背景与目标：从“会聊天的 AI”到“会做事的业务团队”

很多业务团队并不缺 AI 聊天工具，真正缺的是：

```text
能理解业务目标
能按流程协作
能调用业务系统
能留下过程记录
能遇到风险时请求审批
能根据反馈持续变好
```

Hermes Agent Java 已经具备多租户、Agent 团队、Org Control Center、Skill、BrowserBridge、Delegated Task、安全审计等工程基础。下一阶段要做的不是再堆更多技术模块，而是把这些能力包装成业务人员能理解、能配置、敢上线的“智能体团队平台”。

业务方最终应该能完成这些动作：

1. 创建一个属于自己部门或项目的 **业务空间**；
2. 像搭建岗位一样创建 **智能体团队**，例如分类员、政策专家、质检员、审批员；
3. 给每个智能体配置职责、知识、可用工具、审批边界；
4. 通过页面或 API 把真实业务任务交给团队处理；
5. 查看每次任务的处理过程、依据、成本和风险；
6. 根据系统自动生成的优化建议，审批新版本并灰度上线。

愿景：

> 业务定义目标和边界，系统自动组织智能体团队完成任务，并在治理约束下持续自我改进。

这不是“AI 替人拍脑袋”，而是“AI 进入业务组织，成为可管理、可协作、可复盘的数字员工团队”。

---

## 2. 总体定位：智能组织操作系统

平台不只是一个 Agent 执行器，而是一个面向业务的 **智能组织操作系统**。

如果用业务语言描述，它分成三层：

| 层级 | 业务理解 | 平台能力 |
|---|---|---|
| 业务层 | 我有什么业务、目标、规则和权限边界 | Workspace / Scenario / 目标 / 权限 / 数据源 |
| 协作层 | 谁来做、怎么分工、什么时候交接 | Agent Team / Orchestrator / Workflow / Tool & Skill Routing |
| 进化层 | 做得好不好、哪里错了、怎么变好 | Eval / Trace / Feedback / Evolution Proposal / Versioning / Canary |

核心原则：

```text
业务方定义：场景、目标、成功标准、权限边界、审批规则
系统负责：自动拆解任务、选择智能体、调用工具、记录过程、生成结果
进化系统负责：发现问题、生成优化建议、跑评估、提出版本草案
人或治理策略负责：审批高风险变更、灰度发布、回滚
```

平台坚决不做：

```text
AI 自己直接改生产智能体
AI 自己直接扩大权限
AI 自己直接发布高风险变更
AI 在没有记录的情况下执行关键动作
```

推荐模式：

```text
AI 发现问题
  ↓
生成进化提案
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

## 2.1 给业务人员的一分钟例子

以“售后工单处理”为例，业务人员看到的是：

```text
我创建一个“客服业务空间”
  ↓
选择“售后工单处理”模板
  ↓
平台生成一个团队草案：
- 工单分类智能体
- 订单查询智能体
- 售后政策智能体
- 回复文案智能体
- 风险审批智能体
  ↓
我补充公司售后政策、退款规则、审批金额
  ↓
点击发布 v1
  ↓
业务系统把工单发进来
  ↓
智能体团队自动处理，并把高风险事项交给人审批
```

业务人员不需要理解 Tenant、API、Trace、Eval 这些技术词。页面应该呈现为：

```text
创建空间 → 选择场景 → 配置团队 → 设置边界 → 试运行 → 发布 → 查看效果 → 审批优化
```

---

## 2.2 领先智感：平台应该呈现出来的“聪明”

所谓“智感”不是炫技动画，而是让业务人员明显感觉：系统懂业务、会追问、能解释、可托付。

### 2.2.1 创建时：像顾问一样帮业务补全方案

当业务人员输入：

```text
我想做一个售后工单处理团队
```

平台不应该只给空表单，而应该自动建议：

```text
推荐团队角色：分类、订单查询、政策判断、回复生成、风险审批
推荐成功标准：分类准确率、退款规则命中率、人工纠正率、平均处理时长
推荐审批规则：退款金额 > 1000 元必须人工审批
推荐知识库：售后政策、商品类目规则、物流异常规则、历史优秀回复
```

### 2.2.2 配置时：把技术配置翻译成业务语言

不要让业务方直接面对：

```text
tools / skills / route / trace / eval / tenant policy
```

应该翻译为：

```text
这个团队能查哪些系统？
哪些事情可以自动做？
哪些事情必须请人确认？
回答必须遵守哪些规则？
怎样算完成得好？
出了问题找谁处理？
```

### 2.2.3 运行时：像项目经理一样给出可读过程

一次任务完成后，业务方不只看到最终结果，还能看到：

```text
工单被判断为：退款问题
判断依据：用户提到“收到货三天，想退货”
查询结果：订单已签收 3 天，商品未标记特殊类目
政策匹配：符合 7 天无理由退款初步条件
风险判断：退款金额 89 元，无需人工审批
最终建议：引导用户提交退货申请
```

### 2.2.4 复盘时：像业务分析师一样发现问题

平台应该主动提示：

```text
最近 7 天有 12 条工单被人工纠正
主要集中在“生鲜商品退款”场景
建议新增一个“特殊类目政策判断”步骤
是否生成团队 v2 草案？
```

### 2.2.5 发布时：像风控系统一样稳

所有重要变化都应该让业务方放心：

```text
新版本改了什么
为什么改
用哪些历史案例测试过
预计影响哪些场景
先给 5% 流量试运行
异常时自动回到旧版本
```

这就是平台的领先感：不是“AI 很能聊”，而是“AI 懂组织、懂流程、懂风险、懂迭代”。

---

## 2.3 业务友好术语表

| 技术词 | 业务说法 | 解释 |
|---|---|---|
| Tenant | 业务空间 | 某个部门/项目独立使用的一块空间，数据和权限隔离 |
| Agent | 智能体 / 数字员工 | 承担某个明确职责的 AI 角色 |
| Agent Team | 智能体团队 | 多个智能体按分工协作完成任务 |
| Blueprint | 团队蓝图 | 团队岗位、职责、规则、工具和知识范围的配置方案 |
| Version | 团队版本 | 每次发布后的团队配置快照，可回滚 |
| Scenario | 业务场景 | 一个具体业务任务，例如售后处理、线索跟进、合同初审 |
| Run | 一次处理记录 | 某个任务从输入到输出的完整执行过程 |
| Trace | 过程轨迹 | 系统每一步怎么判断、调用了什么、依据是什么 |
| Eval | 效果评估 | 用案例集测试团队是否做得更好 |
| Evolution Proposal | 优化建议 / 进化提案 | 系统基于错误和反馈提出的改进草案 |
| Skill / Tool | 可用能力 | 查询订单、打开网页、发消息、调用内部系统等能力 |
| Approval | 人工审批 | 高风险动作必须经过人或规则确认 |
| Canary | 灰度发布 | 新版本先小范围试用，稳定后再扩大 |

---

## 3. 平台能力地图：业务方能用它做什么

对业务人员来说，平台不是一堆 Agent、Tool、Workflow，而是一套可以把业务经验沉淀成“可运行团队”的系统。

### 3.1 业务人员看到的能力

| 业务动作 | 平台表现 | 背后能力 |
|---|---|---|
| 创建一个业务空间 | 为客服、销售、法务、运营等团队创建独立空间 | 多租户隔离、权限、审计、配额 |
| 选择一个业务场景 | 售后工单、线索跟进、合同初审、内容审核等模板 | Scenario / Workflow |
| 搭建智能体团队 | 像配置岗位一样配置分类员、专家、质检员、审批员 | Agent Team Blueprint |
| 告诉团队怎么做事 | 填写规则、SOP、知识库、成功标准 | Instructions / Knowledge / Eval |
| 设置哪些事不能自动做 | 退款、外发消息、改数据库、真实账号操作等必须审批 | Approval Policy / Tool Policy |
| 试运行并看过程 | 看到每一步判断、依据、调用了什么系统 | Trace / Audit |
| 发布一个稳定版本 | v1、v2、v3 可回滚，可灰度 | Versioning / Canary |
| 让团队越做越好 | 系统根据错误和反馈提出优化草案 | Evolution Proposal |

### 3.2 业务侧使用旅程

```text
第一步：说清楚业务目标
例如：我想让 AI 帮客服处理售后退款咨询。

第二步：选择或生成团队模板
平台推荐：分类、订单查询、政策判断、回复生成、风险审批。

第三步：补充业务规则
例如：7 天无理由、生鲜不支持无理由、退款超过 1000 元要审批。

第四步：试运行
用历史工单测试，查看每一步判断是否符合业务预期。

第五步：发布
发布 v1，并设置只处理低风险工单或小流量灰度。

第六步：复盘和进化
系统发现常见错误，生成 v2 草案，业务方审核后再上线。
```

### 3.3 非技术用户的界面原则

页面不应该让业务人员填写技术字段，而应该围绕业务问题组织：

```text
你要解决什么业务问题？
这个团队需要哪些岗位？
每个岗位负责什么？
能查哪些资料和系统？
哪些动作必须人工确认？
怎样算处理得好？
上线前用哪些案例测试？
出了问题如何回退？
```

推荐页面结构：

```text
业务空间首页
├── 当前运行中的业务场景
├── 智能体团队列表
├── 今日处理量 / 成功率 / 人工介入率
├── 待审批事项
├── 系统发现的问题
└── 推荐优化建议
```

### 3.4 领先智感的产品抓手

平台要凸显领先感，建议重点打磨这些体验：

| 智感能力 | 业务感知 | 示例 |
|---|---|---|
| 意图补全 | 我说一个目标，系统帮我补全团队方案 | “售后处理”自动生成 5 个岗位和审批规则 |
| 业务追问 | 系统知道哪些关键信息没填 | “退款超过多少金额需要人工确认？” |
| 过程解释 | 每个结论都有依据 | “因为订单签收 3 天且非特殊类目，所以建议走 7 天无理由” |
| 风险预判 | 高风险动作自动拦截 | “该操作会向客户发送消息，需要审批” |
| 效果复盘 | 主动发现失败模式 | “生鲜退款场景人工纠正率偏高” |
| 一键生成改进版 | 从问题到新版本草案 | “是否生成 v2：新增特殊类目判断？” |
| 灰度和回滚 | 敢上线、能兜底 | “先给 5% 工单试运行，异常自动回 v1” |

---

## 4. 当前已有技术基础（工程读者）

这一节说明为什么 Hermes Agent Java 适合承载上面的业务体验。业务读者可以略读，技术读者需要重点看。

### 4.1 多租户基础

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

### 4.2 Agent 组织基础

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

### 4.3 Skill 与外部能力基础

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

### 4.4 安全执行基础

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

### 4.5 两个核心闭环：会做事，也会变好

平台要同时支持业务执行闭环和智能进化闭环。

---

#### 4.5.1 业务执行闭环

业务语言版本：

```text
收到任务
  ↓
判断任务类型
  ↓
分配给合适的智能体岗位
  ↓
查询资料或业务系统
  ↓
形成处理建议或结果
  ↓
需要时请求人工审批
  ↓
交付结果并留下记录
```

工程实现版本：

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
分类智能体判断为退款问题
  ↓
订单智能体查询订单状态
  ↓
政策智能体匹配售后规则
  ↓
文案智能体生成回复
  ↓
高风险金额触发人工审批
  ↓
最终回复客户
```

---

#### 4.5.2 智能进化闭环

业务语言版本：

```text
系统记录每次处理过程
  ↓
对比结果是否被人工纠正
  ↓
找出常见错误和薄弱环节
  ↓
提出一份可读的优化建议
  ↓
用历史案例测试新方案
  ↓
业务方审批
  ↓
小范围试运行
  ↓
稳定后扩大使用
```

工程实现版本：

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

示例：退款政策智能体漏判。

```text
系统发现：
- 最近 50 条售后工单中 12 条被人工纠正
- 错误集中在“7天无理由退款”场景

系统生成优化建议：
- 建议修改“售后政策智能体”的判断说明
- 补充“特殊类目商品”的判断步骤
- 增加高风险金额审批规则
- 生成 v4 草案
- 进入历史案例测试
```

---

## 5. 核心业务对象设计

这一节开始进入系统对象设计。为了降低理解门槛，每个对象先用业务语言解释，再给工程字段。

业务对象之间的关系可以理解为：

```text
一个业务空间
  └── 可以有多个业务场景
        └── 每个场景绑定一个智能体团队
              └── 团队有多个版本
                    └── 每次运行都记录使用了哪个版本
                          └── 运行结果进入评估和优化建议
```

换成业务说法：

```text
哪个部门在用 → 用来处理什么事 → 谁来处理 → 当前用的是哪套岗位配置 → 做得怎么样 → 要不要升级团队
```

---

### 5.1 Workspace：业务空间

业务理解：Workspace 就是某个部门、项目或业务线自己的 AI 工作区。

它解决的问题：

```text
客服团队的数据不要和销售团队混在一起
不同业务线可以有不同智能体团队
不同空间有独立权限、审计、成本和配置
```

工程实现：业务方看到的是 Workspace，底层映射到 Tenant。

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

业务理解：Scenario 是一个可以被反复执行的业务任务。

例如：

```text
售后工单处理
销售线索初筛
合同条款初审
公众号文章质检
采购申请预审
```

它解决的问题：同一个智能体团队可以服务多个任务入口，但每个任务都有自己的目标、成功标准和风险边界。

工程实现：Scenario 是业务目标与智能体团队之间的桥。

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

业务理解：Team Blueprint 就是一份“团队编制表 + 工作说明书”。

它描述：

```text
这个团队有哪些岗位
每个岗位负责什么
每个岗位能使用哪些工具和知识
遇到问题交给谁
哪些动作必须审批
当前线上使用的是哪个版本
```

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

业务理解：Agent Blueprint 是一个数字员工的岗位说明书。

它应该回答：

```text
你是谁？
你负责什么？
你不能做什么？
你能查哪些资料？
你能调用哪些系统？
你什么时候必须找人审批？
你的结果怎样才算合格？
```

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

业务理解：Run 就是一张“任务处理单”。

每次业务任务进来，无论成功、失败、转人工，都要留下记录：

```text
谁提交的任务
哪个团队处理的
用的是哪个版本
处理过程是什么
最终结果是什么
有没有触发审批
耗时和成本是多少
```

工程实现：每次业务调用都生成 Run。

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

业务理解：Eval Set 是一组“标准考题”。

它解决的问题：不能凭感觉说新版本更好，必须用历史案例和标准答案验证。

例如客服场景可以准备：

```text
典型退款案例
物流异常案例
高风险赔付案例
不应承诺赔偿的案例
必须转人工的案例
```

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

业务理解：Evolution Proposal 是系统写给业务负责人的“优化建议单”。

它不直接改线上团队，而是说明：

```text
发现了什么问题
证据是什么
建议怎么改
风险有多高
用哪些案例测过
是否建议灰度上线
```

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

> 收敛原则：先把“业务空间 + 团队蓝图版本化”做成可运行、可测试、可复用的核心底座，再向 Scenario、Run、Skill Policy、Evolution Proposal 扩展。

### Phase 0.5：MVP 合同与边界收敛

目标：把终局蓝图压缩成第一阶段可落地的工程合同，避免一开始引入过多对象和状态机。

核心决策：

```text
1. Workspace 是 Tenant 的业务化 façade，不重新实现租户系统
2. 第一版 workspaceId == tenantId，避免双 ID 映射和生命周期漂移
3. Team Blueprint 是业务方自助创建智能体团队的唯一载体
4. Blueprint Version 是后续 Scenario / Run / Evolution 的版本锚点
5. 第一版用文件持久化，先不上数据库强依赖
6. 第一版只做 API + 测试，不做复杂 UI
7. 第一版不做 Scenario / Run / Evolution，只为它们预留字段和模型边界
```

暂不做清单：

```text
Scenario 执行入口
Run 执行记录
Eval Set
Evolution Proposal
灰度发布
自动回滚
复杂审批流
前端复杂配置器
SQLite / PostgreSQL repository
```

这些能力不是不重要，而是依赖 Team Blueprint Versioning。先做会导致模型返工。

---

### Phase 1：Workspace + Team Blueprint Versioning 最小骨架

目标：业务方能创建业务空间，并在业务空间内创建、查询、版本化和激活智能体团队蓝图。

#### 11.1 Workspace MVP

Workspace 第一版只做 Tenant façade。

建议包结构：

```text
com.nousresearch.hermes.workspace
├── WorkspaceRecord.java
├── WorkspaceService.java
└── WorkspaceRoutes.java
```

对象建议：

```json
{
  "workspaceId": "customer-support",
  "tenantId": "customer-support",
  "name": "客服业务空间",
  "description": "售前售后智能体团队",
  "owner": "business-admin",
  "status": "ACTIVE",
  "createdAt": "2026-06-15T00:00:00Z",
  "updatedAt": "2026-06-15T00:00:00Z"
}
```

实现约束：

```text
workspaceId 第一版直接等于 tenantId
POST /workspaces 内部调用 TenantManager.createTenant
GET /workspaces 内部从 TenantManager.getAllTenants 映射
不要让业务 API 暴露 Tenant 的配额、安全、沙箱细节
不要为 Workspace 另建生命周期状态机
```

最小 API：

```http
GET  /api/v1/workspaces
POST /api/v1/workspaces
GET  /api/v1/workspaces/{workspaceId}
```

POST 示例：

```json
{
  "workspaceId": "customer-support",
  "name": "客服业务空间",
  "description": "售前售后智能体团队",
  "owner": "business-admin"
}
```

响应示例：

```json
{
  "ok": true,
  "workspaceId": "customer-support",
  "tenantId": "customer-support",
  "status": "ACTIVE"
}
```

---

#### 11.2 Team Blueprint MVP

建议包结构：

```text
com.nousresearch.hermes.blueprint
├── AgentBlueprintRecord.java
├── TeamBlueprintRecord.java
├── TeamBlueprintVersion.java
├── TeamBlueprintRepository.java
├── FileTeamBlueprintRepository.java
├── TeamBlueprintService.java
└── TeamBlueprintRoutes.java
```

第一版持久化路径：

```text
~/.hermes/tenants/{workspaceId}/business/team-blueprints/{teamId}.json
```

为什么放在 tenant 目录下：

```text
符合现有租户隔离模型
天然跟随 tenant 生命周期
备份/迁移边界清晰
后续可由 repository 替换为 SQLite / PostgreSQL
```

Team Blueprint 示例：

```json
{
  "teamId": "after-sales-team",
  "workspaceId": "customer-support",
  "name": "售后智能体团队",
  "description": "处理售后工单",
  "activeVersion": "v1",
  "versions": [
    {
      "version": "v1",
      "status": "ACTIVE",
      "createdAt": "2026-06-15T00:00:00Z",
      "createdBy": "business-admin",
      "agents": [
        {
          "agentId": "ticket-router-agent",
          "name": "工单分类智能体",
          "role": "分类与路由",
          "mission": "判断用户问题类型并选择后续处理路径",
          "instructions": "先判断工单类型，再选择合适处理智能体。",
          "allowedTools": [],
          "allowedSkills": [],
          "knowledgeScope": [],
          "handoffPolicy": {
            "defaultTarget": "policy-agent"
          },
          "approvalPolicy": {}
        }
      ]
    }
  ]
}
```

版本状态第一版只保留：

```text
DRAFT
ACTIVE
ARCHIVED
```

不要一开始引入 `PENDING_EVAL / EVAL_PASSED / ROLLED_OUT / ROLLED_BACK`，这些属于 Evolution 阶段。

关键不变量：

```text
1. 同一个 teamId 在同一 workspace 内唯一
2. 同一个 team blueprint 至多一个 ACTIVE version
3. activeVersion 必须指向一个 status=ACTIVE 的版本
4. ACTIVE version 不允许原地修改
5. 新变更只能创建新的 DRAFT version
6. activate(version) 会将旧 ACTIVE 改为 ARCHIVED，将目标版本改为 ACTIVE
7. 每个 version 至少包含一个 agent
8. agentId 在同一个 version 内唯一
9. allowedTools / allowedSkills 第一版只记录，不执行强校验
10. 所有写操作必须确认 workspace 存在
```

最小 API：

```http
GET  /api/v1/workspaces/{workspaceId}/team-blueprints
POST /api/v1/workspaces/{workspaceId}/team-blueprints
GET  /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions
POST /api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/activate
```

创建 Team Blueprint 时建议自动创建 v1：

```json
{
  "teamId": "after-sales-team",
  "name": "售后智能体团队",
  "description": "处理售后工单",
  "createdBy": "business-admin",
  "agents": [
    {
      "agentId": "ticket-router-agent",
      "name": "工单分类智能体",
      "role": "分类与路由",
      "mission": "判断用户问题类型并选择后续处理路径",
      "instructions": "先判断工单类型，再选择合适处理智能体。"
    }
  ]
}
```

激活版本请求：

```json
{
  "version": "v2",
  "activatedBy": "business-admin"
}
```

---

#### 11.3 DashboardServer 接入方式

参考现有：

```java
TenantDashboardIntegration.registerRoutes(app, tenantManager);
```

新增一个业务平台路由注册入口：

```java
BusinessPlatformRoutes.registerRoutes(
    app,
    tenantManager,
    workspaceService,
    teamBlueprintService
);
```

建议先集中注册，后续再按模块拆分：

```text
BusinessPlatformRoutes
├── workspace routes
└── team blueprint routes
```

原因：

```text
减少 DashboardServer 膨胀
避免 workspace / blueprint 路由各自重复解析错误响应
便于统一 /api/v1/business-platform 或 /api/v1/workspaces 前缀策略
```

---

#### 11.4 测试策略

参考：

```text
src/test/java/com/nousresearch/hermes/dashboard/DashboardTenantRoutesTest.java
```

新增建议：

```text
src/test/java/com/nousresearch/hermes/business/BusinessPlatformRoutesTest.java
src/test/java/com/nousresearch/hermes/blueprint/TeamBlueprintServiceTest.java
```

必须覆盖：

```text
[ ] POST /api/v1/workspaces 创建 workspace，同时创建底层 tenant
[ ] GET /api/v1/workspaces 能看到刚创建的 workspace
[ ] 重复创建 workspace 返回 409
[ ] POST /team-blueprints 创建 team blueprint，并自动生成 active v1
[ ] GET /team-blueprints/{teamId} 返回 activeVersion=v1
[ ] POST /versions 创建 v2，状态为 DRAFT，不影响 activeVersion
[ ] POST /activate 激活 v2 后，v1=ARCHIVED，v2=ACTIVE
[ ] 创建 blueprint 时 workspace 不存在返回 404
[ ] 创建空 agents 的 version 返回 400
[ ] 同一 version 内重复 agentId 返回 400
```

---

#### 11.5 与后续阶段的接口预留

Phase 1 不实现 Run，但 Team Blueprint Version 必须为 Run 预留稳定引用：

```text
workspaceId
teamId
version
agentId
```

后续 RunRecord 应至少记录：

```text
workspaceId
scenarioId
teamId
teamVersion
agentVersions 或 blueprintVersionRef
traceId
input
output
status
```

Phase 1 不实现 Evolution，但版本模型必须支持 candidate 语义。第一版可以用 DRAFT 表示候选版本，后续再扩展：

```text
DRAFT -> CANDIDATE -> PENDING_EVAL -> APPROVED -> ACTIVE
```

不要在 Phase 1 提前实现复杂状态机，只保证数据结构不阻断未来扩展。

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

> Workspace façade + file-backed Team Blueprint Versioning。

原因：

```text
Workspace 是业务入口，但底层应复用 TenantManager
Team Blueprint 是业务自助创建智能体团队的载体
Versioning 是 Scenario / Run / Evolution 的共同前置条件
文件持久化最快能跑通闭环，也符合现有项目风格
```

最小交付：

```text
1. WorkspaceRecord
2. WorkspaceService
3. WorkspaceRoutes
4. 创建 Workspace 自动创建 Tenant
5. TeamBlueprintRecord
6. AgentBlueprintRecord
7. TeamBlueprintVersion
8. FileTeamBlueprintRepository
9. TeamBlueprintService
10. TeamBlueprintRoutes
11. DashboardServer 注册 BusinessPlatformRoutes
12. API 测试：workspace 创建/查询
13. API 测试：blueprint 创建/v2/激活
14. 文档更新
```

第一版无需做复杂 UI，可以先做 API 和测试。

验收标准：

```text
[ ] mvn test 能通过新增测试
[ ] POST /api/v1/workspaces 能创建底层 tenant
[ ] GET /api/v1/workspaces 能返回业务化 workspace 列表
[ ] POST /api/v1/workspaces/{workspaceId}/team-blueprints 能创建 v1 ACTIVE
[ ] POST /versions 能创建 v2 DRAFT
[ ] POST /activate 能切换 activeVersion
[ ] ACTIVE version 不允许被原地修改
[ ] 所有错误响应包含明确 workspaceId / teamId / version
```

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

业务方打开平台后，不应该感觉自己在使用“开发者控制台”，而应该像在配置一个可以上线工作的数字团队。

### 16.1 第一次创建团队

```text
1. 创建“客服业务空间”
2. 输入一句话目标：“我想自动处理售后退款咨询”
3. 平台自动生成售后智能体团队草案：
   - 工单分类智能体
   - 订单查询智能体
   - 售后政策智能体
   - 回复文案智能体
   - 风险审批智能体
4. 平台追问关键规则：
   - 退款超过多少钱必须审批？
   - 哪些商品不能自动承诺退款？
   - 回复客户前是否需要人工确认？
5. 业务方上传或选择售后政策知识库
6. 用历史工单试运行
7. 查看每一步判断依据
8. 点击发布 v1
```

### 16.2 日常运行

业务首页应该呈现：

```text
今日处理工单：128
自动完成：96
转人工：21
需审批：11
平均处理时长：23 秒
人工纠正率：4.8%
主要风险：生鲜退款判断不稳定
系统建议：生成特殊类目政策判断 v2 草案
```

业务人员可以点开任意一单看到：

```text
这单为什么这么处理？
用了哪些政策依据？
查了哪些系统？
有没有越权动作？
如果不满意，如何纠正？
纠正后是否沉淀为优化建议？
```

### 16.3 持续进化

```text
1. 系统发现失败模式
2. 自动生成优化建议
3. 自动用历史案例回放测试
4. 给出新旧版本对比
5. 业务方审批
6. 先给 5% 流量灰度
7. 指标稳定后扩大到 100%
8. 异常时自动回滚到旧版本
```

最终体验：

> 业务人员只需要定义目标、规则和边界；平台负责组织智能体团队执行、复盘和进化；高风险动作始终留在人和治理规则手里。

这就是从“Agent 工具”升级为“业务智能组织平台”的路径。
