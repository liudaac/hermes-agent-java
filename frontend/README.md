# Legacy Vue Dashboard

> Status: legacy / fallback UI. New dashboard work should target the React app in `../web`.

This directory contains the older Vue 3 + Vite dashboard implementation. It is kept in the repository as a compatibility fallback because some deployments may still have a built `frontend/dist` directory.

## Current dashboard mainline

The current dashboard mainline is the React/Vite app in:

```text
web/
```

Build it with:

```bash
cd ../web
npm install
npm run build
```

The React build output is:

```text
hermes_cli/web_dist
```

`DashboardServer` auto-detects static assets in this order when `HERMES_WEB_DIST` is not set:

1. `hermes_cli/web_dist` — current React dashboard build output
2. `web_dist` — legacy/default local path
3. `web/dist` — Vite default fallback
4. `frontend/dist` — legacy Vue fallback

## Tenant management

The React dashboard now owns the main tenant UI:

```text
web/src/pages/TenantsPage.tsx
```

This Vue implementation has older tenant components such as:

```text
src/components/TenantPanel.vue
src/views/TenantView.vue
```

Those components predate the current Java dashboard API contract and may use outdated assumptions such as `id` instead of canonical `tenantId`, wrapper responses like `response.data`, or older quota/security field names.

Do not add new tenant functionality here unless you are deliberately maintaining the legacy Vue fallback. Prefer migrating useful ideas into the React dashboard.

## Local development

If you need to inspect this legacy UI:

```bash
npm install
npm run dev
```

For production dashboard work, use `../web` instead.
