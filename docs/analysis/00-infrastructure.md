# 阶段 0：基础设施与入口

> 分析时间：2026-07-11
> 分析范围：构建、启动、配置、HTTP 服务层、静态资源、部署形态
> HEAD：`e7c2b5b`

---

## 1. 项目身份

| 项 | 值 |
|---|---|
| GAV | `com.nousresearch:hermes-agent-java:0.1.0-SNAPSHOT` |
| 定位 | 生产级多租户 AI Agent 平台（Java 版 Hermes） |
| Java 版本 | **21**（强依赖，maven-compiler 锁 source/target=21） |
| 打包 | Fat JAR（maven-shade-plugin），主类 `com.nousresearch.hermes.HermesAgentV2` |
| 主 README | 声称 "self-improving AI agent with tool calling and multi-tenant isolation" |
| 代码量 | **后端 506 Java 文件 / 95 927 行**；测试 **149 文件 / 21 686 行** |
| 测试框架 | JUnit 5.10 + Mockito 5.11；surefire 默认把 `hermes.home` 指到 `target/test-hermes-home` 防污染 |

## 2. 核心依赖

| 类别 | 依赖 | 版本 | 用途 |
|---|---|---|---|
| HTTP Server | **Javalin** | 6.1.3 | 全部 HTTP/WebSocket/SSE 基于 Javalin（轻量 Jetty 包装） |
| HTTP Client | OkHttp | 4.12 | LLM 调用、出站请求；okhttp-sse 仅 test scope |
| JSON | fastjson2 | 2.0.43 | 主要业务 JSON；Jackson 2.17 用于 YAML 与部分 databind |
| YAML | SnakeYAML + jackson-dataformat-yaml | 2.2 / 2.17 | `config.yaml` 加载 |
| 日志 | SLF4J 2.0 + Logback 1.5 | — | 全链路日志门面 |
| 响应式 | Reactor Core | 3.6.5 | 异步流（SSE 流式输出等） |
| 浏览器自动化 | Playwright for Java | 1.43 | browser_bridge 后端 |
| 容器/SSH | docker-java 3.3 + JSch 0.1.55 | — | 终端沙箱双后端 |
| 邮件 | javax.mail (com.sun.mail) | 1.6.2 | 告警邮件 |
| 云 LLM | AWS SDK BedrockRuntime | 2.25 | Bedrock 原生通道 |
| 数据库 | **sqlite-jdbc 3.45** | — | 会话持久化（**唯一的持久化依赖，没上 PostgreSQL/Redis**） |
| CLI | Picocli | 4.7.5 | CLI 命令（见 `DashboardRunner`、`AcpEntry`） |
| CDP 客户端 | Java-WebSocket | 1.5.6 | Chrome DevTools Protocol 直连 |
| HTML 解析 | Jsoup | 1.17.2 | web 内容提取 |
| Validation | Jakarta Validation API | 3.0.2 | 注解校验（仅 API，没有 runtime 实现，**潜在 classpath 缺口**） |
| 测试 | JUnit 5 + Mockito 5 | — | 单元测试 |

**观察**：
- 🔴 **没有 Redis**：上次盘点提到的多实例一致性问题里，配额/速率/审批锁的 Redis 需求在依赖层面就没落地。
- 🔴 **没有 Flyway/Liquibase**：SQLite 直接连，没看到 schema migration 机制。
- 🟡 JSON 双库（fastjson2 + Jackson）并存是历史债——DashboardServer 主要用 fastjson2，HermesConfig 用 Jackson，没有统一。
- 🟡 唯一的"数据库"是 SQLite 嵌入式，意味着**单机持久化**，分布式场景下需替换。

## 3. 启动入口

### 3.1 4 个 main 类

| 类 | 用途 |
|---|---|
| `HermesAgentV2` | **主入口**：同时启动 Gateway（平台适配器收消息）+ Dashboard（Web/Portal/Ops/Noc/Jarvis），默认 tenant mode |
| `dashboard.DashboardRunner` | 独立启动 Dashboard（picocli 子命令 `dashboard`）—— Dockerfile 的 CMD 就是这个 |
| `acp.AcpEntry` | 独立启动 ACP（Agent Communication Protocol）服务器 |
| `browser.contract.BrowserBridgeContractCli` | Browser Bridge 合同测试 CLI |

⚠️ **Dockerfile 的 CMD 实际跑的是 `dashboard` 子命令**（只启动 Dashboard），不是 HermesAgentV2 全栈。要开 Gateway 收 IM 消息需要自己加启动参数或换入口。这是一个部署陷阱。

### 3.2 HermesAgentV2 启动顺序

```
new HermesAgentV2(tenantMode=true)
│
├─ ConfigManager.getInstance().load()       ← 读 ~/.hermes/config.yaml
├─ new ApprovalSystem()                     ← 工具级审批内核
├─ ToolRegistry.getInstance()
├─ ToolInitializerV2.initializeAll(...)     ← 注册所有内置工具
├─ new SessionManager(~/.hermes)            ← SQLite 会话持久化
├─ new TenantManager() + initializeDefaultTenant()
├─ new PluginManager(pluginConfig) + discoverAndLoad()  ← 扫 plugins/ 目录
├─ registerBuiltinProviders()               ← 硬编码 4 类 builtin provider：
│                                            · web_search: Brave/Tavily/Exa/Firecrawl
│                                            · image_gen: OpenAI/Stability
│                                            · tts: OpenAI/ElevenLabs
│                                            · model_transport: OpenAI/Anthropic/Bedrock/Codex
├─ /learn、/curator、/journey 三个 slash command 注册到 pluginManager
├─ new GatewayServerV2(port=8080, agentConfig)   ← tenant mode 走 V2
├─ registerAdaptersV2()                     ← 把 plugin 中 checkFn 通过的 PlatformAdapter 挂到 gateway
│
start()
├─ gatewayServerV2.start()                  ← Javalin 起 8080
├─ startDashboard()                         ← new DashboardServer(9119, 127.0.0.1, ...) + start()
│                                           ⚠️ Dashboard 端口=9119，host=127.0.0.1（仅本地）
│                                           且 setSessionToken() 反向注入给 gateway，实现 token 互通
└─ Runtime.addShutdownHook                  ← 优雅停：dashboard→gateway→session.persist→tenant.shutdown

main(args)
└─ 如果没有 FEISHU/TELEGRAM/DISCORD token → runInteractive()（CLI 交互）
   否则 Thread.join() 等平台 webhook
```

### 3.3 双 HTTP 服务器

这是个重要架构事实：**Gateway 和 Dashboard 是两个独立 Javalin 实例**。

| 端口 | 服务器 | 职责 |
|---|---|---|
| **8080** (可配 `gateway.port`) | `GatewayServerV2` | 平台 webhook、`/api/chat`（OpenAI 兼容）、`/v1/chat/completions`、tenant CRUD、sessions CRUD、compare runs |
| **9119** (可配 `HERMES_DASHBOARD_PORT`) | `DashboardServer` | Web UI + 所有 Business/Portal/Ops/Noc/Jarvis/Org API + 静态资源 |

⚠️ **Dockerfile EXPOSE 8080 但 CMD 启动的是 `dashboard --port 8080`**（见下），所以容器里其实是 Dashboard 占 8080，Gateway 没起。这进一步证实部署入口走的是 Dashboard-only 模式。

两者通过**内存里互相引用**交互（Dashboard 通过构造器拿到 TenantManager/Business*Service；GatewayServerV2 被 setSessionToken() 注入 token），**没有进程间通信**——这意味着必须同 JVM 才能协作。

## 4. 配置体系

### 4.1 配置文件位置

`~/.hermes/config.yaml`（可通过 `hermes.home` / `HERMES_HOME` / `HERMES_HOME` 系统属性/环境变量切换；测试默认 `target/test-hermes-home`）。

密钥单独存 `~/.hermes/.env`（`HermesConfig.setSecret()` 写入）。

### 4.2 ⚠️ 两个并存的配置类——重要债点

| 类 | 行数 | 用途 |
|---|---|---|
| `ConfigManager` | ~520 | **主配置**：带 `${ENV_VAR}` 展开、env bridge（terminal/agent/display 映射到系统 env）、Jackson ObjectNode 树、type-safe getter/setter、默认值非常全（含 memory/skills/compression/auxiliary/terminal 等 10+ 个 section） |
| `HermesConfig` | ~470 | **另一套**：自己维护 `Map<String,Object>`、自己的 load/save、自己的默认值（只有 model/agent/tools/display 4 个 section）、另外实现了 `channel-overrides` / `model_routes` S1 系列功能、带 `ModelConfig` 内部类给 ModelClient 用 |

两者**同时被使用**：
- `HermesAgentV2` 构造器里两个都 new：`this.config = ConfigManager.getInstance()`，但随后又 `HermesConfig agentConfig = new HermesConfig(config.getApiKey(), config.getBaseUrl(), config.getModelName())`。
- `DashboardServer` 构造器只接收 `HermesConfig`。
- `ConfigManager.getApiKey()` 回退到 `System.getenv("OPENROUTER_API_KEY")`，`HermesConfig.getApiKey()` 也回退到同一个 env 变量——两套回退逻辑独立维护。
- Model route/channel override 功能（S1-1/S1-3）在 `HermesConfig` 里，`ConfigManager` 没有。

🟡 **债**：两套配置系统并存会导致"改了一处另一处不知道"，是未来 bug 来源。应该合并成一个。

### 4.3 环境变量覆盖点

`ConfigManager` 展开 `${VAR}` 引用；`HermesConfig.applyEnvOverrides()` 识别：
- `OPENROUTER_API_KEY`
- `HERMES_MODEL` / `HERMES_PROVIDER` / `HERMES_BASE_URL`

还有 4 层模型解析优先级（session /model → channel override → tenant default → global default）——但 channel override 只在 `HermesConfig.resolveModel()` 里实现，gateway 消息入口是否走到这条路径待阶段 1/5 验证。

## 5. DashboardServer — 9119/8080 主 Web 服务

**1364 行**，整个项目的 HTTP 中枢。

### 5.1 中间件

1. **Host 头校验**：仅允许 localhost/127.0.0.1/::1 和绑定 host（DNS rebinding 防护）。
2. **Bearer Token 鉴权**：
   - 启动时 `SecureRandom` 生成 32 字节 URL-safe token，存在 final 字段 `sessionToken`。
   - `/api/*` 除白名单外全部要 `Authorization: Bearer <token>`。
   - 白名单（公开）：`/api/status`、`/api/config/defaults`、`/api/config/schema`、`/api/model/info`、`/api/dashboard/themes`、`/api/dashboard/plugins`、`/api/dashboard/plugins/rescan`。
   - SSE 端点（`/api/logs/tail`、`/api/jarvis/stream`、cron runs stream）允许 `?token=` query 参数（EventSource 不能设 header）。
   - 比较用 `HmacSHA256` 做 constant-time compare（正确做法）。
3. **⚠️ Token 不是持久化的**：每次启动重新生成。前端通过 index.html 注入 `<script>window.__HERMES_SESSION_TOKEN__="..."</script>` 拿到。**容器重启后所有老 tab 必须刷新**。
4. **CORS**：只允许 `http://localhost:{port}` 和 `http://127.0.0.1:{port}`。

🔴 P0 安全债（跟阶段 0 相关）：
- `?token=` 走 query string → 会进 access log / 浏览器历史 / 反向代理日志。SSE token 应该用短期签名 token 或走 cookie。
- token 启动时生成、无法配置，意味着多副本部署时每个实例 token 不同，前端无法共享会话。
- `publicApiPaths` 里的 `/api/dashboard/plugins/rescan` 能触发插件重扫，无鉴权——需要确认。

### 5.2 构造器里直接 new 了所有业务 Service

这是 DashboardServer 最"上帝类"的地方——构造器里**硬编码**实例化几乎所有业务组件（这就是为什么它 1364 行还嫌不够）：

```java
// 业务核心
new WorkspaceService(tenantManager);
new TeamBlueprintService(workspaceService);
new ScenarioService(workspaceService, teamBlueprintService);
new BusinessApprovalService(workspaceService);
new BusinessRunService(workspaceService, teamBlueprintService, scenarioService);
new BusinessInsightService(workspaceService, teamBlueprintService, businessRunService, businessApprovalService);
new ToolApprovalCoordinator(workspaceService, businessApprovalService);
new BusinessEventBus();                                      // ← 进程内事件总线
new SLAManager(businessRunService, businessApprovalService);
new DeadLetterQueue();
new ApprovalAnalytics(businessApprovalService);
new HumanOverrideService(workspaceService, businessRunService);
new BusinessWorkflowService(new WorkflowEngine(...), scenarioService, workspaceService, ...);
new ConnectorRegistry();
new EcommerceScenarioFactory(...);
new EvalSetService(workspaceService, scenarioService);
new CanaryReleaseService(workspaceService, teamBlueprintService);
new QuickTeamBuilderService(modelClient);
new BusinessTemplateService();
new TemplateCloneService(...);
new BusinessMemoryNoteService(workspaceService);
new BusinessPortalFoundationFacade(...);

// Jarvis
new JarvisHandler(new ChatService(config, tenantManager, toolApprovalCoordinator, businessApprovalService),
                  new IntentRouter(modelClient, new ProductQueryService(modelClient, ...)),
                  new ApprovalBridge(businessApprovalService, toolApprovalCoordinator),
                  businessEventBus, businessApprovalService);

// AI-native org（一组直接 new 的组件）
new AgentIdentityManager(), new HandoffProtocol(), new PermissionPolicy(),
new OrganizationalKnowledgeBase(), new WorkflowEngine(...), new AgentMarketplace(),
new CostAttribution(), new AgentObservability(), new AgentRegistry(),
new SelfEvolutionEngine(), new ComplianceFramework()
```

🟡 **债**：
- **没有 DI 容器**，全是手工 `new`。加新 service 要改 DashboardServer 构造器。测试 mock 困难。
- `org.*` 11 个组件在构造器里全 new，但有几个看起来是空壳/占位（阶段 5/6 验）。
- `ScenarioService` 被反复调用 setter 循环注入（setPolicyService/setCanaryReleaseService/setPlanReflectionService/setBusinessMemoryNoteService）——典型的循环依赖/构造器未完成逃逸信号。

### 5.3 路由全景（按 prefix 分组）

| Prefix | 数量 | 类别 |
|---|---|---|
| `/health` | 1 | 健康检查 |
| `/api/config/*` | 7 | 配置查看/修改/env/reveal |
| `/api/model/info` | 1 | 模型信息 |
| `/api/providers/oauth/*` | 7 | OAuth 登录流程（第三方接入） |
| `/api/sessions/*` | 4 | 会话检索/删除/消息 |
| `/api/logs/*` | 5 + 1 SSE | 日志查看/聚合/实时 tail |
| `/api/skills` | 2 | 技能开关 |
| `/api/learning/*` | 7 | 学习图（LearningGraphService）节点 CRUD/pin |
| `/api/tools/*` | 3 | 工具集/工具详情 |
| `/api/gateway/*` | 3 | 重启 gateway、更新 hermes、action 状态 |
| `/api/analytics/usage` | 1 | 用量统计 |
| `/api/cron/*` | 9 + 1 SSE | 定时任务 CRUD/trigger/暂停/恢复/运行流 |
| `/api/v1/business/*` | 7 | Business foundation（模板/蓝图/场景/run/insight/evolution-proposal） |
| `/api/v1/business/events/stream` | 1 SSE | 业务事件流（老接口，新代码走 Jarvis SSE） |
| `/api/organization/*` | 3 | Org overview (老接口) |
| `/api/org/summary`、`/api/org/{module}` | ~25 | AI 原生 Org API（12 个模块：identity/handoff/auth/knowledge/workflow/market/cost/observe/distributed/evolution/compliance + control/...） |
| `/api/traces/{id}` | 1 | Trace 详情 |
| `/api/dashboard/themes`、`/theme`、`/plugins`、`/plugins/rescan` | 4 | 前端主题/插件 |
| `/api/jarvis/chat`、`/intent`、`/approval/{id}`、`/stream` | 4 | Jarvis 对话壳（chat/intent/approval/SSE） |
| `/portal`、`/ops`、`/noc` + 各自 `/*` | 6 | SPA fallback（serveSpaIndexHtml） |
| `/`、`/*` | 2 | 根 hub + catch-all fallback |
| `/assets/*`、`/fonts/*`、`/ds-assets/*`、`/favicon*`、`/manifest.webmanifest`、`/sw.js` | 9（每个 SPA 独立一套） | 静态资源 |

**路由风格观察**：
- 历史包袱重：同时存在 `/api/organization/*`（老）、`/api/org/*`（新）、`/api/v1/business/*`（Foundation 版）三套 Business API。`workspace/*` 路径在 WorkspaceDashboardIntegration 里（我没列全，因为它通过 `setUpRoutes` 模式被挂进来，待阶段 4 细看）。
- 大量 handler 直接是 `dashboard.handlers.XxxHandler` 实例，一部分是 lambda 直接写在 DashboardServer 内。

## 6. GatewayServerV2 — 8080 平台/OpenAI 兼容入口

**1431 行**，IM 网关 + OpenAI-compatible API 入口。

路由（`setupRoutes()`）：
- `/health`
- `/webhook/{platform}` — 平台 webhook 接收入口（Feishu/Telegram/Discord/QQ/Wecom）
- `/api/message` — 通用消息投递
- `/api/status`、`/api/tools`
- `/api/chat`、`/api/chat/stream` — **核心对话入口**
- `/v1/chat/completions`、`/v1/models` — OpenAI 兼容 API（走 `OpenAICompatHandler`）
- `/api/compare/runs` CRUD + stream + stop — 多模型对比（A/B 跑）
- `/api/tenants` CRUD + quota + usage + audit + config — **租户管理 REST**
- `/api/sessions` 全局 + per-tenant 两个维度的 CRUD
- `/api/config`、`/api/skills`

Middleware：
- `setupMiddleware()` 有 CORS（options 200）、`checkChatAuth`（针对 `/api/chat*`）、`extractTenantContext`（从 header/query/path 提取 tenant 挂到 ctx 上）
- ⚠️ 上次盘点提到的 P0 安全债：**`/api/jarvis/stream` 的 workspaceId 过滤是在 DashboardServer 的 JarvisHandler 里做的（commit 1a1f4f1），Gateway 侧的 tenant 提取在 `extractTenantContext` 中，但强鉴权逻辑没细看**——这个留到阶段 2（多租户与安全）验证。

Platform 适配器：
- Discord、Telegram、Feishu（两套 V1/V2）、Wecom、QQBot（见 `gateway/platforms/`）
- Feishu 和 QQBot 还有子目录，说明是按插件深度扩展的（不是单一 Java 文件）

## 7. 静态资源服务（前端产物）

### 7.1 web_dist 查找顺序
1. `HERMES_WEB_DIST` 环境变量（绝对路径）
2. CWD 候选：`hermes_cli/web_dist` → `web_dist` → `web/dist`
3. JAR 同级目录同名候选
4. 都找不到 → 打 warn，不注册静态路由

### 7.2 产物布局
```
web_dist/
├── index.html             ← 根 hub（3 个产品入口卡片）
├── favicon.ico / favicon.svg / manifest.webmanifest / sw.js
├── assets/ fonts/ ds-assets/     ← hub 自己的静态资源
├── portal/
│   ├── index.html         ← Portal SPA（业务前店，H5 风）
│   └── assets/ fonts/
├── ops/
│   ├── index.html         ← Ops SPA（控制台，dark teal）
│   └── assets/ fonts/
└── noc/
    ├── index.html         ← NOC SPA（告警中心，amber）
    └── assets/ fonts/
```

### 7.3 SPA fallback
- `/portal`、`/portal/*` → 返回 `web_dist/portal/index.html`
- `/ops`、`/ops/*`、`/noc`、`/noc/*` 同理
- `/`、`/*`（非 API、非 SPA、非静态）→ 根 hub index.html
- 每个 SPA 的 React Router 做客户端路由，**整页跳转跨产品**（vite 时代就定下的）

### 7.4 Token 注入
`serveIndexHtmlFromPath()` 把 `window.__HERMES_SESSION_TOKEN__="...";` 注入到 `<head>` 之后。前端所有 `*.tsx` 都靠这个全局变量鉴权。

### 7.5 前端代码量

| 位置 | 行数 |
|---|---|
| `web/src`（根 hub） | 187 |
| `web/portal` | 3 312 |
| `web/ops` | 14 425 |
| `web/noc` | 7 827 |
| `web/packages/ui` | 2 597 |
| `web/packages/jarvis` | 3 093 |
| **前端总计** | **~31 741** |

另有 `ui-tui/`（单独的 TUI 终端 UI，Node 侧），与 Java 后端分离，本次分析暂列外围。

## 8. 数据持久化

**仅 SQLite**（`org.xerial:sqlite-jdbc`）。
- `SessionManager` 用它存 `~/.hermes/sessions.db`（推测，路径是 `Path dataDir = Paths.get(user.home, ".hermes")`）。
- WorkspaceService / BusinessRunService / BusinessApprovalService 等业务服务——**没在依赖里看到 JDBC 连接池或 JPA/MyBatis**。待阶段 4 验证这些业务服务的持久化方式（很可能是 JSON 文件存 `~/.hermes/workspaces/` 或纯内存）。

🔴 这是关键发现：如果业务数据走 JSON 文件/内存，多实例部署就是纯空谈（阶段 4 验证）。

## 9. 目录与部署

```
hermes-agent-java/
├── pom.xml
├── Dockerfile                  ← 多阶段（maven:3.9-eclipse-temurin-21-alpine → eclipse-temurin:21-jre-alpine）
├── docker-compose.yml          ← 单服务 hermes，可选 prometheus（注释掉）
├── src/main/java/...           ← 后端 506 文件
├── src/test/...                ← 149 测试
├── web/                        ← 前端源码（Vite 多 SPA monorepo）
├── web_dist/                   ← （build 产物）部署时需要存在
├── hermes_cli/web_dist/        ← 备选 web_dist 位置
├── plugins/                    ← 外部插件 yaml（platforms + model-providers）
├── ui-tui/                     ← 独立 TUI 项目（Node）
├── monitoring/{grafana,prometheus}/
├── scripts/                    ← backup/cleanup/smoke 脚本
├── docs/                       ← 文档
└── tests/                      ← Python 辅助测试（test_cleanup_test_tenants.py）
```

Dockerfile 默认命令：
```
exec java $JAVA_OPTS -jar hermes.jar dashboard --port $HERMES_PORT --host $HERMES_HOST
```
→ 走 `DashboardRunner` 主类，**只启 Dashboard**，不起 Gateway、不起 PlatformAdapter。

Docker 环境变量：`HERMES_HOME=/data`、`HERMES_PORT=8080`、`HERMES_HOST=0.0.0.0`、`JAVA_OPTS=-Xms256m -Xmx1g -XX:+UseG1GC`。挂载 `/data` 卷持久化。

Healthcheck：`curl -sf http://localhost:8080/health`（其实是 Dashboard 的 health，不是 Gateway）。

## 10. 阶段 0 总结

### 10.1 架构 1 句话
> **单体 JVM 应用**，两个 Javalin 实例（Gateway 8080 接 IM + OpenAI API，Dashboard 9119/容器内 8080 提供 SPA + 业务 REST + Jarvis），Service 层手工 new/wire，持久化主要靠 SQLite + 可能的 JSON 文件，前端是 Vite 多 SPA（hub + portal + ops + noc）+ `@hermes/ui` + `@hermes/jarvis` 共享包。

### 10.2 核心架构图（基础设施层）

```
┌───────────────────────────────── JVM (hermes.jar) ────────────────────────────────┐
│                                                                                   │
│  ┌─── IM Platforms (Discord/Telegram/Feishu/QQ/Wecom) ─── webhook ───┐            │
│  │                                                                   │            │
│  ▼                                                                   ▼            │
│  ┌──────────────────── Javalin :8080 ────────────────────┐  ┌── Javalin :9119 ──┐│
│  │ GatewayServerV2                                       │  │ DashboardServer   ││
│  │  · /webhook/{platform}                                │  │  · /api/config    ││
│  │  · /api/chat, /api/chat/stream                        │  │  · /api/sessions  ││
│  │  · /v1/chat/completions (OpenAI compat)               │  │  · /api/cron      ││
│  │  · /api/tenants CRUD                                  │  │  · /api/v1/biz/*  ││
│  │  · /api/compare/*                                     │  │  · /api/org/*     ││
│  │ extractTenantContext → checkChatAuth                  │  │  · /api/jarvis/*  ││
│  └────────────┬──────────────────────────────────────────┘  │  · SPA static      ││
│               │ sessionToken 共享注入 ◄────────────────────┤  · Bearer token     ││
│               │                                            └──────┬─────────────┘│
│               ▼                                                   │              │
│  ┌────────────────────────────────────────────────────────────┐   │              │
│  │  Service 层（手工 new，无 DI）                             │   │              │
│  │  TenantManager · ApprovalSystem · ToolRegistry             │   │              │
│  │  WorkspaceService · TeamBlueprintService · ScenarioService │   │              │
│  │  BusinessApproval/Run/Insight/Template/SLAManager/DLQ...  │   │              │
│  │  PluginManager · SkillManager · MemoryManager              │   │              │
│  │  TenantAwareAIAgent (per-workspace pool in ChatService)    │   │              │
│  └────────────────────────────┬───────────────────────────────┘   │              │
│                               │                                   │              │
│              ┌────────────────┴────────────────┐                  │              │
│              ▼                                 ▼                  ▼              │
│     SQLite (~/.hermes/*.db)           外部 LLM API          web_dist/ (静态 SPA) │
│     (sessions/部分业务?)           (OpenAI/Anthropic/                             │
│                                    Bedrock/OpenRouter)                           │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### 10.3 阶段 0 已识别的债/风险（初筛，后续阶段验证）

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🟡 | 双配置系统 | `HermesConfig` vs `ConfigManager` | 功能重叠，易出 bug，应合并 |
| 🟡 | 无 DI 容器 | `DashboardServer` 构造器 ~250 行 | 上帝类手工装配，可测性差 |
| 🔴 | 持久化只看到 SQLite | pom 无连接池/ORM | 多实例/水平扩展存疑（待阶段 4 验证业务层持久化） |
| 🔴 | SSE `?token=` query 鉴权 | DashboardServer middleware | token 进 access log / browser history |
| 🔴 | Session token 启动随机生成、无持久化 | DashboardServer | 重启即失效，无法做蓝绿/多副本 |
| 🟡 | JSON 双库（fastjson2 + Jackson） | 代码风格混杂 | 应该收敛到一个 |
| 🟡 | Dockerfile 只启 Dashboard，不起 Gateway | Dockerfile CMD | 产品化部署模式与全栈模式不同，文档需澄清 |
| 🟡 | 两 Javalin 实例同 JVM 依赖内存引用通信 | Gateway ↔ Dashboard | 无法独立扩缩容，进程崩即全崩 |
| 🟢 | `ScenarioService` setter 循环注入 | DashboardServer 构造器 | 构造期逃逸/循环依赖信号 |
| 🟢 | Validation API 无 runtime | jakarta.validation-api | `@Valid` 等注解不会真正触发校验 |

### 10.4 下一步
→ **阶段 1：核心执行引擎（Agent Runtime）** —— 深入 `agent/`、`model/`、`prompt/`、`memory/`、`trajectory/`，弄清 LLM 调用主循环、消息格式、上下文管理、tool-call 编排是怎么跑起来的。
