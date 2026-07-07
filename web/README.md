# Hermes Agent — Web UI

Browser-based dashboard for Hermes Agent. Three independent SPAs, one shared component package, one landing hub.

## 三个产品（不是三个 tab）

The frontend is split into four Vite projects, each with its own bundle, theme, and route table:

| Product | Path | Vite root | Dev port | Build target | Purpose |
|---|---|---|---|---|---|
| **Hub** | `web/` (root) | `./` | 5174 | `web_dist/index.html` | Three-card landing page. Cross-product jumps are full-page navigations. |
| **Portal** | `web/portal/` | `web/portal/` | 5175 | `web_dist/portal/index.html` | H5 business front — "我的数字员工" cards, glass + aurora, mobile-first. |
| **Ops** | `web/ops/` | `web/ops/` | 5176 | `web_dist/ops/index.html` | Control console — platform admins, dense tables, terminal feel. |
| **NOC** | `web/noc/` | `web/noc/` | 5177 | `web_dist/noc/index.html` | Org Control Center — amber alert, dark, full-screen dashboards. |

Each SPA is **completely independent**:
- Separate Vite entry, port, build output
- Separate i18n namespace, theme palette, top bar
- Separate router, navigation, plugin SDK
- Cross-product jumps are `<a href>` (full-page navigation), not SPA route pushes

**Why three products, not three tabs?** Portal's audience is business users who came from the H5 marketing site and expect a mobile-first H5 experience. Ops/NOC's audience is platform admins who want a dense control surface. Mixing them in one shell produced navigation cross-overs, theme bleed, and deep nesting.

## 共享包 `@hermes/ui`

`web/packages/ui/` is consumed by all three SPAs via a Vite alias (`@hermes/ui` → `web/packages/ui/src`). It contains only code with **zero external dependencies** — anything that talks to `/api/*` stays in the SPA that owns it.

```
web/packages/ui/src/
├── lib/
│   ├── cn.ts              # clsx + tailwind-merge
│   └── format.ts          # formatRelativeTime / Number / Percent / TokenCount / timeAgo
├── components/
│   └── ui/                # 10 atomic primitives
│       ├── badge.tsx
│       ├── button.tsx
│       ├── card.tsx
│       ├── input.tsx
│       ├── label.tsx
│       ├── select.tsx
│       ├── separator.tsx
│       ├── skeleton.tsx
│       ├── switch.tsx
│       └── tabs.tsx
├── i18n/                  # I18nProvider + useI18n + en + zh
└── themes/                # Theme types + BUILTIN_THEMES + default/midnight/rose presets
```

**Intentionally NOT in `@hermes/ui`** (each SPA has its own version because they call `/api`):
- `useSse` (portal uses it; ops/noc don't)
- `useToast` (ops/noc use it; portal doesn't)
- `ThemeProvider` (calls `/api/dashboard/themes`)
- Plugin SDK (calls `/api/dashboard/plugins`)

If `@hermes/ui` ever ships code that requires a runtime dependency, the right answer is to pass it as a prop (dependency injection) rather than reach for it from inside the package.

## Stack

- **Vite** (multi-entry per project) + **React 19** + **TypeScript 5.9**
- **Tailwind CSS v4** with `@tailwindcss/vite`
- **shadcn/ui**-style components (hand-rolled, no CLI dependency)
- **React Router v7** for client-side navigation
- **lucide-react** icons
- **Playfair Display** + **Inter** + **JetBrains Mono** (Portal H5 typography)
- **@nous-research/ui** for shared design tokens (Cell/Grid/Typography)

## Development

```bash
# Start the backend API server (issues session tokens for /api/*)
cd ../
python -m hermes_cli.main web --no-open

# Run all four dev servers concurrently (concurrently package)
cd web/
npm run dev
# → hub :5174, portal :5175, ops :5176, noc :5177
```

The hub Vite server proxies `/portal/*` to `:5175` and `/noc/*` to `:5177`, so you can hit a single port and see cross-product nav working.

Each project can be run alone for iteration speed:

```bash
npm run dev:portal   # H5 SPA only, port 5175
npm run dev:ops      # Ops console only, port 5176
npm run dev:noc      # NOC only, port 5177
npm run dev:hub      # Hub only, port 5174
```

The Vite dev server injects `window.__HERMES_SESSION_TOKEN__` from the running `hermes dashboard` (see `vite.config.ts: hermesDevToken` plugin), so `/api/*` calls authenticate transparently.

## Build

```bash
# Build everything in order
npm run build:all

# Or one at a time
npm run build:hub
npm run build:portal
npm run build:ops
npm run build:noc
```

All four artifacts are emitted into `../hermes_cli/web_dist/`:

```
hermes_cli/web_dist/
├── index.html              # hub (262 KB / gzip 84 KB)
├── assets/                 # hub shared assets
├── portal/
│   ├── index.html          # 294 KB / gzip 96 KB
│   └── assets/
├── ops/
│   ├── index.html          # 362 KB / gzip 110 KB
│   └── assets/
└── noc/
    ├── index.html          # 358 KB / gzip 109 KB
    └── assets/
```

The FastAPI server serves this directory as a static SPA. Cross-product URLs (`/portal/...`, `/ops/...`, `/noc/...`) are resolved by the SPA serving each subdirectory; legacy paths (`/status`, `/business-portal`, etc.) are forwarded by the hub's `App.tsx` to the matching entry.

## Layout

```
web/
├── packages/ui/                    # Shared: cn, format, ui atoms, i18n, theme presets
│   └── src/
├── portal/                         # H5 SPA — 8 pages, 6 components, 1 api tree
│   ├── index.html
│   ├── vite.config.ts              # alias '@hermes/ui' → ../packages/ui/src
│   ├── tsconfig.app.json
│   └── src/
│       ├── api/                    # portalApi + types (independent)
│       ├── components/             # GlassCard, AuroraBackground, BottomTabBar, TopBar, EmployeeCard, StatusPill
│       ├── pages/
│       ├── lib/                    # nav.ts (H5 bottom-tab routes)
│       ├── theme.css               # oklch warm dark + aurora + grain
│       ├── i18n.tsx                # Chinese-first business i18n
│       ├── router.tsx              # depth ≤ 2 routes
│       ├── App.tsx
│       └── main.tsx
│
├── ops/                            # Control console — 13 pages, 13 components
│   ├── index.html
│   ├── vite.config.ts
│   ├── tsconfig.app.json
│   └── src/
│       ├── api/                    # opsApi + opsTenantApi + nocApi subset
│       ├── components/             # OpsTopBar, DataTable, OAuth*, ModelInfoCard, ...
│       ├── pages/                  # Status, Sessions, Logs, Analytics, Cron, Skills, Tools, Tenants, Config, Env, Org, Playground, Compare
│       ├── hooks/                  # useSse, useToast
│       ├── themes/                 # ThemeProvider (calls /api/dashboard/themes)
│       ├── plugins/                # Plugin SDK (calls /api/dashboard/plugins)
│       ├── lib/nav.ts              # Three-group nav (operations/observability/configuration)
│       ├── router.tsx
│       ├── App.tsx
│       └── main.tsx
│
├── noc/                            # Org Control Center — 6 pages, 1 component
│   ├── index.html
│   ├── vite.config.ts
│   ├── tsconfig.app.json
│   └── src/
│       ├── api/                    # nocApi (DLQ, takeover, SLA, workflow, ...)
│       ├── components/             # NocTopBar only
│       ├── pages/                  # OrgControlCenter, TraceDetail, Workflow, SLA, DLQ, HumanInTheLoop
│       ├── themes/                 # amber theme
│       ├── plugins/                # Plugin SDK
│       ├── lib/nav.ts
│       ├── router.tsx
│       ├── App.tsx
│       └── main.tsx
│
├── index.html                      # hub entry (the 3-card page)
├── src/                            # hub itself — only 2 files
│   ├── App.tsx                     # PRODUCT_FORWARDS table + 3 cards
│   └── main.tsx                    # createRoot + BrowserRouter
├── vite.config.ts                  # Multi-app config with /portal + /noc proxies
├── tsconfig.app.json
├── package.json
└── sync-assets.mjs                 # Copies fonts / ds-assets from @nous-research/ui to public/
```

## Architecture Notes

### Why four Vite projects instead of one with code splitting?

Three reasons, in order of impact:

1. **Menu click confusion is impossible by construction.** Each SPA's nav is local. The old "ops nav contains a link that jumps to noc" pattern is gone because there's no shared nav to put cross-space links in.
2. **Bundle size is honest.** Portal users don't pay for ops+plugin code. The downside is each SPA's bundle is ~+25KB heavier than ideal (because `@hermes/ui` is re-bundled by the alias rather than shared as a real package). If that ever matters, the fix is to swap the alias for an npm workspace + rollup external.
3. **Deployment is a deployment, not a re-architecture.** The FastAPI server already serves `web_dist/` statically; we just need it to serve subdirectories too. Each SPA is independently buildable, so the backend can iterate on one without rebuilding the others.

### Cross-product navigation

Every cross-product link is a plain `<a href>` to the target SPA's `index.html`. The browser performs a full-page navigation; the React tree of the source SPA unmounts completely; the target SPA mounts from scratch with its own theme, i18n, and routing.

| Source | Target | Mechanism |
|---|---|---|
| Hub `/` cards | `/portal/index.html` | `<a href>` |
| Ops TopBar CrossProductSwitcher | `/portal/index.html`, `/noc/index.html` | `<a href>` with `ExternalLink` icon |
| NOC TopBar CrossProductSwitcher | `/portal/index.html`, `/ops/index.html` | `<a href>` with `ExternalLink` icon |
| Hub legacy paths (`/status`, `/business-portal`, …) | matching entry | `App.tsx` `useEffect` calls `window.location.replace()` |
| Within the same SPA | `/`, `/teams`, `/runs/:id`, … | React Router (no full-page nav) |

### Theming

Each SPA picks a default theme on mount:
- **Hub**: classic Hermes teal (`defaultTheme` from `@hermes/ui/themes/presets`)
- **Portal**: custom oklch warm dark + aurora + grain (no shadcn tokens; H5 is its own world)
- **Ops**: classic Hermes teal; can be overridden by user
- **NOC**: `midnight` + amber radial glow at the bottom

The `ThemeProvider` in ops and noc calls `/api/dashboard/themes` to override with the user's saved choice (LocalStorage key `hermes-dashboard-theme-override`).

### Plugin system

Plugins (JavaScript bundles served from `/dashboard-plugins/<name>/`) register themselves by calling `window.HermesDashboard.register({...})`. The `exposePluginSDK` function in each SPA's `main.tsx` makes the SDK available before plugins load. Each SPA has its own plugin registry — a plugin written for the Ops console will not appear in the NOC.

### Chart colors

Analytics and operational charts use CSS custom properties (`--color-chart-input`, `--color-chart-output`) defined per theme, instead of hard-coded hex values. Switching themes re-flows chart colors without remounting the components.

## Scripts

| Command | Effect |
|---|---|
| `npm run dev` | Run all four Vite projects in parallel (concurrently) |
| `npm run dev:hub` | Hub only (:5174) |
| `npm run dev:portal` | Portal only (:5175) |
| `npm run dev:ops` | Ops only (:5176) |
| `npm run dev:noc` | NOC only (:5177) |
| `npm run build:hub` | Build hub (262 KB) |
| `npm run build:portal` | Build portal (294 KB) |
| `npm run build:ops` | Build ops (362 KB) |
| `npm run build:noc` | Build noc (358 KB) |
| `npm run build:all` | Build all four in order |
| `npm run lint` | ESLint across the root |

## License

Same as the parent project.
