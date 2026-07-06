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
  Bot,
  BriefcaseBusiness,
  Building2,
  Clock,
  FileText,
  GitBranch,
  Hand,
  KeyRound,
  LayoutDashboard,
  MessageSquare,
  Network,
  Package,
  Settings,
  ShieldCheck,
  Sparkles,
  Terminal,
  Timer,
  TrendingUp,
  Wrench,
  type LucideIcon,
} from "lucide-react";

import { SPACE_PATHS, type SpaceName } from "./spaces";

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

// ── Portal nav (business users) ──────────────────────────────────

export const PORTAL_NAV: NavItem[] = [
  { path: SPACE_PATHS.portal, label: "Home", labelKey: "portalHome", icon: LayoutDashboard },
  { path: `${SPACE_PATHS.portal}/workspaces`, label: "Workspaces", labelKey: "portalWorkspaces", icon: BriefcaseBusiness },
  { path: `${SPACE_PATHS.portal}/agents`, label: "Teams", labelKey: "portalTeams", icon: Bot },
  { path: `${SPACE_PATHS.portal}/templates`, label: "Templates", labelKey: "portalTemplates", icon: Package },
  { path: `${SPACE_PATHS.portal}/approvals`, label: "Approvals", labelKey: "portalApprovals", icon: ShieldCheck },
  { path: `${SPACE_PATHS.portal}/industry-dashboard`, label: "Industry", labelKey: "portalIndustry", icon: BarChart3 },
  { path: `${SPACE_PATHS.portal}/evolution`, label: "Insights", labelKey: "portalInsights", icon: Sparkles },
];

// ── Ops nav (platform admins, grouped) ──────────────────────────

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

export const NOC_NAV: NavItem[] = [
  { path: SPACE_PATHS.noc, label: "Agents", labelKey: "nocAgents", icon: Network },
  { path: `${SPACE_PATHS.noc}/traces`, label: "Traces", labelKey: "nocTraces", icon: GitBranch },
  { path: `${SPACE_PATHS.noc}/dlq`, label: "DLQ", labelKey: "nocDlq", icon: AlertOctagon },
  { path: `${SPACE_PATHS.noc}/hitl`, label: "Human Loop", labelKey: "nocHitl", icon: Hand },
  { path: `${SPACE_PATHS.portal}/risk-policy`, label: "Risk", labelKey: "nocRisk", icon: AlertTriangle },
  { path: `${SPACE_PATHS.portal}/evolution`, label: "Evolution", labelKey: "nocEvolution", icon: TrendingUp },
];

// ── Loaders ──────────────────────────────────────────────────────

export interface NavPayload {
  groups: NavGroup[];   // for grouped (Ops)
  flat: NavItem[];      // for flat (Portal, NOC)
}

/**
 * Get the nav structure for a given space.
 */
export function getNavForSpace(space: SpaceName): NavPayload {
  switch (space) {
    case "portal":
      return { groups: [{ label: "", items: PORTAL_NAV }], flat: PORTAL_NAV };
    case "ops":
      return { groups: OPS_NAV, flat: OPS_NAV_FLAT };
    case "noc":
      return { groups: [{ label: "", items: NOC_NAV }], flat: NOC_NAV };
  }
}

/**
 * Determine which space a path belongs to.
 */
export function pathToSpace(pathname: string): SpaceName | null {
  if (pathname === SPACE_PATHS.portal || pathname.startsWith(`${SPACE_PATHS.portal}/`)) return "portal";
  if (pathname === SPACE_PATHS.ops || pathname.startsWith(`${SPACE_PATHS.ops}/`)) return "ops";
  if (pathname === SPACE_PATHS.noc || pathname.startsWith(`${SPACE_PATHS.noc}/`)) return "noc";
  return null;
}
