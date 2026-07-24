/**
 * @hermes/ui — shared frontend building blocks.
 *
 * Imported by all three SPAs (portal / ops / noc) via a Vite alias that
 * resolves `@hermes/ui` to this directory. The package never ships as
 * a real npm package — the alias routes everything to source.
 *
 * This package deliberately contains ONLY code with zero npm-package
 * dependencies. Browser globals (window, fetch, EventSource) and React are OK.
 *
 *   1. **Atoms**    — cn(), format helpers, HTTP primitives (fetchJSON)
 *   2. **UI**       — shadcn-compat primitives
 *   3. **i18n**     — I18nProvider + useI18n (no external deps)
 *   4. **Themes**   — theme types + default presets
 *
 * What is NOT here (and intentionally so):
 *   - ThemeProvider (calls /api/dashboard/themes; per-SPA wrapper)
 *   - Plugin SDK (calls /api/dashboard/plugins; per-SPA wrapper)
 *   - Page-level components, routing, top bar
 *   - useToast (ops/noc specific)
 */

// ── 1. Atoms ──────────────────────────────────────────────────────
export { cn } from "./lib/cn";
export type { ClassValue } from "./lib/cn";
export {
  formatRelativeTime,
  formatNumber,
  formatPercent,
  formatTokenCount,
  truncate,
  initials,
  timeAgo,
  isoTimeAgo,
} from "./lib/format";
export { fetchJSON, getSessionToken, waitForSessionToken, gatewayFetch, API_BASE } from "./lib/api";
export { parseSseData, bindEventSource, readChunkedSSE } from "./lib/sse";
export { useEventSource } from "./hooks/useEventSource";
export type { UseEventSourceOptions } from "./hooks/useEventSource";
export { useHarnessStream } from "./hooks/useHarnessStream";
export type {
  AgentEvent,
  AgentEventType,
  HarnessState,
  HarnessStatus,
  AgentPhase,
  ToolCallState,
  HarnessSnapshot,
} from "./types/agent-event";
export { initialHarnessState } from "./types/agent-event";

// ── 5. Harness components ────────────────────────────────────────
export { HarnessStatusPanel } from "./components/harness/HarnessStatusPanel";
export { ApprovalInline } from "./components/harness/ApprovalInline";
export { ToolCallTimeline } from "./components/harness/ToolCallTimeline";

// ── 2. UI primitives ──────────────────────────────────────────────
export * from "./components/ui/badge";
export * from "./components/ui/button";
export * from "./components/ui/card";
export * from "./components/ui/input";
export * from "./components/ui/label";
export * from "./components/ui/select";
export * from "./components/ui/separator";
export * from "./components/ui/skeleton";
export * from "./components/ui/switch";
export * from "./components/ui/tabs";
export { ToastProvider, useToast } from "./components/toast";
export type { ToastState } from "./components/toast";

// ── 3. i18n ───────────────────────────────────────────────────────
export { I18nProvider, useI18n } from "./i18n";
export type { Locale, Translations } from "./i18n/types";

// ── 4. Theme types & defaults ─────────────────────────────────────
export type { DashboardTheme, ThemeLayer, ThemePalette } from "./themes/types";
export { BUILTIN_THEMES, defaultTheme, midnightTheme, roseTheme } from "./themes/presets";
