/**
 * Ops nav - pure operations & observability.
 *
 * Admin pages (tenants, spaces, users, config, org) moved to Admin SPA.
 * Dev pages (env, oauth, webhooks) moved to DevPortal SPA.
 */

import {
  Activity, Clock, Settings, AlertOctagon, Workflow, Hand,
  MessageSquare, FileText, BarChart3, Terminal, Globe,
  type LucideIcon,
} from "lucide-react";

export interface OpsNavItem {
  path: string;
  label: string;
  group: "operations" | "observability" | "tools";
  icon: LucideIcon;
}

export const OPS_NAV: OpsNavItem[] = [
  // Operations
  { path: "/", label: "总览", group: "operations", icon: Activity },
  { path: "/sessions", label: "会话", group: "operations", icon: MessageSquare },
  { path: "/cron", label: "定时任务", group: "operations", icon: Clock },
  { path: "/dlq", label: "DLQ", group: "operations", icon: AlertOctagon },
  { path: "/workflows", label: "工作流", group: "operations", icon: Workflow },
  { path: "/hitl", label: "人机协作", group: "operations", icon: Hand },
  // Observability
  { path: "/logs", label: "日志", group: "observability", icon: FileText },
  { path: "/analytics", label: "分析", group: "observability", icon: BarChart3 },
  // Tools
  { path: "/tools", label: "工具", group: "tools", icon: Settings },
  { path: "/playground", label: "Playground", group: "tools", icon: Terminal },
  { path: "/compare", label: "比较", group: "tools", icon: Globe },
];

/** Cross-product entry points. */
export const CROSS_PRODUCT_LINKS = {
  portal: "/portal/index.html",
  admin: "/admin/index.html",
  devportal: "/devportal/index.html",
} as const;
