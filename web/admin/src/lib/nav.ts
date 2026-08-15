/**
 * Admin nav - organization governance domain.
 *
 * Three groups:
 *  - governance : org overview, tenants, spaces, users
 *  - control    : approvals, delegation, evolution
 *  - billing    : cost, audit, model routing
 */

import {
  Building2, Users, Settings, Shield, DollarSign,
  FileClock, GitBranch, Scale, Target, Layers,
  type LucideIcon,
} from "lucide-react";

export interface AdminNavItem {
  path: string;
  label: string;
  group: "governance" | "control" | "billing";
  icon: LucideIcon;
}

export const ADMIN_NAV: AdminNavItem[] = [
  // Governance
  { path: "/", label: "组织总览", group: "governance", icon: Building2 },
  { path: "/tenants", label: "租户管理", group: "governance", icon: Layers },
  { path: "/spaces", label: "空间管理", group: "governance", icon: Building2 },
  { path: "/users", label: "用户管理", group: "governance", icon: Users },
  // Control
  { path: "/approvals", label: "审批策略", group: "control", icon: Shield },
  { path: "/delegation", label: "委派任务", group: "control", icon: Target },
  { path: "/evolution", label: "进化中心", group: "control", icon: GitBranch },
  { path: "/compare", label: "比较分析", group: "control", icon: Scale },
  // Billing
  { path: "/billing", label: "计费", group: "billing", icon: DollarSign },
  { path: "/audit", label: "审计日志", group: "billing", icon: FileClock },
  { path: "/models", label: "模型路由", group: "billing", icon: Settings },
];

export const CROSS_PRODUCT_LINKS = {
  portal: "/portal/index.html",
  ops: "/ops/index.html",
  devportal: "/devportal/index.html",
} as const;
