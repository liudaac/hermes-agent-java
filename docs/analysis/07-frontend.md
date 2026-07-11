# 阶段 7：前端全景

> 分析时间：2026-07-12
> 分析范围：`web/`（根 hub + portal + ops + noc + @hermes/ui + @hermes/jarvis），合计约 **31 741 行**
> HEAD：`e7c2b5b`（后端）/ web 最新 commit `dd8a155`

---

## 1. 总览

前端是**4 个独立 Vite SPA + 2 个共享包**的 monorepo 结构，React 19 + TypeScript + Tailwind CSS v4 + React Router v7。

```
web/
├── packages/
│   ├── ui/           @hermes/ui（2 597 行）  共享原子：cn/format/10 个 UI 组件/i18n/主题预设
│   └── jarvis/       @hermes/jarvis（3 093 行）跨空间对话壳：粒子物理/FSM/聊天/语音/跨 tab 同步
├── portal/           @hermes/portal（3 312 行）业务前店 H5，暖色 glass+aurora 风格，移动优先
├── ops/              @hermes/ops（14 425 行） 控制台，dark teal 风格，桌面优先
├── noc/              @hermes/noc（7 827 行）  NOC 告警中心，amber 风格
└── (根)src/          hub（187 行）           三卡入口 + 跨 SPA 整页跳转发
```

**核心设计原则**（来自 MEMORY 记录的 7 月 7-8 日重构）：
- 每个 SPA **独立 Vite 入口/独立端口/独立路由/独立主题/独立 i18n/独立 build 产物**
- 跨 SPA 跳转用 `<a href>` 整页加载，**不共享 React tree**
- 共享代码通过 Vite alias `@hermes/ui`、`@hermes/jarvis` 引用（实际是源码路径 alias，不是 npm workspace，所以每个 SPA 重复打包共享代码——bundle 略大但部署简单）
- 所有 SPA 末尾挂 `<JarvisCore />` 右下角 orb，跨 tab BroadcastChannel 同步状态

## 2. 构建与开发

| 命令 | 行为 |
|---|---|
| `npm run dev` | concurrently 起 4 个 dev server：hub:5174 / portal:5175 / ops:5176 / noc:5177 |
| `npm run build:hub\|portal\|ops\|noc` | 单独 build 一个 SPA |
| `npm run build:all` | build 四个产物 |
| `npm run sync-assets`（predev/prebuild 自动跑） | 从 node_modules/@nous-research/ui 同步字体/ds-assets |

**端口分配**：hub 5174 / portal 5175 / ops 5176 / noc 5177。

**根 vite 代理**：
- `/api/*` → 后端 DashboardServer（`HERMES_DASHBOARD_URL`，默认 http://127.0.0.1:9119）
- `/portal/*` → portal dev server（5175）
- `/noc/*` → noc dev server（5177）
- `/ops/*` 是根自己的 SPA，不需要代理

**Dev token 注入插件**（`hermesDevToken()`）：dev server 启动时 fetch 后端 index.html，scrape `window.__HERMES_SESSION_TOKEN__` 注入自己的 HTML——解决 dev 下 401 问题。

## 3. 产物布局（build 输出）

```
web_dist/
├── index.html              ← 根 hub（262 KB / gzip 84 KB）
├── favicon.svg / manifest.webmanifest / sw.js
├── assets/ fonts/ ds-assets/
├── portal/
│   └── index.html          ← Portal（319 KB / gzip 104 KB）
├── ops/
│   └── index.html          ← Ops（387 KB / gzip 127 KB）
└── noc/
    └── index.html          ← NOC（384 KB / gzip 125 KB）
```

**后端静态服务**（DashboardServer.serveIndexHtmlFromPath）：
- `/`、`/*` → 根 hub index.html
- `/portal*` → portal/index.html（BrowserRouter fallback）
- `/ops*` → ops/index.html
- `/noc*` → noc/index.html
- 所有 `<head>` 注入 `window.__HERMES_SESSION_TOKEN__="..."` 鉴权

## 4. 共享包 @hermes/ui（2 597 行）

**导出清单**：
- `lib/cn.ts`：clsx + tailwind-merge（className 合并）
- `lib/format.ts`：formatRelativeTime / formatNumber / formatPercent / formatTokenCount / timeAgo / isoTimeAgo
- `components/ui/`（10 个原子组件，shadcn/ui 风格）：badge / button / card / input / label / select / separator / skeleton / switch / tabs
- `i18n/`：I18nProvider + useI18n + en/zh + types（注：Portal 有自己独立的 i18n.tsx，没走 @hermes/ui 的 i18n，是债）
- `themes/`：types + presets（BUILTIN_THEMES：default/midnight/rose）
- **不导出**：useSSE / useToast / ThemeProvider / plugins——每个 SPA 自己实现

## 5. 共享包 @hermes/jarvis（3 093 行）— 跨空间对话壳

**6 个主形态 + 4 个新增形态**（按 design.md）：
- 核心 6 形态：core(24)、sphere(60)、helix(80)、cascade(40)、pulse(16)、net(48)
- v1 新增：dawn、archive、vigilant、reflective

**核心模块**：

| 模块 | 文件 | 行数 | 职责 |
|---|---|---|---|
| JarvisFSM | core/JarvisFSM.ts | ~180 | 有限状态机：dormant → summoning → summoned → expanded → fullscreen → dismissing → dormant |
| JarvisEngine | core/JarvisEngine.ts | ~200 | 粒子物理引擎（tick/repeller/attractor） |
| JarvisOrb | core/JarvisOrb.tsx | ~130 | 右下角 88×88 orb canvas 渲染 |
| Particle/Physics/Color | core/ | ~200 | 粒子系统 |
| forms/ | forms/index.ts + transitions.ts | ~250 | 6+4 形态的粒子布局工厂 |
| useJarvisStore | hooks/useJarvisStore.ts | ~120 | zustand 状态（form/overlay/messages/pendingApproval/...） |
| useJarvisChat | hooks/useJarvisChat.ts | ~200 | 调 jarvisApi.chat，处理流式回复+approval |
| useJarvisVoice | hooks/useJarvisVoice.ts | ~180 | Web Speech API（SpeechSynthesis + SpeechRecognition） |
| useCrossSpaceSync | hooks/useCrossSpaceSync.ts | ~70 | BroadcastChannel + localStorage 跨 tab/SPA 同步 form/overlay |
| useContextAwareness | hooks/useContextAwareness.ts | ~60 | 感知当前 SPA/activeResource |
| useIntentRouter | hooks/useIntentRouter.ts | ~80 | 调用 classifyIntent，得到 cross-space link |
| useLongIdle/useAttention/useKeyShortcuts | hooks/ | ~120 | 长 idle 自动呼吸、注意力脉冲、⌘J 召唤 |
| HudRing/CenterCore/Scanline/DataOverlay | hud/ | ~350 | 召唤态 HUD：3 圈轨道+6 棱体核心+扫描线+24 数据点 |
| JarvisOverlay/HudPanel/Fullscreen/ConversationFlow/MessageBubble | overlay/ | ~700 | 对话 UI：气泡（user/jarvis/tool/approval）+全屏沉浸态 |
| jarvisApi | api/jarvisApi.ts | ~140 | 4 endpoint 客户端（chat/intent/approval/stream SSE） |
| intentRoutes | api/intentRoutes.ts | ~70 | intent→SPA 路径映射表 |

**设计要点**：
- JarvisCore mount 时默认挂 useJarvisChat/useJarvisVoice——三个 SPA 不做任何配置就有可用的对话助手
- 跨 tab 同步**只同步 form/overlay，不同步对话历史**（按 design.md §11.1，每个 tab 自己的 scrollback，避免混乱）
- SSE stream 用 `?token=` query 鉴权（EventSource 不能设 header），已记为阶段 0 P0 债
- welcome 语音通过 `useJarvisVoice().welcome()` 在 summoned 时播一段欢迎词（commit e7c2b5b 加的）

## 6. Portal（3 312 行，最轻）— 业务前店

**视觉风格**：H5 暖色 dark glass + aurora + grain 颗粒纹理，Playfair Display 标题 + Inter 正文，移动优先。

**主题 token（oklch）**：bg-0 `oklch(0.14 0.015 60)`（深棕）+ accent `oklch(0.78 0.16 70)`（暖金橘）。

**6 个专属组件**：
- `GlassCard`/`AuroraBackground`/`TopBar`/`BottomTabBar`/`EmployeeCard`/`StatusPill`

**8 页面（路由扁平 depth ≤ 2）**：

| 路径 | 页面 | 功能 |
|---|---|---|
| `/` | Home.tsx | Hero + 实时运行条（LiveRunsTicker）+ 三步引导抽屉（FirstTimeOnboardingDrawer）+ 团队/场景/最近运行 |
| `/teams` | Teams.tsx | 数字员工（团队）列表 |
| `/teams/:teamId` | TeamDetail.tsx | 团队详情+agent 卡片 |
| `/templates` | Templates.tsx | 场景模板画廊（H5→Portal clone 入口） |
| `/approvals` | Approvals.tsx | 待审批列表（三色桶） |
| `/runs` | Runs.tsx | 运行列表（卡片式） |
| `/runs/:workspaceId/:runId` | RunDetail.tsx | RunStoryTimeline 视觉升级 |
| `/insights` | Insights.tsx | 业务洞察（ROI 等） |

**底部 6 项 BottomTabBar**：Home/Teams/Templates/Approvals/Runs/Insights（移动端触摸友好）。

**API**：portal.ts 调 `/api/v1/business/*` 为主；_base.ts 带 `__HERMES_SESSION_TOKEN__` Bearer 注入。

**债**：Portal 自己的 i18n.tsx 是早期写的，没用 @hermes/ui 的 i18n provider。

## 7. Ops（14 425 行，最重）— 控制台

**视觉风格**：dark teal（青蓝），专业控制台感，桌面优先（SidebarLayout 左侧导航）。

**13 专属组件**：
- SidebarLayout/OpsTopBar/CrossProductSwitcher（右侧跨 SPA 跳转 pill）
- DataTable/Markdown/MarkdownRenderer/LiveBadge/LoadingSpinner/Toast
- OAuthLoginModal/OAuthProvidersCard/ThemeSwitcher/LanguageSwitcher
- ModelInfoCard/CardHeaderIcon/AutoField/Backdrop

**13 页面**：

| 路径 | 页面 | 功能 |
|---|---|---|
| `/` | StatusPage.tsx | 仪表盘总览（健康状态/模型/运行数等） |
| `/playground` | PlaygroundPage.tsx | Chat playground（直连 Gateway `/api/chat/stream`） |
| `/compare` | ComparePage.tsx | 多模型/多租户对比（对应后端 TenantComparisonOrchestrator） |
| `/sessions` | SessionsPage.tsx | 会话浏览/搜索/消息查看 |
| `/analytics` | AnalyticsPage.tsx | 用量分析（token/成本/趋势） |
| `/logs` | LogsPage.tsx | 日志查看+实时 tail SSE |
| `/cron` | CronPage.tsx | 定时任务 CRUD + 手动触发 + 运行流 |
| `/skills` | SkillsPage.tsx | 技能管理 |
| `/tools` | ToolsPage.tsx | 工具集浏览 |
| `/tenants` | TenantsPage.tsx | 多租户管理（配额、用量、审计、配置） |
| `/config` | ConfigPage.tsx | Hermes 配置查看/修改 |
| `/env` | EnvPage.tsx | 环境变量/API Key 管理（安全存储） |
| `/org` | OrgPage.tsx | AI 原生组织总览 |
| `/sla` | AnalyticsPage.tsx（别名） | SLA 监控（复用 AnalyticsPage） |

**插件系统**：ops/src/plugins/ 有完整的 Plugin 注册/加载框架（types+registry+usePlugins），支持运行时插件渲染到 UI。

**API 模块**：
- `ops.ts`：控制台核心 API（config/sessions/logs/cron/skills/tools/tenants）
- `ops-tenant.ts`：租户级 API（配额/审计）
- `noc.ts`：跨产品复用 NOC 的部分 API（compare/playground 用）
- `sse.ts`：SSE 连接（日志/cron 流）
- `_base.ts`：fetchJSON + gatewayFetch（直连 8080 Gateway）双客户端

**hooks**：useSSE（SSE 订阅）+ useToast（全局 toast）。

**主题**：ops/src/themes/ 有 default/midnight/rose 等主题预设，ThemeSwitcher 可切换；space-themes 是旧空间主题遗留（Portal/NOC 拆出后可能清理）。

## 8. NOC（7 827 行）— 告警中心

**视觉风格**：amber 琥珀告警色 + 底部 noc-glow-bottom 发光带，"控制室"氛围，桌面优先。

**1 个专属组件**：NocTopBar（含跨产品 CrossProductSwitcher）。

**6 页面**：

| 路径 | 页面 | 功能 |
|---|---|---|
| `/` | OrgControlCenterPage.tsx | NOC 总控（对应后端 OrgControlCenterHandler 1313 行） |
| `/traces/:traceId` | TraceDetailPage.tsx | Trace 详情（AgentTrace 可视化） |
| `/workflows` | WorkflowPage.tsx | 工作流 DAG 监控（对应 WorkflowEngine） |
| `/sla` | SLAPage.tsx | SLA 状态+breach 列表 |
| `/dlq` | DLQPage.tsx | 死信队列+retry/resolve |
| `/hitl` | HumanInTheLoopPage.tsx | 人工接管+审批队列 |

**API 模块**：noc.ts（DLQ/SLA/Workflow/Org Control）+ sse.ts（NOC 告警流）+ types（noc/ops/orchestration 类型）。

**插件系统**：同 ops 的 plugins/ 框架（拷贝了一份，代码重复——是债）。

**hooks**：useSSE + useToast（也是拷贝的，重复）。

## 9. 根 Hub（187 行）— 三卡入口

App.tsx 职责：
1. 渲染 `/` 时显示 3 个产品卡片（Portal rose/Ops teal/NOC amber）
2. 其他路径解析 PRODUCT_FORWARDS 表，整页 `window.location.replace` 到对应 SPA 的 index.html
3. `/traces/:id` 特殊正则 → noc
4. 防止重复 forward（`rest.startsWith("index.html")` 检查）
5. 未知路径回 `/`

forward 表把老路径都映射对了：
- `/portal`, `/business`, `/business-portal` → `/portal/index.html`
- `/ops`, `/status`, `/playground`, `/compare`, `/sessions`, `/analytics`, `/logs`, `/cron`, `/skills`, `/tools`, `/tenants`, `/config`, `/env`, `/org`, `/org-manage` → `/ops/index.html`
- `/noc`, `/org-control`, `/workflows`, `/sla`, `/dlq`, `/hitl`, `/traces/` → `/noc/index.html`

## 10. API 客户端架构

**token 注入统一模式**：3 个 SPA + jarvis 都是读 `window.__HERMES_SESSION_TOKEN__`（后端注入到 index.html 的 script 标签），自动加 `Authorization: Bearer xxx`。

**fetchJSON 实现重复**：portal 有自己的 _base.ts（2KB），ops 有自己的（3KB），noc 有自己的（跟 ops 几乎一模一样 3KB），jarvis 自己的在 jarvisApi.ts 内。共 4 份，应该抽到 @hermes/ui。

**SSE**：useSSE hook 在 ops 和 noc 各自一份（拷贝），EventSource 用 `?token=` query 鉴权。jarvis 自己在 jarvisApi.openSuggestionStream 里。

**gatewayFetch**：ops 和 noc 都有（直连 8080），用于 compare/playground/chat 走 Gateway 而非 Dashboard。

## 11. i18n

- **@hermes/ui** 的 i18n：I18nProvider + en/zh 双语言，通过 Context 提供 useI18n
- **Ops/NOC**：用 @hermes/ui 的 i18n（有自己的 en/zh 补充文件）
- **Portal**：自己写了一套 i18n.tsx（早期遗留），没接 @hermes/ui——应该统一

切换按钮：OpsTopBar 和 NocTopBar 都有 LanguageSwitcher。

## 12. 样式与主题

- **Tailwind v4**（`@tailwindcss/vite` + `@import "tailwindcss"`）
- **oklch 颜色系统**：三个 SPA 各自定义 `@theme { --color-* }` 覆盖 Tailwind 主题色
- **字体**：Playfair Display（标题）、Inter（正文）、JetBrains Mono（等宽）
- **@source 指令**：每个 SPA 的 theme.css 都要 `@source "../../packages/jarvis/src/**"` 和 `@source "../../packages/ui/src/**"` 让 Tailwind 扫描共享包里的 class，否则 tree-shaking 会把 Jarvis 的 fixed/bottom-6/z-50 等 class 清掉
- **跨 SPA 风格差异**：
  - Portal：暖色 + glass（backdrop-blur）+ aurora（radial glow）+ grain（SVG noise）+ 圆角大 2xl
  - Ops：冷青蓝 + 扁平卡片 + Sidebar + 数据表格密度高
  - NOC：琥珀色告警 + glow-bottom + 控制室氛围

## 13. Jarvis 前端全链路

```
3 个 SPA 末尾挂 <JarvisCore />
    │
    ├─ JarvisFSM：dormant → summoning → summoned → expanded/fullscreen → dismissing
    ├─ JarvisEngine：6+4 形态粒子物理
    ├─ useContextAwareness：感知当前 SPA 是 portal/ops/noc、当前页面资源
    ├─ useJarvisChat：
    │    ├─ onSubmit → POST /api/jarvis/chat { message, context: { space, workspaceId, activeResource } }
    │    ├─ 响应含 reply / toolCalls[] / approval / crossSpaceLink
    │    └─ approval 弹审批 UI → POST /api/jarvis/approval/:id
    ├─ useJarvisVoice：SpeechSynthesis welcome + SpeechRecognition 收声
    ├─ useIntentRouter：输入→classifyIntent→跨 SPA linkTo
    ├─ useCrossSpaceSync：BroadcastChannel + localStorage 同步 form/overlay
    ├─ useKeyShortcuts：⌘J/⌘K 召唤
    └─ SSE stream：/api/jarvis/stream?workspaceId=xxx&token=yyy
         └─ 订阅 BusinessEventBus 事件，推 Suggestion 到对话流
```

**注意**：SSE 订阅目前只是拿到 Suggestion 显示，前端还没做"自动跳转到跨 SPA 目标页"的交互——crossSpaceLink 有了但没接到 router。

## 14. 前端识别到的债/问题

| 级 | 项 | 位置 | 说明 |
|---|---|---|---|
| 🟠 | **4 份重复 fetchJSON** | portal/ops/noc/_base.ts + jarvisApi | 应抽到 @hermes/ui/lib/api.ts，统一 token 注入/错误处理 |
| 🟠 | **useSSE 和 useToast 在 ops/noc 各拷一份** | ops/hooks 与 noc/hooks | 应抽到 @hermes/ui |
| 🟡 | Portal 独立 i18n.tsx 不用 @hermes/ui i18n | portal/src/i18n.tsx | 双 i18n 系统，语言切换会断裂 |
| 🟡 | noc/plugins 拷贝 ops/plugins | noc/src/plugins | 应抽到 @hermes/ui 或共享目录 |
| 🟡 | bundle 重复打包 @hermes/ui 和 @hermes/jarvis | vite alias 非 workspace | 每个 SPA 独立打包共享代码，gzip 多 ~25KB/SPA |
| 🟡 | SSE `?token=` query 鉴权 | jarvisApi、useSSE | token 进 access log/浏览器历史（阶段 0 已记） |
| 🟡 | @hermes/ui 的 themes/presets 没被 ops/noc/portal 用 | packages/ui/themes | 三个 SPA 各自在 theme.css 定义 @theme，presets 死代码 |
| 🟡 | CrossProductSwitcher 在 OpsTopBar 和 NocTopBar 各拷一份 | ops/noc/components | 应共享组件 |
| 🟢 | space-themes 旧空间主题遗留 | ops/src/themes/space-themes.ts | 拆 3 SPA 后可以删 |
| 🟢 | Jarvis crossSpaceLink 只返回 linkTo 字段没自动导航 | useIntentRouter | 应该点 Suggestion 自动整页跳 |
| 🟢 | ESLint 配置 eslint.config.js 在根，子 SPA 没独立 | web/ | 构建时 lint 只扫根 src+packages，不扫 portal/ops/noc/src |

## 15. 前端整体评估

**前端成熟度：Beta+（85%）**

### 亮点
- **架构清爽**：monorepo 拆得干净，4 SPA 边界清晰，整页跳解决跨 SPA 状态污染问题
- **Jarvis 设计大胆且完成度高**：粒子物理+FSM+语音+跨 tab 同步+SSE 都真能跑，10 个形态设计文档严格落地
- **视觉差异化**：三个 SPA 三种调性（H5 暖金/控制台青蓝/NOC 琥珀），不千篇一律
- **dev 体验好**：concurrently 4 端口+dev token 自动注入，热更新流畅
- **Vite 7 + React 19 + Tailwind v4** 都是最新版，技术债少
- **类型安全**：全 TypeScript，API 响应类型明确

### 待补
- API 客户端/hooks/plugins 重复代码清理（低优，容易处理）
- Portal i18n 统一（中优）
- Jarvis crossSpaceLink 自动跳转交互（小功能，30min）
- ESLint 覆盖子 SPA 目录（10min）
- 如果要更彻底去重，换成 npm workspace + rollup external 让 @hermes/ui 成 external chunk（但要改部署脚本，不值得立刻做）

### 下一步
→ **阶段 8：全景汇总** —— 把前 7 个阶段的发现汇总，重新评估成熟度、出一份按优先级排序的债清单、给出迭代路线图建议。
