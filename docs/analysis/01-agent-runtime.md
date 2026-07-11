# 阶段 1：核心执行引擎（Agent Runtime）

> 分析时间：2026-07-11
> 分析范围：`agent/`、`model/`、`prompt/`（runtime 侧）、`memory/`、`trajectory/`
> HEAD：`e7c2b5b`

---

## 1. 总览

Agent Runtime 是整个系统的"大脑"——负责 LLM 对话循环、tool-call 编排、上下文管理、记忆注入、反思/学习、审批挂起/恢复。核心代码集中在一个类：

| 类 | 行数 | 角色 |
|---|---|---|
| **`TenantAwareAIAgent`** | **2362** | **大脑本体**：对话循环、tool 执行、审批 checkpoint、记忆/反思/学习触发、团队协作总线 |
| `ModelClient` | 645 | OpenAI 兼容 HTTP 客户端（chat/embedding/image/transcribe），JDK `java.net.http.HttpClient` |
| `SubAgent` | 278 | 子 agent 并行任务（background review/curator 用），独立上下文+预算 |
| `ContextCompressor` | 229 | 上下文压缩（纯文本启发式，不调 LLM） |
| `ReflectionEngine` | 241 | 会话结束 LLM 反思（打分+lessons+anti-patterns） |
| `CognitiveTraceCollector` | 184 | observe→orient→decide→evaluate 四步认知轨迹（OODA） |
| `ConfidenceCalibrator` | 145 | 回答置信度校准（不够准就追加"建议核实"提示） |
| `SessionScratchpad` | 140 | 会话临时便签 |
| `IterationBudget` | 71 | 原子迭代计数器（线程安全，CAS consume） |
| `agent/transports/*` | 10 文件 | **未接入主路径**的 Transport 抽象层（OpenAI/Anthropic/Bedrock/Codex） |
| `model/ModelMessage`/`ToolCall`/`ToolDefinition`/`ChatCompletionResponse` | ~400 行合计 | OpenAI 风格消息/工具调用数据结构 |
| `memory/MemoryManager` | 404 | 跨会话长期记忆（MEMORY.md / USER.md 文件） |
| `memory/MemoryRetriever` | 177 | BM25-lite 词法检索（中英混合分词） |
| `memory/PromptContextBuilder` | 154 | 每 turn 前注入 top-K 相关记忆 |
| `memory/{External,LocalFile,Mem0}MemoryProvider` | ~375 行合计 | 外部 Memory 提供商（本地文件 + Mem0） |
| `trajectory/TrajectoryCollector` + `Compressor` + `Entry` | ~655 行合计 | 全量轨迹记录 + gzip 压缩落盘 |

Runtime 核心代码量：约 **7 000 行**（不含 transports 空壳的话约 5 800 行）。

## 2. Agent 主循环（doProcessMessage）

`TenantAwareAIAgent.doProcessMessage()` 是所有对话的统一入口。流程如下：

```
processMessage(userMessage)
│
├─ [守卫] 租户状态/配额检查
├─ ensureAutoSkillsLoaded(channel)     ← 加载租户自动技能
├─ startTrace()                         ← org.observe.AgentTrace（可观测）
├─ doProcessMessage(message)
│   ├─ userTurnCount++
│   ├─ if first turn: HOOK(ON_SESSION_START)
│   ├─ conversationHistory.add(user msg)
│   ├─ smart memory card: memoryCardIntegrator.beforeTurn(...)  ← 注入 BM25 top-K 记忆
│   ├─ autoSaveSession()                ← 每次变化都持久化
│   │
│   ├─ while iterationBudget.hasRemaining():
│   │   ├─ ┃治理检查┃ isPaused / isOverBudget
│   │   ├─ HOOK(PRE_LLM_CALL) → 插件可注入 system message
│   │   ├─ modelClient.chatCompletion(history, toolDefs, stream=false)
│   │   ├─ HOOK(POST_LLM_CALL)
│   │   ├─ conversationHistory.add(assistant msg)
│   │   ├─ if response.hasToolCalls():
│   │   │   for each toolCall:
│   │   │     ├─ 角色级 allowed/denied 工具检查（agentRole）
│   │   │     ├─ 工具级审批规则检查（agentRole.toolApprovalRules）
│   │   │     │  └─ 不通过 → 抛 ToolApprovalRequiredException
│   │   │     │      （保存 checkpoint + trigger toolApprovalCallback）
│   │   │     ├─ toolDispatcher.dispatch(name, args)
│   │   │     │  （TenantAwareToolDispatcher 是真正的沙箱+权限+审计层，阶段3细盘）
│   │   │     ├─ 成功 → evolutionEngine.recordSuccess(...)
│   │   │     ├─ 失败 → evolutionEngine.recordFailure(FailureCase)
│   │   │     └─ conversationHistory.add(tool result)
│   │   └─ else (no tool calls, final answer):
│   │       └─ responseBuilder.append(content); break
│   │
│   ├─ persistSession()
│   ├─ confidenceCalibrator.calibrate(...)   ← 置信度不够就追加提示
│   ├─ if nudge intervals hit → spawnBackgroundReview()  ← 虚拟线程 fork 子 agent
│   └─ HOOK(TRANSFORM_LLM_OUTPUT) → 插件可改写最终文本
│
└─ endTrace()
```

### 2.1 流式版本（doProcessMessageStream）
- 结构跟非流式基本一致，区别是 `chatCompletion(..., stream=true, onChunk=chunkConsumer)` 把 token 增量实时推给 consumer
- **tool 调用不流式**：遇到 tool call 直接插入一个 `[Executing tool: xxx]` 占位符后同步执行
- **没有审批 checkpoint 支持**（直接 try/catch 并 rethrow RuntimeException，不处理 `ToolApprovalRequiredException`）——意味着 Gateway 的 `/api/chat/stream` 路径下审批会走异常路径爆出去，而不是暂停等待。🟡 这是债。

## 3. 系统 Prompt 拼装（buildSystemPrompt）

固定顺序拼接：
1. `Constants.DEFAULT_AGENT_IDENTITY`（Hermes 身份 + 浏览器工具可用性强调）
2. `MEMORY_GUIDANCE`（持久化记忆说明）
3. `TOOL_USE_ENFORCEMENT_GUIDANCE`（强制使用工具的硬规则）
4. `EXECUTION_DISCIPLINE_GUIDANCE`（强制纪律：算术/时间/git/web 都必须用工具、act_dont_ask、verification）
5. `SESSION_SEARCH_GUIDANCE`（何时用 session_search）
6. `SKILLS_GUIDANCE`（何时保存 skill、要打 patch）
7. `memoryManager.getSystemPromptSnapshot()`（MEMORY.md + USER.md 启动时冻结快照）
8. `toolPerformanceTracker.buildHintBlock()`（从历史记录学到的工具表现提示）
9. `evolutionEngine.buildEvolutionPrompt(agentId)`（从过去失败中提取的教训）
10. `buildTeamAwarePrompt()`（团队成员+最近活动，协作模式）

外部可以通过 `setSystemPrompt()` 覆盖整个 prompt（会替换 history[0]），或者通过 hook 注入 system message 片段。

## 4. 工具调用审批恢复（resumeToolApproval）

这是 Hermes 相对普通 ReAct agent 的关键差异化能力。

```
用户批准/拒绝
    │
    ▼
POST /api/jarvis/approval/{id}  {decision: "approve"/"reject"}
    │
    ▼
JarvisHandler.resolveApproval()
    │
    ▼
ApprovalBridge.resolve() → ToolApprovalCoordinator.resumeToolApproval()
    │
    ▼
TenantAwareAIAgent.resumeToolApproval(toolCallId, approved, reason)
    │
    ├─ 校验：cp.pendingTool.id == toolCallId
    ├─ 清 checkpointActive 标记
    ├─ approved? executeToolCall(pendingTool)  :  注入拒绝 tool error
    ├─ recordToolCall(...)
    ├─ conversationHistory.add(tool result for pending)
    ├─ for remaining toolCalls after pendingIndex:
    │     （另一把工具又撞审批规则？再抛 ToolApprovalRequiredException，建新 checkpoint）
    └─ continueConversationLoop(responseBuilder, userTurnCount, remainingIterations)
         └─ 重新进入 while 循环：调 LLM → 解析 tool calls → 继续执行...
```

**checkpoint 快照了什么**（`ToolApprovalCheckpoint` 内部类）：
- `assistantMessage`：触发审批的助理消息
- `toolCalls`：当轮所有 tool calls 列表
- `pendingIndex`：当前卡在第几个 tool
- `completedResults`：已经执行完的 tool 结果
- `historySize`：assistant 消息插入后的 history size
- `remainingIterations`：当时剩余迭代数
- `userTurnCount`：第几轮用户消息
- `fromSubtask` / `subtask` / `subtaskMessage`：来自协作子任务时的元数据

**并发/线程安全**：
- `approvalCheckpointActive` 是 `volatile boolean`
- `resumeToolApproval` 是同步的，调用方要保证同一个 agent 实例不能被并发 resume
- 注释里明确说 per-workspace agent 池复用实例——HTTP 线程上并发访问就会出问题。⚠️ **ChatService 里 agentPool 是 ConcurrentHashMap 但没在 agent 上加锁**，留到阶段 2/4 验证。

## 5. 实例创建路径（工厂方法）

TenantAwareAIAgent 有 6 个构造路径，最终收敛到 2 个 private 构造器：

```
createDefault(config)
  → forTenant("default", config)
    → new TenantAwareAIAgent("default", config, null, true)
      → ensureTenantManager() → manager.getOrCreateTenant("default", ...)
      → new TenantContext（通过 manager）
      → 绑定到 default 租户上下文

forContext(context, sessionId, config)
  → new TenantAwareAIAgent(context, null, null, sessionId, config, false)
    → 不注册到 bus（forContext 默认 registerOnBus=false，短生命周期场景）

forBlueprint(context, agentId, role, sessionId, config)
  → new TenantAwareAIAgent(context, agentId, role, sessionId, config, true)
    → 绑定指定 agentId+role，注册到 bus，长生命周期 team agent

fromGateway(platform, channelId, userId, config)
  → resolveTenantId(platform, channelId, userId)
  → forContext(...) 或 forBlueprint(...)（根据场景）
```

⚠️ **观察**：`createDefault(config)` 这个路径会 `new TenantManager()`，而不是复用 HermesAgentV2 里已经创建的 tenantManager。ChatService 里 agentPool 用的是 `tenantManager.getOrCreateTenant()` 注入的 TenantContext——所以要分清楚哪些地方是真多租户、哪些路径在偷偷 new TenantManager（会跟主 TenantManager 分裂，状态不共享）。

## 6. 长期记忆（Memory）

### 6.1 MemoryManager
- 存在 `<hermesHome>/memories/MEMORY.md` 和 `USER.md`；多租户场景存 `<hermesHome>/tenants/{tenantId}/memories/`
- 用 `§`（section sign）做条目分隔符（跟 Python 版一致）
- 字符硬上限：MEMORY 2200 字符 / USER 1375 字符
- **两态设计**：
  - `systemPromptSnapshot`：**load 时冻结**，整会话内不更新（保证 prefix cache 友好）
  - `memoryEntries`/`userEntries`：活状态，工具调用写入立即落盘，但不刷新已注入的 system prompt
- 安全：内置 7 个 THREAT_PATTERNS 正则防 prompt 注入（ignore previous instructions 等），过滤 10 种不可见 Unicode 字符

### 6.2 MemoryRetriever（BM25-lite）
- 轻量词法检索（不需要 embedding 模型）
- 分词：`[A-Za-z0-9]+` 和单个中文字符（CJK 按字切，符合中文检索直觉）
- 内置英文+中文停用词
- BM25 参数 K1=1.4, B=0.75（标准值）
- top-K 由 `memory.smart_card.top_k` 配置，默认 6

### 6.3 PromptContextBuilder
- `beforeTurn(history, userMessage)` 返回注入的 card 字符数
- 组合 MemoryRetriever 结果 + `always_include_profile=true` 时强制加 USER.md 顶部
- 不是替换 system prompt，是插入一条新的 system message：`[Relevant memory for this turn]: ...`

### 6.4 外部 Memory Provider
- `ExternalMemoryProvider` 接口
- `LocalFileMemoryProvider`：本地 JSON 文件（默认）
- `Mem0MemoryProvider`：对接 Mem0.ai 云记忆服务

⚠️ **但 `MemoryManager` 构造里没有注入这些 Provider**——都是直接读写本地 MD 文件，外部 Provider 接口只有 `memory/ExternalMemoryProvider.java` 的接口定义和两个实现，没看到被 `MemoryManager` 调用。死代码/未完成功能。🟡

## 7. 会话持久化

### 7.1 persistSession()
- 直接 new 一个 `gateway.SessionManager(hermesHome)` 然后把 `conversationHistory` 逐条 `session.addMessage(role, content)` 再 save
- ⚠️ **每次 new SessionManager 都要重新加载 SQLite 连接**——高频对话会有连接抖动
- ⚠️ **只存 role+content，不存 tool_calls、tool_call_id**——恢复会话后之前的 tool 关联全丢
- ⚠️ **没有去重**：`session.messages.clear()` 先清空再重建，但 saveSession 走的是 upsert 还是覆盖待验证
- 每加一条 assistant/tool message 都会 `autoSaveSession() → persistSession()`，高频 IO

### 7.2 TrajectoryCollector
- 存到 `<hermesHome>/tenants/{tenantId}/trajectory/{sessionId}.jsonl.gz`
- Entry 类型：MESSAGE/TOOL_CALL/TOOL_RESULT/LLM_CALL/ERROR/CHECKPOINT/REFLECTION
- TrajectoryCompressor 做 gzip 压缩
- 在 `endSession()` 时 shutdown

### 7.3 SessionScratchpad
- 会话内临时 KV 存储（`Map<String,Object>`），不跨会话持久化

## 8. 背景学习（Background Review）

spawnBackgroundReview 使用 `Thread.startVirtualThread()`（Java 21 虚拟线程）fork 一个 SubAgent：
- 复用同一个 modelClient 的配置
- tool 白名单：memory + skill 相关工具
- 三种 prompt：MEMORY_REVIEW / SKILL_REVIEW / COMBINED_REVIEW
- 如果 LLM fork 失败（异常），fallback 到 `reviewAndSaveMemoryHeuristic()` 启发式
- 结果写入 `pendingReviewSummaries` 队列，下一回合开始时 flush 到 response 开头
- 由 `memoryNudgeInterval=10`（每 10 user turns）和 `skillNudgeInterval=10`（每 10 tool iters）触发

## 9. 端会话流程（endSession）

```
endSession(completed)
├─ trajectoryCollector.endSession(...)
├─ learningPipeline.onSessionEnd(...)          ← 抽 insights
├─ reflectionEngine.reflect(...)               ← LLM 反思：score + lessons + anti_patterns
│  └─ 高置信度 lessons 写入 MEMORY.md（[LESSON] 标签）
├─ learningPipeline.runCuriosityScan()         ← 主动学习：弱主题补充
├─ governancePolicy.recordSuccess()
├─ agentRole 更新 metrics
├─ orgHealthChecker.updateHealth(...)
├─ persistSession()
├─ tenantContext.getSessionManager().persistAll()
├─ trajectoryCollector.shutdown()
├─ cognitiveTraceCollector.close()
└─ evalMetrics.logSnapshot()
```

## 10. ModelClient（HTTP 层）

- 用 JDK 11+ 自带 `java.net.http.HttpClient`（connect timeout 30s）
- 固定 OpenAI `/v1/chat/completions` 协议
- 支持 stream=true + onChunk callback（SSE 解析：逐行读 `data: {...}` 块，提取 `choices[0].delta.content`）
- 额外提供 createEmbedding / generateImage（DALL-E）/ transcribeAudio（Whisper）能力
- 错误处理：非 200 抛 RuntimeException，含 error body
- 多模型支持：**只改 base_url+api_key+model，协议固定 OpenAI 格式**

### 10.1 ⚠️ transports 抽象层未接入
`agent/transports/` 下有一套完整的 `BaseTransport` 抽象 + `ChatCompletionsTransport`(OpenAI)、`AnthropicTransport`、`BedrockTransport`、`CodexTransport` 实现，配套 `TransportProvider` 在 plugin 里注册。但：
- **`TenantAwareAIAgent` 里硬编码 `new ModelClient(config.getModelConfig())`**，完全没走 TransportFactory
- `SubAgent`、`Jarvis/ChatService`、`ProductQueryService`、`PlanReflectionService` 等也全部直接 new ModelClient
- 这意味着 Anthropic native API、Bedrock sigv4 签名、Codex 专用协议目前是死代码
- 要支持多模型协议，需要把主路径切到 Transport，但目前没切
- **Impact**：README/内置 provider 声称支持 Anthropic/Bedrock，实际只有"OpenAI 兼容模式"能跑（Anthropic 的 messages API 和 OpenAI chat/completions 不兼容，直接用会 400）

🟠 这是个 **README vs 代码不一致**的问题——Beta→GA 前要解决：要么删 transports 保持 OpenAI-only，要么把主路径切到 Transport。

## 11. 上下文压缩（ContextCompressor）

策略非常简单：
1. 估 token：`chars / 4`（粗糙）
2. 超过 target（max 的 80%）时，保留 system message + 最近 N 条
3. 把更早的消息做个**纯文本启发式摘要**（统计 user/assistant/tool 条数 + 前 200 字符 excerpt）
4. 插入 `[Earlier conversation summary]: ...` 作为 system message

⚠️ **没调用 LLM** 做总结（注释里写 "In a real implementation, this would use the LLM"），所以压缩质量很差。而且 `TenantAwareAIAgent` 主循环里**根本没调用 `compressor.compress()`**——这个组件没接到主路径，完全是死代码。Config 里虽然有 `compression.enabled/threshold/target`，但没人读。🟡

Config 默认 `DEFAULT_CONTEXT_LIMIT = 128000` tokens，但主循环**完全没有 token 计数或超限保护**——全靠 max_turns=90 次迭代来防止无限跑。长会话会把对话历史撑到爆上下文窗口然后 API 报错。🔴

## 12. Agent 协作钩子

- `setTeam(TeamRuntime)`：加入团队
- `buildTeamAwarePrompt()`：注入 `team.describeForPrompt()` 到 system prompt
- `handleBusMessage(AgentMessage)`：TenantBus 回调，存到 team 共享 state
- `handleIntentSubtask(AgentMessage)`：处理 intent 路由过来的子任务
- `sendBusReply(...)`：通过总线回复
- 这些是 `collaboration/` 包的入口，阶段 5 细盘

## 13. 数据流示意（Agent 视角）

```
    inbound message (Gateway/Chat/Jarvis)
            │
            ▼
    ┌─ TenantAwareAIAgent ─────────────────────────────────┐
    │                                                       │
    │  MemoryManager ──frozen snapshot──→ system prompt     │
    │       ▲                                               │
    │       │ write (memory tool)                           │
    │                                                       │
    │  MemoryRetriever ──top-K──→ PromptContextBuilder      │
    │    (BM25, per-turn)                                   │
    │                                                       │
    │  ToolPerfTracker ──hints──→ system prompt             │
    │  EvolutionEngine ──lessons──→ system prompt           │
    │  TeamRuntime.describe ──team ctx──→ system prompt     │
    │                                                       │
    │  HookEngine (PRE/POST_LLM_CALL, TRANSFORM_OUTPUT)     │
    │                                                       │
    │        ┌────────── while budget ──────────┐           │
    │        │                                   │           │
    │        ▼                                   │           │
    │    ModelClient.chatCompletion()            │           │
    │        │                                   │           │
    │        ▼                                   │           │
    │    tool calls? ──── yes ──▶ executeToolCall()          │
    │        │                     │ (role check → approval │
    │        │                     │  → dispatcher → sandbox│
    │        │                     │  → record success/fail)│
    │        │                     └───▶ append tool result │
    │        no                                  │           │
    │        │                                   │           │
    │        ▼                                   │           │
    │    final answer ◀──────────────────────────┘           │
    │                                                       │
    │  TrajectoryCollector (jsonl.gz)                       │
    │  CognitiveTraceCollector (OODA)                       │
    │  AgentEvalMetrics                                     │
    │                                                       │
    │  nudge 触发 → spawnBackgroundReview (virtual thread)   │
    │    └── SubAgent (fork) → pendingReviewSummaries       │
    └───────────────────────────┬───────────────────────────┘
                                │
                                ▼
                          response text
                 （stream 模式是逐 token Consumer<String>）
```

## 14. 阶段 1 识别到的债/风险

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🔴 | 主循环无 token 计数/超限保护 | `doProcessMessage` | 只靠 max_turns=90，长会话必爆 context window |
| 🔴 | 流式路径（doProcessMessageStream）没有审批 checkpoint 支持 | 949–1086 行 | 撞审批直接 RuntimeException 爆出，用户体验挂 |
| 🟠 | transports 抽象层完全未接入主路径 | `agent/transports/*`，所有 new ModelClient 处 | README 声称支持 Anthropic/Bedrock/Codex，实际只跑 OpenAI 兼容协议 |
| 🟠 | persistSession 每次 new SessionManager，只存 role+content | `persistSession()` | SQLite 连接抖动、tool_call 关联丢失、无去重逻辑 |
| 🟡 | ContextCompressor 未被主循环调用 | 全代码仅 TrajectoryCollector 用 | 死代码，config 里的 compression 配置无消费者 |
| 🟡 | Mem0/LocalFile ExternalMemoryProvider 没被 MemoryManager 接入 | `memory/*Provider.java` | 外部记忆接口定义了但没接线 |
| 🟡 | createDefault() 路径偷偷 new TenantManager() | `ensureTenantManager()` | 与主 HermesAgentV2 里的 TenantManager 可能分裂 |
| 🟡 | agentPool 是 ConcurrentHashMap 但 agent 实例内部没加锁 | `ChatService.agentPool` | HTTP 线程并发 resumeToolApproval/chat 会数据竞争 |
| 🟢 | SubAgent 也走 ToolRegistry 全局（非 sandbox 化） | `SubAgent.java:toolRegistry = ToolRegistry.getInstance()` | fork 的 review 子 agent 实际拿到全局工具集，toolWhitelist 是过滤而不是隔离 |
| 🟢 | ReflectionEngine 高置信度 lessons 以 "[LESSON] " 标签注入 MEMORY.md | `reflect()` 行 84–87 | 可能越积越长，没有去重或淘汰机制 |

## 15. 阶段 1 小结

**Runtime 成熟度：Beta-（75%）**

跑通了核心 ReAct loop + tool 审批 resume + 多租户隔离 + 长期记忆（BM25 注入）+ 背景学习 + 团队协作 hook。但：
- 缺少生产必备的 token 预算保护
- 流式路径审批没闭环
- 多模型支持是空壳
- 会话持久化粗粒度
- 上下文压缩死代码

这些问题都不是架构级的——主骨架设计是对的（TenantContext 贯穿、checkpoint 设计清晰、HookEngine 留了扩展点、OODA 认知轨迹有想法），差的是"把已经写好的组件接上线"和"补生产级健壮性"。

### 下一步
→ **阶段 2：多租户与安全边界** —— 深盘 `tenant/` 1.5w 行（13 个子系统：core/sandbox/quota/audit/metrics/security/gpu/autoscaler/container/lifecycle/persistence/session/tools）+ `auth/` + `policy/` + `approval/`，验证上次盘点说的"defense-in-depth 教科书实现"到底落地了几成，以及多实例 7 个一致性问题具体卡在哪。
