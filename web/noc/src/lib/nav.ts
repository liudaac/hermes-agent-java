/**
 * NOC nav — flat routes inside the noc SPA. No cross-space references.
 * Cross-product entries (Portal, Ops) are external and rendered as <a>
 * tags so the browser exits the noc SPA.
 */

import {
  AlertOctagon,
  Hand,
  Network,
  Timer,
  Workflow,
  type LucideIcon,
} from "lucide-react";

export interface NocNavItem {
  path: string;
  label: string;
  icon: LucideIcon;
}

export const NOC_NAV: NocNavItem[] = [
  { path: "/", label: "Agents", icon: Network },
  { path: "/workflows", label: "Workflows", icon: Workflow },
  { path: "/sla", label: "SLA", icon: Timer },
  { path: "/dlq", label: "DLQ", icon: AlertOctagon },
  { path: "/hitl", label: "Human Loop", icon: Hand },
];

/** Cross-product entry points. */
export const CROSS_PRODUCT_LINKS = {
  portal: "/portal/index.html",
  ops: "/ops/index.html",
} as const;
