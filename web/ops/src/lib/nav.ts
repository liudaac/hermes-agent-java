/**
 * Ops nav - flat routes. NOC merged in. Three-layer admin integrated.
 */

import {
  Activity, Building2, Clock, FileText, GitBranch, KeyRound,
  MessageSquare, Settings, Terminal, BarChart3,
  AlertOctagon, Workflow, Hand, type LucideIcon,
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
  { path: "/spaces", label: "Spaces", group: "operations", icon: Building2 },
  { path: "/cron", label: "Cron", group: "operations", icon: Clock },
  { path: "/tools", label: "Tools", group: "operations", icon: Settings },
  { path: "/dlq", label: "DLQ", group: "operations", icon: AlertOctagon },
  { path: "/workflows", label: "Workflows", group: "operations", icon: Workflow },
  { path: "/hitl", label: "Human Loop", group: "operations", icon: Hand },
  // Observability
  { path: "/sessions", label: "Sessions", group: "observability", icon: MessageSquare },
  { path: "/logs", label: "Logs", group: "observability", icon: FileText },
  { path: "/analytics", label: "Analytics", group: "observability", icon: BarChart3 },
  // Configuration
  { path: "/config", label: "Config", group: "configuration", icon: Settings },
  { path: "/env", label: "Keys", group: "configuration", icon: KeyRound },
  { path: "/playground", label: "Playground", group: "configuration", icon: Terminal },
  { path: "/compare", label: "Compare", group: "configuration", icon: GitBranch },
];

/** Cross-product entry points. */
export const CROSS_PRODUCT_LINKS = {
  portal: "/portal/index.html",
} as const;
