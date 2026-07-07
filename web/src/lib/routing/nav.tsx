/**
 * Three-space navigation: Portal / Ops / NOC.
 *
 * Aligned with the front-end refactor: each space has its own focused nav.
 * - Portal:  7 business objects, single row, no categories
 * - Ops:     operations / observability / configuration groups
 * - NOC:     6 governance items, single row, dark alert feel
 *
 * All paths use space-scoped URLs (see ./spaces.ts). Old top-level paths
 * are auto-redirected by the App-level effect.
 */

import {
  Activity,
  AlertOctagon,
  AlertTriangle,
  BarChart3,
  Building2,
  Clock,
  FileText,
  GitBranch,
  Hand,
  KeyRound,
  MessageSquare,
  Network,
  Package,
  Settings,
  Terminal,
  Timer,
  TrendingUp,
  Wrench,
  type LucideIcon,
} from "lucide-react";

import { SPACE_PATHS, type SpaceName } from "./spaces";

/**
 * Standalone portal entry — set to a relative URL that the browser will
 * load as a full-page navigation, exiting the combined dashboard.
 * The portal SPA reads the rest of the path on its own.
 */
export const PORTAL_ENTRY = "/portal/index.html";

// ── Types ────────────────────────────────────────────────────────

export interface NavItem {
  path: string;
  label: string;
  labelKey?: string;
  icon: LucideIcon;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

// ── Ops nav (platform admins, grouped) ──────────────────────────
//
// Portal no longer lives inside the combined dashboard — it is a fully
// independent SPA at PORTAL_ENTRY. So the dashboard only renders Ops + NOC.

export const OPS_NAV: NavGroup[] = [
  {
    label: "Operations",
    items: [
      { path: SPACE_PATHS.ops, label: "Overview", labelKey: "opsOverview", icon: Activity },
      { path: `${SPACE_PATHS.ops}/tenants`, label: "Tenants", labelKey: "tenants", icon: Building2 },
      { path: `${SPACE_PATHS.ops}/skills`, label: "Skills", labelKey: "skills", icon: Package },
      { path: `${SPACE_PATHS.ops}/cron`, label: "Cron", labelKey: "cron", icon: Clock },
      { path: `${SPACE_PATHS.ops}/tools`, label: "Tools", labelKey: "tools", icon: Wrench },
    ],
  },
  {
    label: "Observability",
    items: [
      { path: `${SPACE_PATHS.ops}/sessions`, label: "Sessions", labelKey: "sessions", icon: MessageSquare },
      { path: `${SPACE_PATHS.ops}/logs`, label: "Logs", labelKey: "logs", icon: FileText },
      { path: `${SPACE_PATHS.ops}/analytics`, label: "Analytics", labelKey: "analytics", icon: BarChart3 },
      { path: `${SPACE_PATHS.noc}/sla`, label: "SLA", labelKey: "sla", icon: Timer },
    ],
  },
  {
    label: "Configuration",
    items: [
      { path: `${SPACE_PATHS.ops}/config`, label: "Config", labelKey: "config", icon: Settings },
      { path: `${SPACE_PATHS.ops}/env`, label: "Keys", labelKey: "keys", icon: KeyRound },
      { path: `${SPACE_PATHS.ops}/playground`, label: "Playground", labelKey: "playground", icon: Terminal },
      { path: `${SPACE_PATHS.ops}/compare`, label: "Compare", labelKey: "compare", icon: GitBranch },
    ],
  },
];

// Flattened list for places that want a single array (legacy nav, search)
export const OPS_NAV_FLAT: NavItem[] = OPS_NAV.flatMap((g) => g.items);

// ── NOC nav (governance, single row) ────────────────────────────
//
// Items that live in the standalone portal SPA use PORTAL_ENTRY so the
// browser exits the combined dashboard (full-page navigation, true isolation).

export const NOC_NAV: NavItem[] = [
  { path: SPACE_PATHS.noc, label: "Agents", labelKey: "nocAgents", icon: Network },
  { path: `${SPACE_PATHS.noc}/traces`, label: "Traces", labelKey: "nocTraces", icon: GitBranch },
  { path: `${SPACE_PATHS.noc}/dlq`, label: "DLQ", labelKey: "nocDlq", icon: AlertOctagon },
  { path: `${SPACE_PATHS.noc}/hitl`, label: "Human Loop", labelKey: "nocHitl", icon: Hand },
  { path: `${PORTAL_ENTRY}/risk-policy`, label: "Risk", labelKey: "nocRisk", icon: AlertTriangle },
  { path: `${PORTAL_ENTRY}/evolution`, label: "Evolution", labelKey: "nocEvolution", icon: TrendingUp },
];

// ── Loaders ──────────────────────────────────────────────────────

export interface NavPayload {
  groups: NavGroup[];   // for grouped (Ops)
  flat: NavItem[];      // for flat (Portal, NOC)
}

/**
 * Get the nav structure for a given space.
 * The combined dashboard only renders Ops and NOC. Portal is a separate
 * SPA with its own nav inside it.
 */
export function getNavForSpace(space: SpaceName): NavPayload {
  switch (space) {
    case "ops":
      return { groups: OPS_NAV, flat: OPS_NAV_FLAT };
    case "noc":
      return { groups: [{ label: "", items: NOC_NAV }], flat: NOC_NAV };
  }
}

/**
 * Determine which space a path belongs to. The combined dashboard only
 * owns Ops and NOC paths; portal paths belong to the standalone SPA.
 */
export function pathToSpace(pathname: string): SpaceName | null {
  if (pathname === SPACE_PATHS.ops || pathname.startsWith(`${SPACE_PATHS.ops}/`)) return "ops";
  if (pathname === SPACE_PATHS.noc || pathname.startsWith(`${SPACE_PATHS.noc}/`)) return "noc";
  return null;
}
