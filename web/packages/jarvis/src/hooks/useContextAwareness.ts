/**
 * useContextAwareness — "Jarvis 永远知道的事情" (design.md §11.1).
 *
 * Resolves:
 *   - current space (portal / ops / noc) from `pathname`
 *   - current workspaceId from URL or localStorage
 *   - current user (session token; identity decode done server-side)
 *   - recently-active resources (e.g. last viewed team / run / approval)
 *
 * Result is exposed as a stable object so consumers can re-render only
 * when context actually changes. The hook also subscribes to route
 * changes via `useLocation` so cross-space navigation triggers
 * awareness updates in real time.
 */

import { useEffect, useMemo, useState } from "react";
import { useLocation } from "react-router-dom";

export type SpaceName = "portal" | "ops" | "noc" | "hub" | "unknown";

export interface ActiveContext {
  space: SpaceName;
  workspaceId?: string;
  userId?: string;
  activeResource?: {
    kind: "team" | "run" | "approval" | "trace" | "template";
    id: string;
    label?: string;
  };
  pathname: string;
}

function pathToSpace(pathname: string): SpaceName {
  if (pathname === "/" || pathname === "") return "hub";
  if (pathname.startsWith("/portal") || pathname.startsWith("/teams") ||
      pathname.startsWith("/templates") || pathname.startsWith("/approvals") ||
      pathname.startsWith("/runs") || pathname.startsWith("/insights")) {
    return "portal";
  }
  if (pathname.startsWith("/ops") || pathname.startsWith("/status") ||
      pathname.startsWith("/playground") || pathname.startsWith("/sessions") ||
      pathname.startsWith("/logs") || pathname.startsWith("/cron") ||
      pathname.startsWith("/skills") || pathname.startsWith("/tools") ||
      pathname.startsWith("/tenants") || pathname.startsWith("/config") ||
      pathname.startsWith("/env") || pathname.startsWith("/org")) {
    return "ops";
  }
  if (pathname.startsWith("/noc") || pathname.startsWith("/workflows") ||
      pathname.startsWith("/sla") || pathname.startsWith("/dlq") ||
      pathname.startsWith("/hitl") || pathname.startsWith("/traces")) {
    return "noc";
  }
  return "unknown";
}

const ACTIVE_RESOURCE_KEY = "hermes-jarvis-active-resource";

export function useContextAwareness(): ActiveContext {
  const location = useLocation();
  const [activeResource, setActiveResource] =
    useState<ActiveContext["activeResource"]>(undefined);

  // Read once on mount + on each route change.
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const raw = window.sessionStorage.getItem(ACTIVE_RESOURCE_KEY);
      if (raw) setActiveResource(JSON.parse(raw));
    } catch {
      // ignore
    }
  }, [location.pathname]);

  return useMemo<ActiveContext>(() => {
    const space = pathToSpace(location.pathname);
    const params = new URLSearchParams(location.search);
    const workspaceId =
      params.get("workspaceId") ??
      window.localStorage.getItem("hermes.activeWorkspaceId") ??
      undefined;
    return {
      space,
      workspaceId: workspaceId ?? undefined,
      activeResource,
      pathname: location.pathname,
    };
  }, [location.pathname, location.search, activeResource]);
}

/** Set the most recently active resource (e.g. when a user opens a team). */
export function setActiveResource(
  res: NonNullable<ActiveContext["activeResource"]>,
): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(ACTIVE_RESOURCE_KEY, JSON.stringify(res));
  } catch {
    // ignore
  }
}
