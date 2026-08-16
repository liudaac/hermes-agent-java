# Hermes Agent — Web UI

Browser-based dashboard for Hermes Agent. **Five independent SPAs**, one shared component package, one landing hub.

## 五个产品（不是五个 tab）

The frontend is split into five Vite projects, each with its own bundle, theme, and route table:

| Product | Path | Dev port | Build target | Purpose |
|---|---|---|---|---|
| **Hub** | `web/` (root) | 5174 | `web_dist/index.html` | Ultra-thin landing page. Cross-product jumps are full-page navigations. |
| **Portal** | `web/portal/` | 5175 | `web_dist/portal/index.html` | H5 business front — "我的数字员工" cards, glass + aurora, mobile-first. |
| **Ops** | `web/ops/` | 5176 | `web_dist/ops/index.html` | Control console — platform admins, dense tables, terminal feel. |
| **Admin** | `web/admin/` | 5177 | `web_dist/admin/index.html` | Organization admin — tenants, spaces, users, models, billing, audit. |
| **DevPortal** | `web/devportal/` | 5178 | `web_dist/devportal/index.html` | Developer portal — API browser, env vars, OAuth, webhooks, integration guide. |

Each SPA is **completely independent**:
- Separate Vite entry, port, build output
- Separate i18n namespace, theme palette, top bar
- Separate router, navigation
- Cross-product jumps are `<a href>` (full-page navigation), not SPA route pushes

**Why five products, not five tabs?** Portal's audience is business users who expect a mobile-first H5 experience. Ops's audience is platform admins who want a dense control surface. Admin is for org-level governance. DevPortal is for developers integrating with the API. Mixing them in one shell produced navigation cross-overs, theme bleed, and deep nesting.

## 共享包

### `@hermes/ui`

`web/packages/ui/` — consumed by all SPAs via Vite alias. Contains only code with **zero external dependencies** (no `/api` calls).

```
packages/ui/src/
├── lib/
│   ├── cn.ts              # clsx + tailwind-merge
│   ├── api.ts             # fetchJSON, getSessionToken, gatewayFetch
│   └── format.ts          # formatRelativeTime / Number / Percent / TokenCount / timeAgo / isoTimeAgo
├── components/
│   ├── ui/                # 10 atomic primitives (badge, button, card, input, label, select, separator, skeleton, switch, tabs)
│   ├── toast.tsx          # ToastProvider + useToast
│   └── harness/           # HarnessStatusPanel, ToolCallTimeline, ApprovalInline, HarnessHealth
├── i18n/                  # I18nProvider + useI18n + en + zh + types
├── themes/                # Theme types + BUILTIN_THEMES + default/midnight/rose presets
├── hooks/                 # useEventSource, useHarnessStream
├── types/                 # agent-event types
└── index.ts               # barrel export
```

### `@hermes/jarvis`

`web/packages/jarvis/` — the Jarvis AI assistant overlay (orb + HUD + voice + chat). Consumed by Portal and Ops.

```
packages/jarvis/src/
├── core/                  # JarvisCore, JarvisEngine, JarvisFSM, JarvisOrb, Particle, Physics
├── hud/                   # CenterCore, DataOverlay, HudRing, Scanline
├── overlay/               # JarvisOverlay, JarvisHudPanel, ConversationFlow, MessageBubble, SuggestionTicker, JarvisFullscreen
├── hooks/                 # useJarvisChat, useJarvisStore, useJarvisSuggestions, useJarvisVoice, useAttention, useContextAwareness, useCrossSpaceSync, useIntentRouter, useKeyShortcuts, useLongIdle
├── api/                   # jarvisApi, intentRoutes
├── forms/                 # transitions
└── index.ts
```

## Stack

- **Vite** (multi-entry per project) + **React 19** + **TypeScript 5.9**
- **Tailwind CSS v4** with `@tailwindcss/vite`
- **shadcn/ui**-style components (hand-rolled, no CLI dependency)
- **React Router v7** for client-side navigation
- **lucide-react** icons
- **Playfair Display** + **Inter** + **JetBrains Mono** (Portal H5 typography)
- **@observablehq/plot** for analytics charts

## Development

```bash
# Start the backend API server (issues session tokens for /api/*)
cd ../
python -m hermes_cli.main web --no-open

# Run all five dev servers concurrently
cd web/
npm run dev
# -> hub :5174, portal :5175, ops :5176, admin :5177, devportal :5178
```

Each project can be run alone for iteration speed:

```bash
npm run dev:hub        # Hub only, port 5174
npm run dev:portal     # H5 SPA only, port 5175
npm run dev:ops        # Ops console only, port 5176
npm run dev:admin      # Admin only, port 5177
npm run dev:devportal  # DevPortal only, port 5178
```

The Vite dev server injects `window.__HERMES_SESSION_TOKEN__` from the running `hermes dashboard` (see `vite.config.ts: hermesDevToken` plugin), so `/api/*` calls authenticate transparently.

## Build

```bash
# Build everything
npm run build:all

# Or one at a time
npm run build:hub
npm run build:portal
npm run build:ops
npm run build:admin
npm run build:devportal
```

All five artifacts are emitted into `../hermes_cli/web_dist/`:

```
hermes_cli/web_dist/
├── index.html              # hub (977 KB / gzip 313 KB)
├── assets/                 # hub shared assets
├── portal/
│   ├── index.html          # (461 KB / gzip 139 KB)
│   └── assets/
├── ops/
│   ├── index.html          # (528 KB / gzip 164 KB)
│   └── assets/
├── admin/
│   ├── index.html          # (376 KB / gzip 116 KB)
│   └── assets/
└── devportal/
    ├── index.html          # (409 KB / gzip 127 KB)
    └── assets/
```

The backend server serves this directory as a static SPA. Cross-product URLs (`/portal/...`, `/ops/...`, `/admin/...`, `/devportal/...`) are resolved by the SPA serving each subdirectory.

## Layout

```
web/
├── packages/
│   ├── ui/                        # Shared: cn, format, ui atoms, i18n, themes, toast, harness
│   │   └── src/
│   └── jarvis/                    # Jarvis AI overlay: orb, HUD, voice, chat
│       └── src/
│
├── portal/                        # H5 SPA — 11 pages, 10 components
│   ├── index.html
│   ├── vite.config.ts             # alias '@hermes/ui', '@hermes/jarvis'
│   └── src/
│       ├── api/                   # portalApi (668 lines) + types
│       ├── components/            # GlassCard, AuroraBackground, BottomTabBar, TopBar, EmployeeCard, StatusPill, ChainPlanCard, SearchBar, Skeleton, ErrorCard
│       ├── pages/
│       │   ├── Home.tsx           # dashboard: team cards, approvals, runs, insights
│       │   ├── Teams.tsx          # digital employee list
│       │   ├── TeamDetail.tsx     # employee detail + runs
│       │   ├── Templates.tsx      # scenario template gallery
│       │   ├── Runs.tsx           # run list
│       │   ├── RunDetail.tsx      # run timeline + steps
│       │   ├── Approvals.tsx      # approvals + delegated tasks (tabbed)
│       │   ├── Memory.tsx         # 3-tab: decay pipeline / my memory / self-improvement
│       │   ├── Skills.tsx         # skills marketplace with toggle
│       │   ├── Sessions.tsx       # session history with bookmark/rating
│       │   └── Me.tsx            # hub: profile + nav cards + preferences
│       ├── hooks/                 # useWorkspace, useActiveHarnesses
│       ├── i18n.tsx               # Chinese-first business i18n
│       ├── theme.css              # oklch warm dark + aurora + grain
│       └── router.tsx             # 5-tab bottom nav, depth ≤ 2 routes
│
├── ops/                           # Control console — 13 pages, 15 components
│   ├── index.html
│   ├── vite.config.ts
│   └── src/
│       ├── api/                   # opsApi + opsTenantApi + nocApi + sse
│       │   ├── ops.ts             # status, sessions, logs, analytics, config, cron, skills, tools, traces
│       │   ├── ops-tenant.ts      # tenant management, OAuth providers
│       │   ├── noc.ts             # DLQ, takeover, workflow, SLA
│       │   ├── sse.ts             # SSE streaming (log tail, cron run, business event)
│       │   └── types/             # ops, noc, common, orchestration types
│       ├── components/
│       │   ├── OpsTopBar.tsx      # top bar with cross-product switcher
│       │   ├── compare/           # CompareChat, CompareControls, CompareParticipants
│       │   ├── Markdown.tsx       # markdown renderer
│       │   ├── DataTable.tsx
│       │   ├── ModelInfoCard.tsx
│       │   ├── ThemeSwitcher.tsx
│       │   ├── LanguageSwitcher.tsx
│       │   └── ...
│       ├── pages/
│       │   ├── StatusPage.tsx     # system overview
│       │   ├── SessionsPage.tsx   # session list + message viewer
│       │   ├── ComparePage.tsx    # A/B comparison chat
│       │   ├── PlaygroundPage.tsx # chat playground
│       │   ├── CronPage.tsx       # cron job management
│       │   ├── ToolsPage.tsx      # tool list + detail
│       │   ├── LogsPage.tsx       # log viewer with tail
│       │   ├── AnalyticsPage.tsx  # usage analytics + charts
│       │   ├── DLQPage.tsx        # dead letter queue
│       │   ├── WorkflowPage.tsx   # workflow + checkpoint approval
│       │   ├── HumanLoopPage.tsx  # human takeover control
│       │   ├── SLAPage.tsx        # SLA compliance monitoring
│       │   └── TracesPage.tsx     # full-chain trace viewer
│       ├── i18n/                  # zh + en (operations terminology)
│       ├── themes/                # theme provider + presets
│       ├── plugins/               # plugin SDK
│       └── lib/nav.ts             # 3-group nav (operations/observability/tools)
│
├── admin/                         # Org admin — 10 pages
│   ├── index.html
│   ├── vite.config.ts
│   └── src/
│       ├── api/                   # adminApi + three-layer API
│       ├── pages/
│       │   ├── OrgOverview.tsx    # org summary
│       │   ├── Tenants.tsx        # tenant management
│       │   ├── Spaces.tsx         # space management
│       │   ├── Users.tsx          # user management
│       │   ├── Models.tsx         # model routing + providers
│       │   ├── Billing.tsx        # billing summary
│       │   ├── Audit.tsx          # audit log
│       │   ├── ApprovalPolicy.tsx # approval policy config
│       │   ├── Delegation.tsx     # delegated task management
│       │   ├── Evolution.tsx      # self-evolution proposals
│       │   └── Compare.tsx        # model comparison
│       └── lib/nav.ts
│
├── devportal/                     # Developer portal — 5 pages
│   ├── index.html
│   ├── vite.config.ts
│   └── src/
│       ├── lib/
│       │   ├── api.ts             # env, OAuth, webhook API client
│       │   └── endpoints.ts       # 80 API endpoints catalog (21 categories)
│       ├── components/
│       │   ├── OAuthProvidersCard.tsx
│       │   ├── OAuthLoginModal.tsx
│       │   └── LoadingSpinner.tsx
│       ├── pages/
│       │   ├── ApiBrowser.tsx     # API endpoint browser + Try-It
│       │   ├── EnvVars.tsx        # env var management (provider grouped)
│       │   ├── OAuth.tsx          # OAuth provider logins
│       │   ├── Webhooks.tsx       # webhook registration + testing
│       │   └── Integration.tsx    # SDK guide (curl/JS/Python examples)
│       └── i18n/                  # zh + en (developer terminology)
│
├── index.html                     # hub entry
├── src/                           # hub — 2 files
│   ├── App.tsx                    # PRODUCT_FORWARDS + 5 cards
│   └── main.tsx                   # createRoot
├── vite.config.ts                 # multi-app config with proxies
├── package.json
└── scripts/
    └── sync-assets.mjs            # copy fonts/ds-assets to public/
```

## Product Details

### Portal (H5 Business)

**Audience:** Business users from the marketing H5 site.
**Theme:** oklch warm dark + aurora + grain. Playfair Display headers.
**Nav:** 5-tab bottom bar (首页 / 数字员工 / 运行 / 审批 / 我的).

| Page | Route | Lines | Description |
|---|---|---|---|
| Home | `/` | 445 | Dashboard: team cards, pending approvals, recent runs, insights, recommended scenarios |
| Teams | `/teams` | 96 | Digital employee list |
| TeamDetail | `/teams/:id` | 208 | Employee detail with runs and chain plan |
| Templates | `/templates` | 244 | Scenario template gallery with clone |
| Runs | `/runs` | 182 | Run list with status filter |
| RunDetail | `/runs/:ws/:id` | 290 | Run timeline with steps and approval interaction |
| Approvals | `/approvals` | 296 | Tabbed: pending approvals + delegated tasks |
| Memory | `/memory` | 487 | 3-tab: decay pipeline / my memory (search+edit) / self-improvement proposals |
| Skills | `/skills` | 218 | Skills marketplace with scope badges and toggle |
| Sessions | `/sessions` | 286 | Session history with bookmark, rating, filters, pagination |
| Me | `/me` | 247 | Profile + nav hub to sub-pages + preferences editor |

### Ops (Control Console)

**Audience:** Platform administrators.
**Theme:** Classic Hermes teal, dark, dense.
**Nav:** 3-group sidebar (Operations / Observability / Tools), 13 items.

| Page | Route | Lines | Description |
|---|---|---|---|
| StatusPage | `/` | 598 | System overview: gateway, sessions, config, model info |
| SessionsPage | `/sessions` | 661 | Session list + message viewer with search |
| CronPage | `/cron` | 578 | Cron job CRUD + run history + schedule preview |
| DLQPage | `/dlq` | 168 | Dead letter queue with retry/resolve |
| WorkflowPage | `/workflows` | 167 | Workflow monitoring with checkpoint approval |
| HumanLoopPage | `/hitl` | 166 | Human takeover: confirm/release |
| LogsPage | `/logs` | 433 | Log viewer with level filter, tail streaming |
| AnalyticsPage | `/analytics` | 388 | Usage analytics: daily chart, model breakdown, skills |
| SLAPage | `/sla` | 172 | SLA compliance dashboard |
| TracesPage | `/traces` | 219 | Full-chain trace viewer (tree + timeline) |
| ToolsPage | `/tools` | 249 | Tool list with search and detail |
| PlaygroundPage | `/playground` | 480 | Chat playground with model params |
| ComparePage | `/compare` | 506 | A/B comparison chat (split into 3 sub-components) |

### Admin (Org Governance)

**Audience:** Organization administrators.
**Theme:** Clean, professional.

| Page | Route | Description |
|---|---|---|
| OrgOverview | `/` | Org summary: spaces, users, billing |
| Tenants | `/tenants` | Tenant CRUD + suspend/resume |
| Spaces | `/spaces` | Space management: members, knowledge, policy |
| Users | `/users` | User profile + capabilities + preferences |
| Models | `/models` | Model routing + API keys + providers |
| Billing | `/billing` | Billing summary per tenant |
| Audit | `/audit` | Audit log viewer |
| ApprovalPolicy | `/approvals` | Approval policy configuration |
| Delegation | `/delegation` | Delegated task management |
| Evolution | `/evolution` | Self-evolution proposals review |
| Compare | `/compare` | Model comparison |

### DevPortal (Developer)

**Audience:** Developers integrating with the Hermes API.
**Theme:** GitHub dark (#0d1117 bg, #58a6ff accent). JetBrains Mono for code.

| Page | Route | Lines | Description |
|---|---|---|---|
| ApiBrowser | `/` | 251 | 80 endpoints × 21 categories, search, Try-It, method badges |
| EnvVars | `/env` | 614 | Env var management (provider-grouped, reveal, edit) |
| OAuth | `/oauth` | 26 | OAuth provider logins (PKCE + device code) |
| Webhooks | `/webhooks` | 193 | Webhook registration, list, test |
| Integration | `/integration` | 241 | SDK guide: curl/JS/Python, auth, webhook/OAuth setup |

## Architecture Notes

### Why five Vite projects instead of one with code splitting?

1. **Menu confusion is impossible by construction.** Each SPA's nav is local. Cross-product links are `<a href>` full-page jumps.
2. **Bundle size is honest.** Portal users don't pay for ops code. DevPortal users don't pay for portal's H5 components.
3. **Independent deployment.** Each SPA is independently buildable. The backend can iterate on one without rebuilding the others.

### Cross-product navigation

Every cross-product link is a plain `<a href>` to the target SPA's `index.html`. The browser performs a full-page navigation; the React tree unmounts completely; the target SPA mounts fresh.

| Source | Target | Mechanism |
|---|---|---|
| Hub `/` cards | `/portal/index.html`, `/ops/index.html`, etc. | `<a href>` |
| Ops TopBar | `/portal/`, `/admin/`, `/devportal/` | `<a href>` |
| DevPortal TopBar | `/portal/`, `/ops/`, `/admin/` | `<a href>` |
| Within same SPA | `/`, `/teams`, `/runs/:id`, … | React Router |

### Theming

Each SPA picks its own default theme:
- **Hub**: classic Hermes teal
- **Portal**: custom oklch warm dark + aurora + grain (H5 is its own world)
- **Ops**: classic Hermes teal; user can override via ThemeSwitcher
- **Admin**: clean professional light
- **DevPortal**: GitHub dark (#0d1117)

### Plugin system

Plugins (JavaScript bundles served from `/dashboard-plugins/<name>/`) register via `window.HermesDashboard.register({...})`. Each SPA has its own plugin registry.

### SSE streaming

Ops uses SSE (Server-Sent Events) for real-time updates:
- Log tail streaming (`/api/logs/tail`)
- Cron run streaming
- Business event streaming
- Jarvis AI assistant (`/api/jarvis/stream`)

SSE tokens are HMAC-signed with scope binding — URL parameters no longer determine scope.

## Refactoring History

| Phase | Description | Result |
|---|---|---|
| 1 | 4-SPA skeleton — add Admin + DevPortal, streamline Ops | Hub + Portal + Ops + Admin + DevPortal |
| 2 | Migrate Admin pages from Ops/Portal to Admin SPA | Tenants, Spaces, Users, Models, Billing, Audit moved to Admin |
| 3 | Build out DevPortal SPA | Env/OAuth migrated from Ops; API Browser, Webhooks, Integration guide added |
| 4 | Portal rewrite | UserAdmin split into Memory/Skills/Sessions/Me; Approvals enhanced with delegated tasks |
| 5 | Ops rewrite | ComparePage split into 3 components; stub pages (DLQ/Workflow/HITL) fleshed out; SLA + Traces pages added |
| 6 | Cleanup | Orphaned files deleted; @nous-research/ui dependency removed; i18n consolidated |

## Scripts

| Command | Effect |
|---|---|
| `npm run dev` | Run all five Vite projects in parallel |
| `npm run dev:hub` | Hub only (:5174) |
| `npm run dev:portal` | Portal only (:5175) |
| `npm run dev:ops` | Ops only (:5176) |
| `npm run dev:admin` | Admin only (:5177) |
| `npm run dev:devportal` | DevPortal only (:5178) |
| `npm run build:all` | Build all five |
| `npm run build:hub` | Build hub (977 KB / gzip 313 KB) |
| `npm run build:portal` | Build portal (461 KB / gzip 139 KB) |
| `npm run build:ops` | Build ops (528 KB / gzip 164 KB) |
| `npm run build:admin` | Build admin (376 KB / gzip 116 KB) |
| `npm run build:devportal` | Build devportal (409 KB / gzip 127 KB) |
| `npm run lint` | ESLint across the root |

## License

Same as the parent project.
