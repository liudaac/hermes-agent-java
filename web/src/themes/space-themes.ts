/**
 * Space-scoped theme mappings.
 *
 * Each "mental space" (Ops / NOC) maps to a dashboard theme that
 * visually matches its product role:
 *
 *   - Ops:  default (classic Hermes teal, terminal/DS feel)
 *   - NOC:  amber (warm alert + amber glow, SOC/NOC feel)
 *
 * Portal is now a separate SPA (web/portal/) and ships its own
 * H5 visual system — it no longer participates in the combined
 * dashboard theme registry.
 *
 * The active theme follows the active space. Users can still override
 * via ThemeSwitcher — that override is remembered per-browser.
 */

import { BUILTIN_THEMES, defaultTheme, midnightTheme } from "./presets";
import type { DashboardTheme } from "./types";
import type { SpaceName } from "@/lib/routing/spaces";

/**
 * Custom NOC theme — not in the backend BUILTIN_THEMES list because the
 * backend list is owned by the Java server. This is a frontend-only
 * palette that NOC users get automatically when they enter /noc.
 */
export const nocTheme: DashboardTheme = {
  name: "noc-amber",
  label: "NOC Amber",
  description: "Warm amber alert — NOC/SOC console feel",
  palette: {
    background: { hex: "#0d0805", alpha: 1 },
    midground: { hex: "#ffc97a", alpha: 1 },
    foreground: { hex: "#ffffff", alpha: 0 },
    warmGlow: "rgba(251, 191, 36, 0.42)",
    noiseOpacity: 1.1,
  },
};

export const SPACE_THEMES: Record<SpaceName, string> = {
  ops: "default",
  noc: "noc-amber",
};

/**
 * Resolve the theme object for a given space name.
 * Falls back to the default theme if the named theme is missing.
 */
export function themeForSpace(space: SpaceName): DashboardTheme {
  const themeName = SPACE_THEMES[space];
  return BUILTIN_THEMES[themeName] ?? defaultTheme;
}

/**
 * All space themes, flattened.
 * Used by ThemeSwitcher to show "Space Defaults" alongside the user
 * overrides list.
 */
export const SPACE_THEME_PALETTES: Array<{
  space: SpaceName;
  theme: DashboardTheme;
  description: string;
}> = [
  { space: "ops", theme: defaultTheme, description: "Ops default — classic Hermes teal, terminal feel" },
  { space: "noc", theme: midnightTheme, description: "NOC default — midnight + amber glow" },
];
