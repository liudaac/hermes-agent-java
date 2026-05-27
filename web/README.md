# Hermes Agent — Web UI

Browser-based dashboard for managing Hermes Agent configuration, API keys, monitoring active sessions, viewing analytics, and managing cron jobs.

## Stack

- **Vite** + **React 19** + **TypeScript**
- **Tailwind CSS v4** with custom dark theme and CSS variable-driven theming
- **shadcn/ui**-style components (hand-rolled, no CLI dependency)
- **React Router v7** for client-side navigation
- **i18n** with locale switching

## Development

```bash
# Start the backend API server
cd ../
python -m hermes_cli.main web --no-open

# In another terminal, start the Vite dev server (with HMR + API proxy)
cd web/
npm run dev
```

The Vite dev server proxies `/api` requests to `http://127.0.0.1:9119` (the FastAPI backend).

## Build

```bash
npm run build
```

This outputs to `../hermes_cli/web_dist/`, which the FastAPI server serves as a static SPA. The built assets are included in the Python package via `pyproject.toml` package-data.

## Structure

```
src/
├── components/
│   ├── ui/              # Reusable UI primitives (Card, Badge, Button, Input, etc.)
│   ├── Backdrop.tsx     # Theme-aware background overlays
│   ├── CardHeaderIcon.tsx   # CardHeader with icon + title pre-composed
│   ├── DataTable.tsx    # Generic table primitives (Header/Body/Row/Cell)
│   ├── LanguageSwitcher.tsx # Locale selector
│   ├── LiveBadge.tsx    # Animated "live" indicator with pulsing dot
│   ├── LoadingSpinner.tsx   # Standard loading spinner (sm/md/lg sizes)
│   ├── Markdown.tsx     # Markdown renderer for session messages
│   ├── SidebarLayout.tsx    # Two-column layout with sticky sidebar
│   ├── ThemeSwitcher.tsx    # Theme picker with color swatches
│   └── Toast.tsx        # Toast notification UI (ported to global Context)
├── hooks/
│   └── useToast.ts      # Global Toast Context + showToast hook
├── i18n/                # Internationalization (context + translations)
├── lib/
│   ├── api.ts           # Typed API client for all backend endpoints
│   ├── nested.ts        # Nested object get/set helpers
│   └── utils.ts         # cn() helper, timeAgo, isoTimeAgo
├── pages/
│   ├── StatusPage.tsx   # Agent status, gateway health, active/recent sessions
│   ├── SessionsPage.tsx # Session browser with FTS search, expandable message viewer
│   ├── AnalyticsPage.tsx# Token usage charts, daily/model/skill breakdown tables
│   ├── LogsPage.tsx     # Live log tailing with SSE, filtering by level/component
│   ├── CronPage.tsx     # Cron job management with schedule preview
│   ├── SkillsPage.tsx   # Skill toggle, toolset grid, category filtering
│   ├── ToolsPage.tsx    # Tool catalog and details
│   ├── TenantsPage.tsx  # Multi-tenant management (quota, security, audit)
│   ├── ConfigPage.tsx   # Dynamic config editor (YAML + form modes)
│   └── EnvPage.tsx      # API key management with provider grouping, OAuth cards
├── plugins/             # Dashboard plugin system (runtime script loading)
├── themes/
│   ├── context.tsx      # ThemeProvider (reads/writes CSS custom properties)
│   ├── presets.ts       # Built-in themes (Hermes Teal, Midnight, Ember, etc.)
│   └── types.ts         # Theme type definitions
├── App.tsx              # Main layout, navigation, plugin route registration
├── main.tsx             # React entry point (with ToastProvider)
└── index.css            # Tailwind imports, theme tokens, animations
```

## Architecture Notes

### Theming

Themes are expressed as a 3-triplet palette (`background`, `midground`, `foreground`) plus a warm-glow tint. All downstream shadcn-compat tokens (`--color-card`, `--color-border`, etc.) are defined as `color-mix()` expressions over the triplets, so switching themes at runtime cascades to every surface without remounting components.

Built-in themes: `default` (Hermes Teal), `midnight`, `ember`, `mono`, `cyberpunk`, `rose`.

### Toast Notifications

Toast is rendered globally via `<ToastProvider>` in `main.tsx`. Individual pages call `const { showToast } = useToast()` — no need to import or render `<Toast />` in each page.

### Chart Colors

Analytics charts use CSS custom properties (`--color-chart-input`, `--color-chart-output`) instead of hard-coded hex values, ensuring they adapt to the active theme.

### Component DRY Patterns

Common UI patterns have been extracted to avoid copy-paste:

- **`<LoadingSpinner size="sm|md|lg" padding="..." />`** — replaces repeated inline spinner markup across all pages
- **`<LiveBadge label="..." />`** — replaces the pulsing-dot "live" indicator pattern
- **`<CardHeaderIcon icon={...} title={...} />`** — replaces repeated CardHeader + Icon + Title composition
- **`<SidebarLayout sidebar={...}>...</SidebarLayout>`** — replaces the repeated two-column sticky-sidebar layout
- **`<DataTable>` primitives** — replaces repeated `<table>`/`thead`/`tbody`/`tr`/`td` boilerplate in analytics tables

## License

Same as the parent project.
