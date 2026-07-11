# 阶段 5：生态层与扩展

> 分析时间：2026-07-12
> 分析范围：`plugin/` 2 710 行、`skills/` 4 425 行、`acp/` 1 522 行、`collaboration/` 5 331 行、`evolution/` 991 行、`learning/` 1 259 行、`gateway/` 7 722 行、`connector/` 178 行，合计约 **24 000 行**
> HEAD：`e7c2b5b`

---

## 1. 总览

生态层是 Hermes 对接外部世界和扩展自身的"翅膀"：
- **Plugin 系统**：Hook 引擎 + JAR 插件加载 + 内置平台/传输 provider
- **Skills**：技能加载/创建/补丁/学习图/技能 hub/审核/Curator 后台
- **Collaboration**：多 Agent 协作核心（ScenarioOrchestrator + TenantBus + Negotiator + 5 种协作模式）
- **ACP**：Agent Collaboration Protocol（面向机器的 MCP 兼容服务端）
- **Evolution**：提案生成→评估→审批→应用的"AI 自我进化"流程
- **Learning**：会话结束后 LLM 抽取知识 + CuriosityEngine 主动学习
- **Gateway**：IM webhook 接入 + OpenAI 兼容 API + 多租户路由
- **Connector**：第三方系统（电商/ERP/物流/支付）连接器接口

**核心结论**：Plugin 体系和协作内核（ScenarioOrchestrator + TenantBus + Negotiator）是**真东西**，设计合理；Skills 体系最完整（有 Hub 客户端、补丁、审核、学习图、Curator）；Gateway 路由框架在，但**管理 API 鉴权严重缺失（P0 漏洞）**；ACP 是独立服务器，Dashboard 只挂了个 Integration 壳；Evolution 提案是文件+人工审批流程，LLM 生成但不自动应用；Connector 接口写了但没一个内置实现。

## 2. Plugin 系统（2 710 行）

### 2.1 HookEngine（119 行）— 16 个 HookType

```java
enum HookType {
    PRE_TOOL_CALL, POST_TOOL_CALL,
    TRANSFORM_TERMINAL_OUTPUT, TRANSFORM_TOOL_RESULT, TRANSFORM_LLM_OUTPUT,
    PRE_LLM_CALL, POST_LLM_CALL,
    PRE_API_REQUEST, POST_API_REQUEST,
    ON_SESSION_START, ON_SESSION_END, ON_SESSION_FINALIZE, ON_SESSION_RESET,
    PRE_GATEWAY_DISPATCH,
    PRE_APPROVAL_REQUEST, POST_APPROVAL_RESPONSE
}
```

覆盖 6 类生命周期：
- **工具**：pre/post/transform（可一票否决、可脱敏结果）
- **LLM**：pre/post（可注入 system prompt、改写响应）
- **API**：pre/post
- **会话**：start/end/finalize/reset
- **网关分发**：pre（可拦截 IM 消息）
- **审批**：pre/post

协议：`HookCallback.invoke(ctx) → List<Object>`，返回值语义：
- PRE_TOOL_CALL：返回 String 表示"blocked, reason"
- TRANSFORM_*：返回 String 表示替换 result
- 其他：返回 Map 表示附加数据
- 单个插件异常不影响其他插件（try/catch per callback）

⚠️ **不足**（上次盘点已提及）：无优先级、无命名空间、不能热加载。

### 2.2 PluginManager（761 行）

**3 种加载策略**（按优先级）：
1. **JAR 加载**（JarPluginLoader，162 行）：扫 plugin 目录下的 JAR，SPI 找 `META-INF/services/com.nousresearch.hermes.plugin.Plugin`
2. **类路径反射**：按约定 `plugin.builtin.{key}.{Key}Plugin` 类名加载
3. **目录加载**：每个 plugin 目录下找 `Plugin` 实现类

**3 类插件**：
- `backend/platform`：PlatformAdapter（Discord/Telegram/Feishu/QQ/Wecom）
- `backend/transport`：ChatCompletionsTransport（OpenAI/Anthropic/Bedrock/Codex）
- `tool/hook`：自定义工具和 hook（来自 JAR 或目录）

**内置 transport provider**（都在 plugin/builtin/transport/）：
- OpenAITransportProvider（68 行）
- AnthropicTransportProvider（67 行）
- BedrockTransportProvider（74 行）
- CodexTransportProvider（67 行）

⚠️ 跟阶段 1 发现的一致：这些 TransportProvider 注册了，但主路径 ModelClient 硬编码 OpenAI 协议没走 Transport 抽象——**Provider 注册了但主循环不消费**（半拉子）。

**Plugin 安全控制**：
- `isToolOverrideAllowed(key)`：决定插件能否覆盖已有工具名（默认 false）
- 加载失败的插件 mark `enabled=false, error=message`，不影响其他插件
- 内置插件自动加载，外部插件需配置开启

### 2.3 PluginYamlParser（133 行）
解析 plugin.yaml：name/version/kind/entrypoint/requirements/envVars/permissions/tools/hooks。

## 3. Skills 系统（4 425 行）

### 3.1 SkillManager（775 行）— 全局技能管理

**技能查找路径（3 层）**：
1. 用户技能：`<hermesHome>/skills/{name}/SKILL.md`
2. 外部技能：`HERMES_SKILLS_DIR` 或 `external_skills_dir` 配置
3. 内置技能：classpath `skills/`

支持：create/load/list/update/delete/search/getRelevantSkills（按 description 词匹配）/patch（精确替换 oldString→newString）/writeFile/removeFile/archive/restore

⚠️ **与 TenantSkillManager 两套并存**（阶段 2 盘过）：全局 SkillManager 是单租户/默认场景用，TenantSkillManager 做 4 层（private/installed/shared/builtin）租户隔离。**两个类并存，未抽象共享基类**。

### 3.2 Skill 学习与进化（~2 400 行）

| 类 | 行数 | 职责 |
|---|---|---|
| `LearningGraphService` | 311 | **学习图**：知识点节点+边，支持 add/query/shortestPath/连通分量 |
| `LearningGraphMutations` | 333 | 图变异操作（添加/合并/分裂/重命名节点） |
| `LearningGraphRenderer` | 331 | DOT/ASCII/Markdown 可视化 |
| `CuratorJob` | 588 | **Curator 后台任务**：定期扫描技能，做一致性/安全性/改进审查 |
| `CuratorCommandRegistrar` | 163 | 注册 `/curate` 命令 |
| `CuratorReviewPrompts`+`BackgroundReviewPrompts` | 286 | LLM 审查 prompt |
| `CuratorRunReport` | 135 | Curator 运行报告 |
| `PreVerifyHook` | 163 | 技能加载前 hook：注入验证、防 prompt 注入 |
| `SkillProvenanceService` | 206 | 技能溯源（来源、版本、谁改的） |
| `SkillHubClient` | 243 | 对接远程 SkillHub（skillhub.com？）发布/拉取技能 |
| `SkillBundleService` | 135 | 技能打包/导出 |
| `FineTuneExporter` | 152 | 技能→fine-tuning 数据导出 |
| `AchievementService` | 154 | 成就系统（解锁新技能） |
| `LearnPromptBuilder` | 153 | 学习 prompt 构造 |
| `LearnCommandRegistrar`+`JourneyCommandRegistrar` | 78+162 | `/learn`、`/journey` 斜杠命令注册 |

**亮点**：
- 有完整的技能生命周期：创建→补丁→审查（Curator）→溯源→打包→发布到 Hub→fine-tune 导出
- LearningGraph 是个真正的图结构，能做知识关联
- PreVerifyHook 防 prompt 注入（检查 dangerous pattern）

**问题**：
- CuratorJob 被 `ToolInitializerV2.initializeAll()` 里的 `CuratorCommandRegistrar` 注册，但没看到定期调度的代码——可能是手工触发
- SkillHubClient 写了 HTTP 客户端，但 skillhub 端点 URL 是配置项，默认没设置

## 4. 多 Agent 协作（5 331 行）— 真实落地

### 4.1 ScenarioOrchestrator（1 347 行）— 协作核心

**核心流程**：
```
plan(intent, preferredTeamId, allowDelegation, signals, pattern)
│
├─ 选 team（preferredTeamId 或默认第一个活跃 team）
├─ IntentDecomposer.decompose(intent, pattern, roles) 用 LLM 把 intent 拆成 subtask 列表
├─ 每个 subtask 用 CapabilityScorer + findBestMatch 找最合适的 agent
│
execute(plan) → startRun() → new Thread() → executeByPattern(pattern)
```

**5 种协作模式**（CollaborationPattern）：
| 模式 | 实现 | 场景 |
|---|---|---|
| SEQUENTIAL | for 循环串行 | 默认，稳定 |
| PARALLEL | 每个 subtask 一个 daemon thread + CountDownLatch（300s 超时） | 无依赖查询 |
| REVIEW | primary 生成 → reviewer 复核 → 人工确认 | 高风险（退款、审批） |
| COMPETITIVE | 多个 agent 并行做同一任务，CapabilityScorer 选最好结果 | 创意/方案比较 |
| MASTER_WORKER | master 拿到 plan 后分发给 workers 并行，最后聚合 | 可拆子任务的大任务 |
| PIPELINE | 按顺序流式（上游输出→下游输入） | 数据处理流水线 |

**delegateOne()**（核心分发）：
1. 启动 AgentTrace
2. 拿 TenantBus
3. 构造 AgentMessage（Type.REQUEST, action="delegate_task", payload=subtask+context）
4. `bus.sendAndWait(msg, 60_000L)` 等回复
5. 超时/失败 → handleExecutionFailure → 可能 retry → 可能 reroute 到其他 agent
6. 记录成功/失败到 IntentRun

失败处理（handleExecutionFailure）：
- 超时 → 自动 retry 一次，retry 时 reassign 到其他有能力的 agent
- 其他异常 → 记录 failure
- replayFailures(runId)：只重跑失败的 subtask

⚠️ **问题**：
- 用裸 `new Thread()` 而不是 Java 21 虚拟线程（阶段 1 提到过 background review 用了虚拟线程，但这里没用）
- 每个 execute 模式都手写线程管理，没统一 ExecutorService
- runs 是 CHM + `saveRuns()` 写 JSON（内存+文件），重启丢 running 状态
- IntentDecomposer 依赖 modelClient，LLM 拆任务如果失败回退到单 subtask

### 4.2 TenantBus（279 行）— 租户内消息总线

- **静态 tenantBuses CHM**：每个 tenant 一个 bus 实例（TenantContext.getOrCreateTenantBus() 里 DCL 创建）
- `handlers: CHM<agentId, Consumer<AgentMessage>>`：每个 agent 注册一个 handler
- `pendingReplies: CHM<messageId, CompletableFuture<AgentMessage>>`：sendAndWait 的 future 存储
- 消息路由：
  - `message.to()` 指定目标：发到指定 agentId handler
  - 广播：除 sender 外所有 handler
- sendAndWait：构造 REQUEST 消息 → 生成 replyId → put future → 调 handler → future.get(timeoutMs)
- handler 处理完 REPLY 消息时 complete future

**注意**：
- TenantBus 是**单 JVM 内**的内存消息总线，不是分布式消息队列
- Agent 必须在同 JVM 注册才能收到消息——这与阶段 2/4 发现的"单实例"模型一致
- 没看到消息持久化、重试、死信（但 ScenarioOrchestrator 层面有 retry）

### 4.3 Negotiator（248 行）— AI 协商

三阶段协商模型：
1. **Propose**：Agent 带 confidence 提建议
2. **Review**：Peer agent 评审（LLM 调 reviewPrompt）
3. **Decide**：confidence > 0.85 自动通过；< 0.4 自动升级人工；中间要 peer review

`autoNegotiate(proposerId, topic, proposal, confidence)` 是工具分发路径调用的简化版：
- conf ≥ 0.85 → Result.approved()
- conf < 0.4 → Result.needsHuman()
- 中间 → Result.needsReview()

**问题**：autoNegotiate 没真的调 LLM 做 peer review——只是阈值判断。完整协商流程（`negotiate` 方法）调 modelClient 走 3 阶段，但没有调用点（跟 Dispatcher 只接 autoNegotiate 一致）。

### 4.4 其他协作组件

| 类 | 行数 | 职责 |
|---|---|---|
| TeamRuntime | 213 | 团队运行时，持有一组 agent + bus 引用 |
| TeamRuntimeRegistry | 107 | 团队注册表 |
| AgentRuntimeProfile | 136 | agent 运行时画像（能力、负载、最近成功/失败率） |
| CapabilityScorer | 199 | agent 能力打分，决定任务路由 |
| IntentDecomposer | 250 | LLM 拆任务 |
| DelegatedTask/Store/Result/ExecutionResult | 187+185+116+157=645 | 委派任务持久化（文件） |
| DelegatedExecutorSafetyPolicy | 271 | 委派执行安全策略（防越权、防递归委派） |
| LocalPatchExecutor | 275 | 本地补丁执行（agent 提的代码 patch 应用） |
| ParentVerificationPolicy | 106 | 父 agent 对子 agent 结果的验证 |
| PatchSandboxPlan | 87 | patch 沙箱计划 |
| ContextPressureDetector | 105 | 检测上下文压力（对话太长），可能触发 summarization |
| TaskOrchestrator | 127 | 任务编排器（更高层的 orchestration，没看到调用点） |
| AgentMessage | 125 | 消息结构 |

**DelegatedExecutorSafetyPolicy** 亮点：禁止无限递归委派（A→B→C→A 环检测）、限制委派深度（默认 3）、限制危险操作跨 agent。

## 5. ACP（Agent Collaboration Protocol，1 522 行）

面向机器客户端（不是给人用的 Dashboard）的协议服务端，跟 MCP 兼容思路类似。

| 类 | 行数 | 职责 |
|---|---|---|
| `AcpServer` | 155 | Javalin 服务器：WebSocket 长连接 + HTTP REST + 命令路由到 ToolRegistry |
| `AcpEntry` | 140 | CLI main 入口（picocli），独立启动 ACP 服务器 |
| `AcpSessionManager` | 117 | WebSocket 会话管理（ConcurrentHashMap） |
| `AcpSession`/`AcpSessionFactory`/`AcpFork` | 286+79+66=431 | 会话生命周期 + fork 子会话（执行任务隔离） |
| `AcpTools` | 131 | ACP 专用工具（list_tools/call_tool/list_sessions/status） |
| `AcpPermissions`/`AcpPermissionChecker` | 126+92=218 | 权限控制（read/write/execute/admin 分级） |
| `AcpEvents` | 159 | 事件类型 |
| `AcpIntegration` | 84 | DashboardServer 里挂 ACP 的集成类（**但只构造，没看到 start**） |
| `AcpRequest`/`AcpResponse` | 41+46=87 | 协议结构 |

**部署形态**：独立 Javalin 实例（可以单独起端口），通过 picocli `AcpEntry` 启动，与 Dashboard/Gateway 解耦。DashboardServer 里构造了 AcpIntegration 但没看到 start 调用——需要后续检查是否真的挂载。

**设计意图**：让外部 IDE/编辑器/其他 AI 客户端通过 WebSocket 长连接接入 Hermes，调用内部工具，相当于 OpenAI Plugin / MCP 的服务端实现。

## 6. Evolution（991 行）— AI 自我进化提案

| 类 | 行数 | 职责 |
|---|---|---|
| `EvolutionProposalService` | 265 | CRUD 提案：DRAFT→EVALUATING→NEEDS_APPROVAL→APPROVED/REJECTED→APPLIED |
| `EvolutionProposalGenerator` | 214 | LLM 生成提案（从失败案例/使用统计中发现改进点） |
| `EvolutionProposalAdapter` | 205 | 把提案转成 TenantBlueprint 变更、可应用的 patch |
| `EvolutionProposalDashboardIntegration` | 159 | HTTP API |
| `FileEvolutionProposalRepository` | 84 | JSON 文件持久化 |
| `EvolutionProposalRecord` | 64 | 提案记录数据对象 |

**流程**：
1. Generator 定期/手工触发，LLM 分析失败库/指标 → 生成改进提案（prompt/tool/skill 改进）
2. 状态转 EVALUATING → LLM 自评收益和风险
3. 转 NEEDS_APPROVAL → 人工审批（Portal 上）
4. APPROVED → Adapter 应用（改 TeamBlueprint 或写 Skill 文件）
5. REJECTED → 记录拒绝原因

⚠️ **不自动应用**：必须人工审批通过才 APPLIED。这是正确的保守设计。但 Generator 的调度触发点没看到——可能只有手工调用。

## 7. Learning（1 259 行）— 知识抽取与主动学习

| 类 | 行数 | 职责 |
|---|---|---|
| `KnowledgeExtractor` | 410 | 会话结束用 LLM 做结构化知识抽取（entities/concepts/procedures/rules/preferences） |
| `InsightExtractor` | 234 | 提炼会话洞察 |
| `CuriosityEngine` | 269 | 主动学习：发现知识缺口，生成探索问题 |
| `LearningPipeline` | 92 | 串联 extract→insight→curiosity 的管道 |
| `ExtractedKnowledge` | 137 | 抽取结果结构 |
| `ExtractionPolicy` | 48 | 抽取策略（开启/关闭/隐私过滤） |
| `StructuredExtractionPrompts` | 69 | 抽取 prompt |

**调用链**：TenantAwareAIAgent.endSession() → learningPipeline.onSessionEnd() → KnowledgeExtractor.extract() → LLM 调 structured JSON 输出→ ExtractedKnowledge → 存 memory/skill。

## 8. Gateway（7 722 行）— IM 接入 + OpenAI API

### 8.1 GatewayServerV2（1 431 行，阶段 0/2 已盘部分）
- 两个 Javalin 实例之一（:8080）
- 路由：webhook/{platform}、/api/message、/api/chat[stream]、/v1/chat/completions（OpenAI 兼容）、/api/tenants CRUD、/api/sessions CRUD、/api/compare/*
- middleware：CORS、checkChatAuth（只保护 /api/chat*）、extractTenantContext（所有 /api/*）

### 8.2 🚨 P0 安全漏洞：管理 API 完全无鉴权
`extractTenantContext` 只是：
```java
String tenantId = ctx.header("X-Tenant-ID");
if (tenantId != null) ctx.attribute("tenant_id", tenantId);
```
**没有任何 token 校验、没有 session 校验**——意味着：
- `/api/tenants` GET：列所有租户
- `/api/tenants/{id}` GET/POST：看/改租户配置
- `/api/tenants/{id}/quota`、`/usage`、`/audit`、`/config`：看配额/用量/审计/配置
- `/api/sessions`、`/api/tenants/{id}/sessions/{id}/messages`：看所有会话消息
- `/api/compare/runs` 所有操作
- `/api/config`：看全局配置（可能含 API key？）

任何能访问 8080 端口的人，只要设 `X-Tenant-ID: {target}` header，就能看/改任意租户数据。checkChatAuth 只保护了 `/api/chat` 一个端点。这比阶段 3 发现的工具裸跑还严重——是公开管理 API。

⚠️ 待验证：这些 endpoint 的 handler 里是否二次校验权限？初步看不是——它们都从 ctx.attribute("tenant_id") 取 tenantId 直接用。

### 8.3 Platform 适配器
| 平台 | 适配器 | 行数 |
|---|---|---|
| QQ Bot | QQBotAdapter + QQBotUtils + QQBotCrypto | 685+236+106=1 027 |
| 飞书评论 | FeishuCommentAdapter | 523 |
| 飞书 | FeishuAdapter / FeishuAdapterV2 | 294 / 146 |
| 企业微信 | WeComCallbackAdapter | 312 |
| Telegram | TelegramAdapter | 222 |
| Discord | DiscordAdapter | 204 |

Feishu 有 V1/V2 两套（V2 更新），QQ Bot 最重（有消息加密解密）。Webhook 校验都在 adapter.verifyWebhook() 做签名验证。

### 8.4 其他 Gateway 组件
- `OpenAICompatHandler`（239 行）：实现 `/v1/chat/completions` 和 `/v1/models`，把 OpenAI 格式请求转成内部 agent 调用
- `SessionManager`（348 行）：Gateway 自己的会话管理（SQLite，与 agent 层的 TenantSessionManager 又是两套）
- `TenantController`（554 行）：租户 CRUD HTTP handler（配额、audit、config、suspend/resume）
- `ResourceSandboxController`（240 行）：资源沙箱 HTTP API
- `GatewayRunner`（405 行）：另一个启动入口（CLI）
- `RoutingResolver`/`ClusterTopology`（111+106）：路由/集群拓扑抽象——但 ClusterTopology 只有单节点实现，分布式是骨架
- `config/HermesConfig`（204 行）：Gateway 自己的 HermesConfig（跟 config 包的 HermesConfig/ConfigManager 又是**第三套配置类**）

### 8.5 GatewayServer（749 行，老版本）
跟 V2 并存，老版本的 GatewayServer，代码量大但主要被 V2 替代。

## 9. Connector（178 行）— 第三方集成接口

- `Connector` 接口（63 行）：getName/getLabel/Description/testConnection/execute/getSupportedOperations/getConfigSchema/configure/isHealthy + ConnectorOperation record
- `ConnectorRegistry`（115 行）：CHM 注册 + list/get/execute/healthCheck

**问题**：定义了接口但**0 个内置实现**（taobao/jushuitan/cainiao 等全是文档注释里举例）。是面向未来的扩展点。

## 10. 阶段 5 识别到的债/风险

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🔴 | **Gateway /api/tenants/* /api/sessions/* /api/compare/* /api/config 管理 API 无鉴权** | GatewayServerV2.extractTenantContext | 只靠 X-Tenant-ID header 无校验，能访问 8080 就能看/改任意租户数据 |
| 🟠 | TransportProvider 注册了但 ModelClient 不消费 | plugin/builtin/transport + ModelClient | Anthropic/Bedrock/Codex provider 是死代码，模型支持半拉子（跟阶段 1 一致） |
| 🟠 | ScenarioOrchestrator 用裸 new Thread() 而非虚拟线程 | executeParallel/executeReview/... | 协作并发模型粗糙，高并发场景线程数可能爆炸 |
| 🟠 | AcpIntegration 在 DashboardServer 构造但没看到 start | DashboardServer | ACP 可能没真正挂上去 |
| 🟡 | SkillManager（全局）和 TenantSkillManager（租户）两套并存未抽象 | skills/ + tenant/core/ | 代码重复，技能加载逻辑不一致 |
| 🟡 | Gateway 有自己的 HermesConfig 和 SessionManager（第三/第二套） | gateway/config/ + gateway/SessionManager | 配置类 3 套、会话管理 2 套并存 |
| 🟡 | GatewayServer（V1，749 行）和 V2 并存 | gateway/ | 老代码未清理，维护负担 |
| 🟡 | Negotiator.autoNegotiate 只做阈值判断不调 LLM | Negotiator.autoNegotiate | 跟 Dispatcher 集成的只是个简化版，完整 3 阶段协商没接线 |
| 🟡 | Connector 接口 0 内置实现 | connector/ | 电商/ERP 连接器全部待写，是未来工作 |
| 🟡 | IntentRun/Running 状态内存+JSON，重启丢 | ScenarioOrchestrator.runs | JVM 重启协作任务全部丢 |
| 🟡 | Evolution/Learning 的定时调度没接线 | EvolutionProposalService、CuratorJob | 提案生成、知识抽取靠手工或会话结束触发，没有定期后台调度 |
| 🟡 | TaskOrchestrator（127 行）没看到调用点 | collaboration/TaskOrchestrator | 死代码 |
| 🟢 | HookEngine 缺优先级/命名空间/热加载 | HookEngine | 扩展点功能基础但可用 |
| 🟢 | Curator/SkillHub/FineTuneExporter 等围绕 Skill 的功能链完整但部分未真正运行 | skills/ | 代码写了但调度没接 |

## 11. 阶段 5 小结

**生态层成熟度：Beta-（70%）核心协作扎实，扩展点多但边缘功能多半接线**

### 真正落地的核心
- ✅ HookEngine 16 个扩展点 + PluginManager 3 种加载策略（JAR/类路径/目录）
- ✅ ScenarioOrchestrator 5 种协作模式（SEQUENTIAL/PARALLEL/REVIEW/COMPETITIVE/MASTER_WORKER/PIPELINE）
- ✅ TenantBus 内存消息总线（sendAndWait + broadcast + reply/future）
- ✅ Negotiator 阈值协商 + 完整 3 阶段协商框架
- ✅ Skills 体系最完整（775 行 Manager + 学习图 + Curator + Provenance + Hub 客户端 + FineTune 导出）
- ✅ DelegatedExecutorSafetyPolicy 防委派环/深度限制
- ✅ PlatformAdapter 6 平台 webhook 签名验证都做了
- ✅ Evolution 提案流程（LLM 生成 → 人工审批 → 应用）
- ✅ Learning 管道（会话结束 LLM 抽取 + Curiosity 主动学习）
- ✅ ACP 独立协议服务端（面向机器客户端）

### 没接好/半拉子
- ❌ TransportProvider 注册但 ModelClient 不消费（阶段 1 已发现）
- ❌ ACP Integration 构造了但可能没 start
- ❌ Connector 接口 0 实现
- ❌ Gateway 管理 API 无鉴权（**P0**）
- ❌ Gateway V1/V2 + 3 套配置类 + 2 套会话管理并存
- ❌ 协作/evolution/learning 的定时调度多未接线
- ❌ ClusterTopology/Distributed 是骨架，无网络层

### 亮点
- **Skill 生态链最完整**：创建→审查→溯源→补丁→发布 Hub→Fine-tune 导出，是往"Agent 技能市场"方向铺路
- **协作模式 6 种**覆盖常见企业场景（顺序、并行、评审、竞争、主从、流水线）
- **Evolution 提案不自动应用**，保守设计适合生产
- **DelegatedExecutorSafetyPolicy** 防递归委派是真正的安全思考

### 下一步
→ **阶段 6：可观测性与运维**（dashboard handlers、monitoring、canary、evalset、compare、governance、insights 空模块），把 HTTP handler 全扫一遍，验证哪些 API 真能用、哪些是空壳。
