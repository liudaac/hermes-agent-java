# Hermes Agent Java — 架构设计

> 业务智能体团队平台：定义团队 → 配置场景 → 执行任务 → 评估 → 自我进化

## 一、总体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Dashboard API Layer                           │
│  (Javalin HTTP + SSE，所有模块通过 DashboardIntegration 注册路由)     │
├──────────────┬──────────────┬──────────────┬────────────────────────┤
│   Workspace  │  Blueprint   │   Scenario   │     Run / Approval     │
│   业务空间    │  团队蓝图     │   业务场景    │  运行记录 / 审批中心    │
├──────────────┴──────────────┴──────────────┴────────────────────────┤
│                        Business Services 业务服务层                   │
│  ┌──────────┬─────────┬──────────┬──────────┬────────────────────┐ │
│  │ Approval │   Run   │ Insight  │  Policy  │    Safety Valve    │ │
│  │ 审批中心   │ 业务运行  │ 洞察分析  │  策略服务  │    安全阀适配器     │ │
│  └──────────┴─────────┴──────────┴──────────┴────────────────────┘ │
├─────────────────────────────────────────────────────────────────────┤
│                      Blueprint / Domain Model 领域层                 │
│  TeamBlueprint (版本化)  AgentBlueprint  Scenario  EvalSet          │
│  Evolution Proposal  Canary Release  Active Memory                  │
├─────────────────────────────────────────────────────────────────────┤
│                   Collaboration Foundation 协作内核                   │
│  IntentOrchestrator  TaskOrchestrator  TeamManager  TenantBus       │
│  AgentRole  DelegationPolicy  GovernancePolicy                      │
├─────────────────────────────────────────────────────────────────────┤
│                      Agent Core Agent 核心层                         │
│  TenantAwareAIAgent  ToolRegistry  ToolDispatcher  ModelClient     │
│  MemoryManager  CognitiveTrace  IterationBudget                    │
├─────────────────────────────────────────────────────────────────────┤
│                     Tenant Infrastructure 租户基础设施               │
│  TenantContext  TenantManager  TenantConfig  TenantSecurityPolicy  │
│  TenantQuota  TenantSandbox  TenantAudit  TenantSkillManager       │
├─────────────────────────────────────────────────────────────────────┤
│                       Plugins & Extensions 扩展层                    │
│  PluginLoader  PluginScanner  PluginRegistry  HookSystem           │
└─────────────────────────────────────────────────────────────────────┘
```

## 二、核心概念

| 概念 | 说明 | 对应模块 |
|------|------|----------|
| **Workspace** | 业务空间，隔离不同业务线/团队的数据 | `workspace/` |
| **Team Blueprint** | 团队蓝图，定义一个 agent 团队的成员、角色、工具权限 | `blueprint/` |
| **Agent Blueprint** | Agent 蓝图，定义单个 agent 的职责、工具、审批规则、工具级审批规则 | `blueprint/` |
| **Scenario** | 业务场景，定义一类任务的入口、成功标准、审批规则 | `scenario/` |
| **Run** | 运行记录，一次场景执行的完整业务故事，含路由的版本号 | `business/run/` |
| **Approval** | 审批记录，含场景级 + 工具级两种类型 | `business/approval/` |
| **ToolApprovalCoordinator** | 工具级审批协调器，连接 agent 和审批中心 | `business/approval/` |
| **Eval Set** | 评估集，用于验证 agent 团队的表现 | `evalset/` |
| **Evolution Proposal** | 进化提案，系统自动生成的团队优化建议 | `evolution/` |
| **Canary Release** | 灰度发布，新版本逐步放量 + 双版本对比指标 | `canary/` |
| **Policy** | 策略服务，workspace 和 agent 级别的 tool/skill 权限 + 审批规则 | `policy/` |

## 三、核心链路：从场景定义到执行完成

```
1. 创建 Workspace
   ↓
2. 创建 Team Blueprint（含多个 Agent）
   ↓
3. 创建 Scenario（绑定入口 Team）
   ↓
4. 触发 Scenario 执行
   ├─→ 检查审批规则 → 需要审批 → 创建 Approval + 挂起 Run
   │     ↓ 用户审批通过
   └─→ 审批通过 / 无需审批
        ↓
5. TeamBlueprintRuntime 确保 agent 实例运行在 TenantBus 上
   ↓
6. IntentOrchestrator 分解任务，委派给合适的 agent
   ↓
7. Agent 接收子任务，调用工具，生成结果
   ↓
8. BusinessRunProjectionAdapter 投影为业务故事（Run）
   ↓
9. SSE 实时推送进度到前端
   ↓
10. 执行完成 → 可通过 Eval Set 评估 → 生成 Evolution Proposal
```

## 四、权限模型（三层）

```
租户级 Security Policy (TenantSecurityPolicy)
       ↓ 全局兜底
工作区级 Policy (WorkspacePolicyRecord)
       ↓ workspace ∩ agent
Agent 蓝图级 (AgentBlueprintRecord.allowedTools)
       ↓ 写入运行时
运行时 AgentRole (allowedTools + deniedTools)
       ↓ 两处拦截
├─ buildToolDefinitions() → LLM 看不到不允许的工具
└─ executeToolCall() → 硬拦截，绕过 LLM 也没用
```

**解析规则：**
- 白名单：取交集（workspace ∩ agent），最严格者胜
- 黑名单：取并集（workspace denied 全局生效）
- 都没配置：不限制（默认放行）

## 五、审批模型

### 审批触发点

| 触发点 | 粒度 | 配置位置 | 说明 |
|--------|------|---------|------|
| Scenario 执行前 | 场景级 | `agent.approvalRules` | 执行前检查规则，需要就挂起整个场景 |
| 工具调用时 | 工具级 | `agent.toolApprovalRules` | agent 调用高风险工具时暂停，断点续传 |

### 审批规则语法

```json
{
  "approvalRules": [
    "always",                    // 任何执行都要批
    "high-risk",                 // 命中高风险关键词才批
    "external-action",           // 涉及外部动作才批
    "contains:删除"              // 输入包含特定关键词
  ],
  "toolApprovalRules": [
    "always",                    // 所有工具调用都要批
    "high-risk",                 // exec/delete/write/refund 等高风险工具
    "external",                  // send/email/post/browser 等对外工具
    "tool:exec",                 // 特定工具
    "contains:rm -rf"            // 工具参数包含特定关键词
  ]
}
```

### 审批状态流转

```
PENDING → APPROVED → 执行恢复
   │
   ├→ REJECTED → Run 标记为 FAILED（场景级）/ 工具调用注入"拒绝"错误（工具级）
   │
   └→ INFO_REQUESTED → 等待补充信息
```

### 工具级审批：断点续传

工具级审批使用 **路线 B（断点续传）** 实现，agent 不需要重新执行：

```
1. Agent LLM 决定调用工具
2. executeToolCall() 命中 toolApprovalRules
3. Agent 保存 Checkpoint：
   - assistantMessage（含所有 tool calls）
   - toolCalls 列表 + pendingIndex
   - completedResults（已执行的工具结果）
   - 对话历史快照位置
   - 剩余迭代预算
4. 抛 ToolApprovalRequiredException
5. ToolApprovalCoordinator 创建 BusinessApprovalRecord（type=tool-call）
6. 用户审批 → coordinator.resumeToolApproval()
   → agent.resumeToolApproval(approved, reason)
7. 批准：执行该工具 → 继续剩余 tool calls → 继续 LLM 循环
   拒绝：注入 "Tool call rejected" → LLM 看到错误后调整策略
```

### 审批与 Run 双向关联

- 审批触发时自动创建 `NEEDS_APPROVAL` 状态的 Run
- Approval.runId ↔ Run.approvalId 双向关联
- 审批通过自动恢复执行（`/approve` 接口自动 resume）
- 审批拒绝自动标记 Run 为 FAILED
- 审批记录维护 timeline：`CREATED` → `APPROVED/REJECTED` → `EXECUTION_RESUMED`/`TOOL_APPROVED`

### 实时推送

- 工作区级 SSE：`/api/v1/workspaces/{id}/approvals/stream`
- 单审批 SSE：`/api/v1/workspaces/{id}/approvals/{apvId}/stream`
- Run 级 SSE：`/api/v1/workspaces/{id}/runs/{runId}/stream`

## 六、版本与进化

### 完整版本生命周期

```
Team Blueprint v1（active）
      ↓
Evolution Proposal（系统自动生成优化建议）
      ↓ 用户审批
应用 → Team Blueprint v2（draft）
      ↓
Canary Release: v1 → v2, 10% 流量
      ↓ 双版本并行运行，确定性哈希路由
      ├─ canary metrics: total / succeeded / failed / avgDuration / avgCost / successRate
      └─ baseline metrics: 同上
      ↓
比较指标
      ├─ 满意 → promote → v2 全量激活
      └─ 不满意 → rollback → 维持 v1
      ↓
Eval Set 评估 → 验证效果
```

### Canary 灰度发布

- **确定性路由**：同一个 user/request key 始终路由到同一版本（哈希取模）
- **双版本并行**：active version 和 canary version 的 agents 同时在 TenantBus 上运行
- **指标自动对比**：每个 run 完成时按版本累计 success rate / duration / cost
- **Run 可追溯**：每个 Run 的 metadata 都标记了路由的版本和是否走了灰度
- **流量调节**：可以从 5% → 25% → 50% → 100% 逐步放量
- **API**：`/api/v1/workspaces/{id}/teams/{tId}/canaries`

## 七、关键设计决策

### 1. 业务层 vs 基础层分离

Business 层的所有模块（Approval/Run/Insight/SafetyValve）都通过 **Adapter** 模式接入基础层。业务层不直接依赖 agent 内部实现，而是通过 IntentOrchestrator 等公共接口交互。

好处：
- 业务逻辑可以独立演进
- 基础层保持通用
- 换底层引擎不影响业务层

### 2. Projection 模式

基础层的执行结果（IntentRun）通过 `ProjectionAdapter` 投影为业务层的 `BusinessRunRecord`。不是一一对应，而是业务视角的翻译。

### 3. DashboardIntegration 模式

每个模块自己定义 HTTP 路由，通过 `registerRoutes(app, service)` 统一注册到 DashboardServer。模块化、零侵入。

### 4. 文件存储优先

所有业务数据默认文件存储（`File*Repository`），不依赖数据库。简单、可移植、方便调试。后续需要时可以替换为数据库实现。

## 八、代码结构

```
src/main/java/com/nousresearch/hermes/
├── agent/                    # Agent 核心（TenantAwareAIAgent）
├── approval/                 # 基础审批（ACP approval classifier）
├── blueprint/                # 团队蓝图（TeamBlueprint）
├── business/                 # 业务层
│   ├── approval/             # 业务审批中心
│   ├── foundation/           # 业务门户基础（适配器注册）
│   ├── insight/              # 业务洞察
│   ├── run/                  # 业务运行记录
│   └── safetyvalve/          # 安全阀
├── canary/                   # 灰度发布
├── collaboration/            # 协作内核（IntentOrchestrator, TeamManager）
├── dashboard/                # Dashboard HTTP API
├── evolution/                # 进化提案
├── evalset/                  # 评估集
├── gateway/                  # 消息网关（飞书/QQ 等）
├── memory/                   # 记忆系统
├── org/                      # AI 原生组织（旧版组织模型）
├── policy/                   # 策略服务
├── scenario/                 # 场景服务
├── skills/                   # 技能系统
├── tenant/                   # 租户基础设施
├── tools/                    # 工具系统
└── workspace/                # 工作空间
```

## 九、测试覆盖

- 测试类：133+ 个
- 用例数：546 个（0 失败，4 跳过）
- 覆盖范围：单元测试 + 集成测试 + HTTP 路由测试 + Canary 路由测试 + 工具级审批测试

## 十、已知限制 & 下一步方向

### 已完成的核心能力 ✅

- ✅ Workspace + Team Blueprint + Scenario + Run（业务建模 + 执行）
- ✅ SSE 实时事件流（Run + Approval）
- ✅ 三层工具权限（tenant ∩ workspace ∩ agent）+ 拦截
- ✅ 场景级审批（执行前门禁 + 自动恢复）
- ✅ 工具级审批（执行中暂停 + 断点续传）
- ✅ Evolution Proposal 闭环（生成 → 审批 → 应用 → 激活 → 评估）
- ✅ Canary 灰度发布（双版本并行 + 哈希路由 + 指标对比）
- ✅ Eval Set 评估
- ✅ 审批时间线 + 双向关联

### 短期（生产化）

- [ ] 飞书/钉钉审批卡片集成
- [ ] 多级审批链（金额/风险等级动态决定层级）
- [ ] 审计日志统一入口
- [ ] 首页 Dashboard 数据概览
- [ ] 性能监控 + 成本分析

### 中期（深化）

- [ ] Active Memory 知识沉淀
- [ ] Skill / Tool 市场（可插拔）
- [ ] 多团队跨团队协作
- [ ] 数据库存储选项（替换 File*Repository）

### 长期（差异化）

- [ ] Agent 自我反思 + 跨 run 学习
- [ ] 协作模式可视化编辑器
- [ ] 多模态输入（图片/文档/语音）
