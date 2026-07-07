/**
 * Ops nav — flat routes inside the ops SPA. No cross-space references.
 * SLA is an ops responsibility (analytics, p95/p99) and lives here.
 * Items pointing to /portal/index.html or /noc/index.html are external
 * entries rendered as plain <a> tags so the browser exits the SPA.
 */

import {
  Activity,
  Building2,
  Clock,
  FileText,
  GitBranch,
  KeyRound,
  MessageSquare,
  Package,
  Settings,
  Terminal,
  Timer,
  Wrench,
  BarChart3,
  type LucideIcon,
} from "lucide-react";

export interface OpsNavItem {
  path: string;
  label: string;
  group: "operations" | "observability" | "configuration";
  icon: LucideIcon;
}

export const OPS_NAV: OpsNavItem[] = [
  // Operations
  { path: "/", label: "Overview", group: "operations", icon: Activity },
  { path: "/tenants", label: "Tenants", group: "operations", icon: Building2 },
  { path: "/skills", label: "Skills", group: "operations", icon: Package },
  { path: "/cron", label: "Cron", group: "operations", icon: Clock },
  { path: "/tools", label: "Tools", group: "operations", icon: Wrench },
  // Observability
  { path: "/sessions", label: "Sessions", group: "observability", icon: MessageSquare },
  { path: "/logs", label: "Logs", group: "observability", icon: FileText },
  { path: "/analytics", label: "Analytics", group: "observability", icon: BarChart3 },
  { path: "/sla", label: "SLA", group: "observability", icon: Timer },
  // Configuration
  { path: "/config", label: "Config", group: "configuration", icon: Settings },
  { path: "/env", label: "Keys", group: "configuration", icon: KeyRound },
  { path: "/playground", label: "Playground", group: "configuration", icon: Terminal },
  { path: "/compare", label: "Compare", group: "configuration", icon: GitBranch },
];

/** Cross-product entry points (rendered as full-page nav in the header). */
export const CROSS_PRODUCT_LINKS = {
  portal: "/portal/index.html",
  noc: "/noc/index.html",
} as const;
