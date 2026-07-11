# 阶段 3：工具与执行沙箱

> 分析时间：2026-07-11
> 分析范围：`tools/` 2262+6776+400=9 438 行 + `browser/` 1700 行 + `terminal/` 658 行
> HEAD：`e7c2b5b`

---

## 1. 总览

工具层是 agent 与"世界"交互的唯一通道。它的安全边界比 agent loop 本身更重要——agent 只是在思考，工具才是在行动。

**代码量**：约 **11 800 行**。

核心结论：**架构意图是对的（8 关卡流水线），但落地覆盖率只有 19/66 ≈ 29%**。47 个注册工具绕过沙箱直接执行 handler，其中至少包含 GitTool（8 个 git 子命令）这样直接 ProcessBuilder 且无路径校验的高风险工具。

## 2. TenantAwareToolDispatcher（1 094 行）— 8 关卡流水线

`dispatch(toolName, args)` 按顺序执行：

```
dispatch(toolName, args)
│
├─ 关卡 0：pre_tool_call hook（HookEngine.checkToolBlocked）
│         插件可一票否决（返回 blocked reason）
│
├─ 关卡 1：TenantToolRegistry.checkPermission()
│         · deniedTools 黑名单
│         · allowedTools 白名单
│         · 工具调用配额
│         · 参数校验（paramCheck）
│
├─ 关卡 2：ToolCallPrelude.analyze()
│         · 生成 intent 解释（"I'm reading X to understand Y"）
│         · dry-run 推荐（高风险工具先 dry-run）
│         · 预览副作用
│         · warnings（敏感数据、破坏性操作等）
│         · 可 reject（返回 graceful reject）
│
├─ 关卡 3：ApprovalSystem.requestApproval()
│         · 按 ToolEntry.requiresApproval+risk+approvalType 决定
│         · 8 条 hardcoded DangerPattern（mkfs/>/dev/sda 等 DENY）
│         · shell 续行/转义/IFS 归一化防绕过
│         · 30 分钟 session approval 缓存
│         · 外部审批（ApprovalMessageHandler）或控制台 prompt
│
├─ 关卡 4：Negotiator.autoNegotiate()
│         · MEDIUM+HIGH 风险工具触发 AI 自动协商
│         · confidence 不够 → needsHuman() 升级人工
│
├─ 关卡 5：实际执行（switch/case，见下）
│
├─ 关卡 6：post_tool_call hook（埋点/通知）
│
├─ 关卡 7：transform_tool_result hook（脱敏/改写）
│
└─ 关卡 8：TenantToolRegistry.recordToolCall()（统计+审计）
```

**关卡 0–4 和 6–8 对所有工具生效**（包括走 generic 的裸 handler），因为它们在 switch 之前/之后。**只有关卡 5 的沙箱执行路径是选择性的**。

## 3. 关卡 5 实际执行——双轨制

Dispatcher 用 switch/case 把工具分两类：

### 3.1 走沙箱路径（19 个工具，真正被 Dispatcher 拦截）

| 分支 | 工具名 | 沙箱 |
|---|---|---|
| `dispatchFileTool` | read_file, write_file, list_directory, search_files, file_read, file_write, file_list | ✅ TenantFileSandbox.validatePath()（11 步校验）+ Files.readString/writeString |
| `dispatchCodeTool` | execute_python, execute_javascript, execute_bash | ✅ tenantContext.exec()（cgroup/process sandbox）+ 512MB 内存限 + timeout |
| `dispatchTerminalTool` | terminal, execute_command | ✅ validateTenantPath(cwd) + tenantContext.exec()（同上），timeout 300s |
| `dispatchMemoryTool` | memory_read, memory_write, memory_search | ✅ tenantContext.getMemoryManager() |
| `dispatchOrgTool` | find_teammate, delegate_task, query_org_knowledge, escalate_to_human, team_post, team_read, team_status, orchestrate_intent, intent_status, org_traces, org_anomalies, browser_bridge | ✅ 全部经 TenantContext 懒加载的协作组件；browser_bridge 额外走 BrowserBridgePolicy + BrowserApprovalQueue + auditLogger |

这 19 个工具在 Dispatcher 里**完全重新实现**了 handler 逻辑——`impl/` 里对应类的 handler **永远不会被调用**（成了死代码）。

### 3.2 走 genericTool 裸 handler（47 个工具）

```
default -> entry.getHandler().apply(args)
```

直接调用 ToolInitializerV2 注册的 lambda/方法引用，**Dispatcher 不做任何沙箱隔离**：
- ❌ 不调用 TenantFileSandbox
- ❌ 不调用 tenantContext.exec()（无 cgroup/memory 限）
- ❌ 不调用 RestrictedHttpClient（绕过网络沙箱）
- ❌ 不调 auditLogger（除了 hookEngine 的 post_tool_call 外没审计）

这些工具分几类风险：

#### 🔴 高风险（直接起进程/写文件，且无沙箱）
| 工具 | 风险 | 问题 |
|---|---|---|
| `git_clone/push/pull/commit/add/...`（8 个） | 直接 `new ProcessBuilder(cmd)`、`pb.directory(new File(cwd))` | cwd 任意绝对路径、命令参数未转义、无网络白名单、risk=LOW/无审批 |
| `grep_files` | Files.walkFileTree + FileTool.isPathAllowed 黑名单只有 4 个路径 | `/proc/self/environ`、`~/.aws/credentials`、其他租户目录都能 grep |
| **（关键）execute_command 的 TerminalTool handler 本身** | new ProcessBuilder + /bin/bash -c | **但** Dispatcher 拦截了 execute_command 走沙箱，所以 handler 死代码；但下面这些没被拦截... |

Wait——`execute_command` 已经被 switch 拦截了，所以它的裸 handler 不会被跑到。那真正跑裸 ProcessBuilder 的是 **GitTool 的 8 个 git_*** 命令。

#### 🟠 中风险（直接发 HTTP，绕过 RestrictedHttpClient）
| 工具 | HTTP 客户端 | 绕过 |
|---|---|---|
| web_search, web_extract | OkHttpClient（静态实例） | 速率限制、host 白名单、DNS rebinding 防护全绕过 |
| feishu_doc_read, feishu_drive_*（6 个，若注册了） | OkHttpClient | 同上，且**不在 V2 注册里**（死代码） |
| tts_speak, image_generate/edit | OkHttpClient | 对外发请求，绕过审计 |
| mcp_call, mcp_add_server | 自己的 HttpClient | MCP server 地址任意，SSRF |
| browser_open/click/type/...（12 个 BrowserToolV2 除 browser_bridge） | OkHttpClient → 控制浏览器 | 走 CDP 或 HTTP，但**不经过 BrowserBridgePolicy/audit** |

注意：`browser_bridge`（org tool）走的是沙箱路径（关卡全过 + BrowserBridgePolicy + 审计），但 BrowserToolV2 注册的 `browser_open/browser_click/...` 这 12 个细粒度动作走 genericTool → 它们直接用 OkHttpClient 打浏览器 daemon，**绕过 BrowserBridgePolicy 审批**。

#### 🟡 低风险（纯内存/已自带权限）
- vision_analyze, tts_voices, ha_state/turn_on/set_temperature 等 IoT/辅助工具
- cronjob_*（内存 cron）、rl_*（训练工具，看实现）、subagent_*（spawn 子 agent）、blackboard_*（未在 V2 注册）、skill_*/memory_get/save/replace/delete（未在 V2 注册）

## 4. 各工具快速评估

### 4.1 FileTool（424 行）— 被 Dispatcher 90% 覆盖，grepFiles 裸跑
- 注册：read_file, write_file, search_files, **grep_files**
- 自己的安全：`isPathAllowed()` 只拦 4 个路径（/etc/shadow, /etc/passwd, .ssh/id_rsa, .ssh/id_ed25519），子串 contains
- Dispatcher 覆盖前 3 个，**grep_files 裸跑**——可用 grep_files 读 /proc/self/environ、~/.hermes/config.yaml、其他租户数据
- 另外还有 list_directory 工具，但 Dispatcher 的 case 也覆盖了 file_list/list_directory 两个别名——list_directory 的老 handler 死代码

### 4.2 TerminalTool（213 行）— 完全被 Dispatcher 覆盖
- 只注册 execute_command 一个
- 自己实现：ProcessBuilder + /bin/bash -c + checkSafety（黑名单检查）
- Dispatcher 的 dispatchTerminalTool 拦截它走 tenantContext.exec()——**老 handler 完全死代码**，但代码还在维护（checkSafety 包含 normalizeCommandForDetection 反绕过逻辑，永远用不到）

### 4.3 CodeTool（176 行）— 完全被 Dispatcher 覆盖
- 注册 execute_python, execute_javascript, execute_bash
- 自己实现：代码写到临时文件 + ProcessBuilder
- Dispatcher 的 dispatchCodeTool 拦截走 cgroup sandbox——老 handler 死代码

### 4.4 GitTool（217 行）— 🔴 **完全裸跑**
- 注册：git_status, git_add, git_commit, git_push, git_pull, git_log, git_branch, **git_clone**
- 全部走 `runGit(cwd, args...)` → `new ProcessBuilder("git", ...args)`
- cwd 来自 LLM 参数 `new File(cwd)`，没有 validatePath、没有 sandboxRoot 检查
- git_clone 的 url 参数完全自由——LLM 能 clone 任意外部 repo、clone 到任意路径、通过 `-c core.sshCommand=...` 注入 shell
- risk 标记 LOW，**requiresApproval=false**——不触发关卡 3 审批
- 没有 cgroup 内存/CPU 限制

### 4.5 BrowserToolV2（738 行）— 12 个动作裸跑，只有 browser_bridge 走沙箱
- 注册：browser_open/click/type/scroll/back/press/get_content/close/screenshot/navigate/snapshot/cdp_connect/cdp_status（共 13 个）
- 12 个细粒度动作直接 OkHttpClient 打浏览器 daemon（HTTP API），**没走 BrowserBridgePolicy 审批、没调 auditLogger**
- browser_bridge（org tool 版本）走沙箱路径
- BrowserToolV2 用的是 OkHttpClient 静态实例，走的是自己的浏览器控制协议（跟 browser.BrowserBridge 是两套平行实现）

### 4.6 WebSearchTool/V2（213+204 行）— 裸跑 HTTP
- web_search, web_extract
- 静态 OkHttpClient（不走 RestrictedHttpClient）
- 有自己的 API key 管理，但不经过 NetworkSandbox

### 4.7 MCPTool（166 行）— 🟠 裸跑
- mcp_list_servers, mcp_add_server, mcp_list_tools, mcp_call
- mcp_add_server 允许 LLM 注册任意 MCP server URL
- 后续 mcp_call 直接向该 server 发 JSON-RPC——**SSRF 风险**（可打内网 metadata）

### 4.8 其他裸跑工具（低风险或内存内操作）
- CronjobTool/SubAgentTool/HomeAssistantTool/ImageGen/TTSTool/VisionTool/RLTrainingTool 都是内存或对外 HTTP，但不直接起进程/写任意文件
- OrgNativeTools 的 12 个 org 工具**全部被 switch 拦截**走沙箱（唯一 browser_bridge 在 dispatchOrgTool 里）

## 5. 死代码 / 幽灵实现盘点

这是阶段 3 最大的发现——项目里有**三套半平行的工具框架**同时存在：

| 框架 | 行数 | 实际使用 |
|---|---|---|
| (A) Dispatcher 内联沙箱实现（dispatchFileTool/dispatchCodeTool/...） | ~700 行 | ✅ 真正生效 |
| (B) tools/impl/*Tool.java 老的独立 handler 类 | ~5 000 行 | 部分死代码（19 个被 Dispatcher shadow），部分裸跑（47 个生效） |
| (C) tenant/tools/TenantAwareCodeTool + TenantAwareSkillTool | 883 行 | ❌ **完全没被任何地方 new/调用** |
| (D) terminal/ 包（TerminalEnvironment + Local/SSH/Docker） | 658 行 | ❌ **完全没被任何地方调用**——Dispatcher 直接走 ProcessSandbox，没接这个抽象 |
| (E) tools/impl/PathSecurity（validateWithinDir 工具类） | 190 行 | ❌ 没有 impl 工具调用它（FileTool 用自己的 isPathAllowed 黑名单） |
| (F) tools/impl/MemoryTool, SkillTool, BlackboardTool, FeishuDoc/DriveTool（在老 ToolInitializer 注册） | ~2 500 行 | 🟡 老 ToolInitializer 没被调用；但 Dispatcher 自己实现了 memory_read/write/search，skill/blackboard/feishu **完全没注册** |

### 5.1 老 impl 工具的死代码名单（被 Dispatcher shadow）
19 个工具的老 handler 永远不会跑：
- read_file, write_file, search_files（FileTool）——注意 grep_files/list_directory 没被 shadow，list_directory 实际在 case "list_directory","file_list" 里都覆盖了
- execute_python, execute_javascript, execute_bash（CodeTool）
- execute_command（TerminalTool）
- memory_search（OrgNative/Dispatcher 版）
- delegate_task, escalate_to_human, find_teammate, team_post/team_read/team_status, orchestrate_intent, intent_status, org_traces, org_anomalies, query_org_knowledge, browser_bridge（OrgNativeTools）

FileTool 自己还注册了 file_read/file_write/file_list 别名，这些别名也在 switch 里被覆盖。

## 6. Browser 子系统（1 700 行）— 独立抽象

BrowserBridge 是 provider-neutral 接口，BrowserBridgeFactory 按 `BROWSER_PROVIDER` 环境变量选实现：

| provider | 实现 | 说明 |
|---|---|---|
| mock/test/空 | MockBrowserBridge | 假实现，返回空结果 |
| webbridge/kimi-* | KimiOfficialWebBridgeAdapter | Kimi WebBridge 官方协议 |
| kimi-contract | KimiWebBridgeAdapter + contract 校验 |
| openclaw/relay | OpenClawRelayBrowserBridge | OpenClaw Browser Relay |
| 其他 | UnavailableBrowserBridge | 报错 |

配套：
- BrowserBridgePolicy（76 行）：URL 白名单、禁止访问内网/localhost、敏感动作需 confirm
- BrowserApprovalQueue（134 行）+ BrowserApprovalRequest（109 行）：浏览器动作审批队列
- BrowserActionResult/BrowserAction：标准化动作模型
- contract/ 目录：Browser bridge provider 合同测试/探针

⚠️ **但这个抽象只被 Dispatcher 的 browser_bridge org tool 用**——BrowserToolV2 的 12 个细粒度工具（browser_open 等）直接走 OkHttpClient 打 HTTP，绕过了这套 Policy/审批队列/审计。存在两套 browser 通道。

## 7. ToolCallPrelude（249 行）— 可解释性层

- `analyze(toolName, args, ctx, toolEntries)` 返回 Result：allowed/explanation/dryRun/preview/warnings/rejected/rejectReason
- dryRunRecommendedTools 集合：write_file/execute_bash/git_push/cronjob_add 等
- 敏感数据启发式：参数里出现 path/id/amount 等关键词就加 warning
- 破坏性操作检测：write(append=false)、rm、drop table 等
- 记录到 AgentTrace（可观测性）
- **如果 rejected，返回 gracefulReject 给 LLM，建议委派或升人工**

这层是"AI 原生"的自我反思——工具执行前先让 LLM 或启发式判断要不要 dry-run，设计是先进的。

## 8. ToolPerformanceTracker（232 行）

- 记录每个工具的：调用次数、成功率、平均延迟、p95、最近错误
- `buildHintBlock()` 生成提示注入 system prompt（"tool X fails 30% of the time, consider alternative"）
- 数据进程内，重启丢失

## 9. 阶段 3 识别到的债/风险

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🔴 | **GitTool 8 个命令全部裸跑** | GitTool.runGit() | cwd 任意、无沙箱、无审批、可 clone 任意外部 URL，LLM 可写任意路径 |
| 🔴 | **BrowserToolV2 12 个细粒度动作绕过 BrowserBridgePolicy** | BrowserToolV2（除 browser_bridge） | 不审批、不审计、直连 OkHttpClient 打浏览器 |
| 🔴 | **grep_files 裸跑且只拦 4 个黑名单路径** | FileTool.grepFiles() | 可读 /proc/self/environ、其他租户目录、配置文件 |
| 🟠 | MCPTool.mcp_add_server 允许任意 URL → SSRF | MCPTool | 可打内网/metadata，配合 mcp_call 变成任意 HTTP 代理 |
| 🟠 | WebSearch/TTs/ImageGen 用静态 OkHttpClient 绕过 RestrictedHttpClient | impl/*Tool | 速率限制/host 白名单/DNS rebinding 全绕过 |
| 🟠 | tenant/tools/TenantAwareCodeTool + TenantAwareSkillTool 883 行死代码 | tenant/tools/ | 从未被构造调用，是另一套"租户安全工具"的废弃分支 |
| 🟠 | terminal/ 包 658 行死代码 | terminal/Local,SSH,Docker | TerminalEnvironment 抽象层从未被用，Dispatcher 直接走 ProcessSandbox |
| 🟠 | tools/impl/PathSecurity 190 行死代码 | PathSecurity | validateWithinDir 写了但没有 impl 工具调用 |
| 🟡 | 三套半平行工具框架并存（Dispatcher 内联 / impl 老 handler / TenantAware*Tool / terminal 抽象） | 整个 tools/ | 维护负担重，改一个工具得改两处；新开发者不知道哪个才是生效路径 |
| 🟡 | 19 个工具的老 handler 是死代码但还在维护 | FileTool/TerminalTool/CodeTool/OrgNativeTools 的 handler | 代码重复、有 bug 要改两份、未来重构风险 |
| 🟡 | MemoryTool/SkillTool/BlackboardTool/FeishuDoc/DriveTool 没注册到 V2 | ToolInitializer(老) | ToolInitializerV2 没调用这些；Dispatcher 自己实现了 memory_* 但 skill_*/blackboard_*/feishu_* 完全不可用 |
| 🟡 | browser 通道双轨（browser_bridge org tool 走 Policy + BrowserToolV2 12 动作裸跑） | browser/ + impl/BrowserToolV2 | 用户/LLM 可以绕过浏览器审批，挑容易的路径用 |
| 🟢 | execute_command/generic 分支的 stdout 有 truncate，但 switch dispatchTerminalTool 没 truncate | dispatchTerminalTool | 可能返回超大 output 撑爆 context window |
| 🟢 | FileTool.isPathAllowed 用 contains() 子串匹配 | FileTool | ".ssh/id_rsa" 会被 ".ssh/id_rsa_backup" 误拦，也能被 "/tmp/.ssh/id_rsa" 绕开（虽然 Dispatcher 版本的 read/write 不会走到这） |

## 10. 阶段 3 小结

**工具层成熟度：Beta-（70%）核心沙箱扎实，覆盖率有大洞**

设计上 8 关卡（hook/permission/prelude/approval/negotiation/execution/post-hook/transform）是企业级水准，关卡 0-4 和 6-8 对所有工具生效（这是好事）。但**关卡 5 的沙箱执行只覆盖 19/66 个工具（29%）**，留下 GitTool 这样 8 个命令完全裸跑的大洞。

### 亮点
- 8 关卡流水线设计清晰，hook/transform/approval/negotiation 都是企业级特性
- normalizeCommandForDetection 防 shell 绕过（行续/转义/IFS）是真实 CVE 补丁
- ToolCallPrelude 的 dry-run+explain+reject 让工具调用有可解释性
- BrowserBridge 抽象支持多 provider（Kimi/OpenClaw Relay/Mock），接口小而美
- 走沙箱路径的 19 个工具（尤其是 file 和 code）校验严格

### 必须修的安全漏洞（上线前）
1. **GitTool 接进沙箱**（最简单：把 git_* 加进 Dispatcher switch，或改 GitTool 用 tenantContext.exec() + validateTenantPath）
2. **grep_files 加进 dispatchFileTool**（实现沙箱版本）
3. **BrowserToolV2 12 个动作要么删、要么接 BrowserBridgePolicy**
4. **MCPTool add_server 走 SSRF 过滤**（内网/metadata 黑名单）
5. 把所有 ProcessBuilder/OkHttpClient 静态实例改成走 tenantContext 的 sandbox

### 架构债清理建议（中优先级）
6. 删除 tenant/tools/TenantAware*Tool（883 行死代码）
7. 删除 terminal/ 包或真正接入 ProcessSandbox
8. 删除 PathSecurity 或统一让所有 impl 工具用它
9. 把 impl 里被 shadow 的 19 个老 handler 删掉（或让它们 delegate 到 Dispatcher 版本）——消除双写
10. 把 MemoryTool/SkillTool/BlackboardTool/FeishuTool 接进 ToolInitializerV2 或从代码库删除

### 下一步
→ **阶段 4：业务层** —— `business/`（8.4k 行）、`workspace/`、`scenario/`、`blueprint/`、`org/`、`metering/`，理清 BusinessRunService、BusinessApprovalService、EventBus、ScenarioOrchestrator、TeamBlueprint、Workspace 之间的调用关系，以及它们到底怎么持久化（上次推测是 JSON 文件，要验证）。
