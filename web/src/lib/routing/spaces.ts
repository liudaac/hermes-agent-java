/**
 * Three-space route redirection.
 *
 * Aligned with the front-end refactor: split routes into Portal / Ops / NOC
 * while keeping old URLs working via redirects. New code should write routes
 * under `/portal/`, `/ops/`, or `/noc/` and import this module for the
 * canonical space-to-path mapping.
 *
 * Old path           → new path
 * /                  → /portal           (default; respects ?space= and firstTime)
 * /status            → /ops
 * /playground        → /ops/playground
 * /compare           → /ops/compare
 * /sessions          → /ops/sessions
 * /analytics         → /ops/analytics
 * /logs              → /ops/logs
 * /cron              → /ops/cron
 * /skills            → /ops/skills
 * /tools             → /ops/tools
 * /tenants           → /ops/tenants
 * /config            → /ops/config
 * /env               → /ops/env
 *
 * /org               → /ops/org
 * /org-control       → /noc
 * /workflows         → /noc/workflows
 * /sla               → /noc/sla
 * /dlq               → /noc/dlq
 * /hitl              → /noc/hitl
 * /traces/:id        → /noc/traces/:id
 *
 * /business          → /portal           (was /business-portal)
 * /business-portal/* → /portal/*
 * /runs/:ws/:id      → /portal/runs/:ws/:id
 */

export const SPACE_PATHS = {
  portal: "/portal",
  ops: "/ops",
  noc: "/noc",
} as const;

export type SpaceName = keyof typeof SPACE_PATHS;

/**
 * Resolve the user's current space from URL/params/localStorage.
 * Returns "portal" by default (business user first).
 */
export function resolveDefaultSpace(): SpaceName {
  if (typeof window === "undefined") return "portal";

  const params = new URLSearchParams(window.location.search);
  const explicit = params.get("space");
  if (explicit === "portal" || explicit === "ops" || explicit === "noc") {
    return explicit;
  }

  if (params.get("firstTime") === "1") return "portal";

  const last = window.localStorage.getItem("hermes.lastSpace");
  if (last === "portal" || last === "ops" || last === "noc") return last;

  return "portal";
}

/**
 * Persist the active space so refreshes return the user to where they were.
 */
export function rememberSpace(space: SpaceName): void {
  try {
    window.localStorage.setItem("hermes.lastSpace", space);
  } catch {
    // ignore (e.g. private mode)
  }
}

/**
 * Map an old-style top-level path to its new space-scoped path.
 * Returns null for paths already under /portal, /ops, or /noc.
 */
export function migrateOldPath(pathname: string): string | null {
  if (
    pathname.startsWith("/portal/") ||
    pathname === "/portal" ||
    pathname.startsWith("/ops/") ||
    pathname === "/ops" ||
    pathname.startsWith("/noc/") ||
    pathname === "/noc"
  ) {
    return null;
  }

  // /business-portal/* → /portal/*
  if (pathname === "/business-portal") return "/portal";
  if (pathname.startsWith("/business-portal/")) {
    return `/portal${pathname.slice("/business-portal".length)}`;
  }

  // /business → /portal
  if (pathname === "/business") return "/portal";

  // /runs/:ws/:id → /portal/runs/:ws/:id
  const runsMatch = pathname.match(/^\/runs\/([^/]+)\/([^/]+)$/);
  if (runsMatch) return `/portal/runs/${runsMatch[1]}/${runsMatch[2]}`;

  // /traces/:id → /noc/traces/:id
  const tracesMatch = pathname.match(/^\/traces\/([^/]+)$/);
  if (tracesMatch) return `/noc/traces/${tracesMatch[1]}`;

  // Single-segment ops + noc pages
  const opsPages: Record<string, string> = {
    "/status": "/ops",
    "/playground": "/ops/playground",
    "/compare": "/ops/compare",
    "/sessions": "/ops/sessions",
    "/analytics": "/ops/analytics",
    "/logs": "/ops/logs",
    "/cron": "/ops/cron",
    "/skills": "/ops/skills",
    "/tools": "/ops/tools",
    "/tenants": "/ops/tenants",
    "/config": "/ops/config",
    "/env": "/ops/env",
    "/org": "/ops/org",
  };
  if (opsPages[pathname]) return opsPages[pathname];

  const nocPages: Record<string, string> = {
    "/org-control": "/noc",
    "/workflows": "/noc/workflows",
    "/sla": "/noc/sla",
    "/dlq": "/noc/dlq",
    "/hitl": "/noc/hitl",
  };
  if (nocPages[pathname]) return nocPages[pathname];

  return null;
}
