# AI 组织原生化模块 (org)

将 AI Agent 从匿名工具提升为组织正式成员的核心模块。

## 模块结构

```
org/
├── identity/             # P0: Agent 身份与认证
│   ├── AgentCredential.java      # 凭证管理 (API Key/OAuth/TLS/JWT)
│   ├── AgentIdentity.java        # Agent 完整身份
│   └── AgentIdentityManager.java # 身份生命周期管理
│
├── handoff/              # P0: 人机交接协议
│   ├── HandoffContext.java       # 交接上下文打包
│   └── HandoffProtocol.java     # 交接生命周期 (SLA/升级/决议)
│
├── auth/                 # P0: RBAC + ABAC 权限
│   ├── RoleBasedAccessControl.java      # 基于角色的访问控制
│   ├── AttributeBasedAccessControl.java # 基于属性的访问控制
│   └── PermissionPolicy.java            # 统一权限引擎
│
├── knowledge/            # P1: 组织知识库
│   ├── KnowledgeEntry.java               # 知识条目
│   └── OrganizationalKnowledgeBase.java  # 知识库 (RAG-ready)
│
├── workflow/             # P1: 持久化工作流引擎
│   ├── Workflow.java         # 工作流定义与状态
│   ├── WorkflowStep.java     # 工作流步骤
│   └── WorkflowEngine.java   # 工作流执行引擎
│
└── eval/                 # P1: Agent 评估框架
    └── AgentEvaluation.java  # 评估/A-B对比/漂移检测
```

## 快速使用

### Agent 身份

```java
AgentIdentityManager manager = new AgentIdentityManager();

AgentRole role = new AgentRole("code-reviewer", "Code Review", AgentRole.Level.SENIOR)
    .skills("java", "python", "security")
    .allowedTools("file:read", "web:search");

AgentIdentity identity = manager.provision("agent-001", "CodeBot v1", role)
    .department("engineering");

// 验证请求
Optional<AgentIdentity> auth = manager.authenticateByApiKey(rawKey);
```

### 人机交接

```java
HandoffProtocol protocol = new HandoffProtocol();
protocol.start();

// Agent 触发审批
HandoffContext ctx = protocol.requestApproval(
    "ci-bot",                  // 发起 Agent
    "Release v1.0.0",          // 摘要
    "All checks passed...",    // 详情
    "release-manager",         // 审批人
    600                        // 超时秒数
);

// 人类审批后恢复
protocol.resolve(ctx.getHandoffId(), "alice", "approve", "LGTM");
```

### 权限检查

```java
PermissionPolicy policy = new PermissionPolicy();
policy.rbac().assignRole("agent-1", "CONTRIBUTOR");
policy.abac().addPolicy(AttributeBasedAccessControl.deployGate(10));

// 组合检查
PermissionResult result = policy.authorize(
    "agent-1", "code:deploy", "/deploy",
    Map.of("tags", Set.of("production-safe")),  // 主体属性
    Map.of("classification", "INTERNAL"),        // 资源属性
    Map.of("time", "10:30")                      // 环境属性
);
```

### 知识库

```java
OrganizationalKnowledgeBase kb = new OrganizationalKnowledgeBase();

KnowledgeEntry sop = new KnowledgeEntry("sop-001",
    KnowledgeEntry.Type.SOP,
    KnowledgeEntry.Classification.INTERNAL,
    "Deploy Procedure",
    "1. Run tests. 2. Review...",
    "ops-team")
    .tag("deployment", "production")
    .topics("infrastructure");
kb.put(sop);

// RAG 上下文
String context = kb.buildRagContext("how to deploy", 5, 500);
```

### 工作流

```java
WorkflowEngine engine = new WorkflowEngine(Path.of("/data/workflows"));
engine.handoffProtocol(protocol);
engine.start();

// 注册步骤执行器
engine.registerExecutor("TOOL_CALL", (wf, step) -> {
    // 执行工具调用...
    return new Workflow.StepResult(step.getName(), true, "ok", null, Instant.now());
});

// 提交工作流
List<WorkflowStep> steps = List.of(
    WorkflowStep.toolCall("check_status", "terminal", Map.of("cmd", "systemctl status")),
    WorkflowStep.humanApproval("approval", "Approve deployment?"),
    WorkflowStep.toolCall("deploy", "terminal", Map.of("cmd", "kubectl apply"))
);
Workflow wf = new Workflow("wf-001", "Deploy", "Prod deploy", "ops", "tenant-1", steps, true);
engine.submit(wf);
```

### Agent 评估

```java
AgentEvaluation.EvalResult result = new AgentEvaluation.EvalResult.Builder()
    .agentId("agent-001")
    .agentVersion("1.2.0")
    .task("Fix NullPointerException in UserService")
    .score(AgentEvaluation.Dimension.ACCURACY, 0.95)
    .score(AgentEvaluation.Dimension.SAFETY, 1.0)
    .duration(Duration.ofSeconds(12))
    .tokens(1500)
    .cost(0.003)
    .build();

// A/B 对比
Map<String, Object> comparison = AgentEvaluation.compare(resultV1, resultV2,
    AgentEvaluation.productionWeights());

// 漂移检测
List<String> drifts = AgentEvaluation.detectDrift(baseline, current, 0.1);
```

## 预定义角色

| 角色 | 典型权限 | 适用场景 |
|------|---------|---------|
| VIEWER | 只读文件 + Web 搜索 | 信息查询 Agent |
| CONTRIBUTOR | 读写文件 + 代码执行 + 数据操作 | 开发 Agent |
| MAINTAINER | + 删除文件 + 部署 + Agent 管理 | 运维 Agent |
| ADMIN | 全部权限 + 审计 + 计费 + 策略 | 管理 Agent |

## 测试

```bash
mvn test -pl . -Dtest="com.nousresearch.hermes.org.*"
```

## 下一步

- P2: 分布式 Agent 网格 (服务发现/负载均衡/分布式追踪)
- P2: 成本归因与预算控制 (部门级账单/动态预算/模型比价)
- P2: Agent 市场与模板 (预配置模板/能力发布/评分)
- P3: Agent 自我进化 (失败学习/持续微调/能力传递)
- P3: 合规与监管 (SOC2/ISO27001/可解释性)
