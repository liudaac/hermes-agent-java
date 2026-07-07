/**
 * Three-space route redirection.
 *
 * Aligned with the front-end refactor: split routes into Portal / Ops / NOC
 * while keeping old URLs working via redirects. New code should write routes
 * under `/portal/`, `/ops/`, or `/noc/` and import this module for the
 * canonical space-to-path mapping.
 *
 * The Portal space is now a fully independent SPA served from
 * `/portal/index.html` (portal/ in the repo). The combined dashboard here
 * hosts Ops + NOC only. Old `/portal/*` and `/business-portal/*` URLs in
 * this SPA are deep-link forwarded to the standalone portal entry.
 *
 * Old path           → new path
 * /                  → /ops               (default; respects ?space=)
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
 * /portal/*          → external /portal/index.html (independent SPA)
 * /business          → external /portal/
 * /business-portal/* → external /portal/<rest>
 * /runs/:ws/:id      → external /portal/runs/:ws/:id
 */

export const SPACE_PATHS = {
  ops: "/ops",
  noc: "/noc",
} as const;

export type SpaceName = keyof typeof SPACE_PATHS;

/**
 * External portal entry — served as a separate SPA at /portal/index.html.
 * When users land here on /portal/* from a stale link, we forward the
 * browser to the standalone portal so they get the H5 experience.
 */
export const PORTAL_ENTRY = "/portal/index.html";

/**
 * Resolve the user's current space from URL/params/localStorage.
 * Returns "ops" by default — the combined dashboard no longer hosts portal.
 */
export function resolveDefaultSpace(): SpaceName {
  if (typeof window === "undefined") return "ops";

  const params = new URLSearchParams(window.location.search);
  const explicit = params.get("space");
  if (explicit === "ops" || explicit === "noc") {
    return explicit;
  }

  const last = window.localStorage.getItem("hermes.lastSpace");
  if (last === "ops" || last === "noc") return last;

  return "ops";
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
 * Returns null for paths already under /ops or /noc, or when the path
 * is a portal path that should be handled by `forwardToExternalPortal`.
 */
export function migrateOldPath(pathname: string): string | null {
  if (
    pathname.startsWith("/ops/") ||
    pathname === "/ops" ||
    pathname.startsWith("/noc/") ||
    pathname === "/noc"
  ) {
    return null;
  }

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

/**
 * Forward the browser to the external standalone portal SPA.
 * Used for paths that no longer belong to the combined dashboard
 * (anything that was /portal/*, /business-portal/*, /business, or
 * /runs/:ws/:id).
 *
 * The portal SPA reads the rest of the path on its own, so we just
 * push the matching external URL preserving query/hash.
 */
export function forwardToExternalPortal(pathname: string): boolean {
  if (
    pathname === "/portal" ||
    pathname.startsWith("/portal/") ||
    pathname === "/business-portal" ||
    pathname.startsWith("/business-portal/") ||
    pathname === "/business"
  ) {
    const rest =
      pathname === "/portal" || pathname === "/business" || pathname === "/business-portal"
        ? ""
        : pathname.startsWith("/portal/")
          ? pathname.slice("/portal".length)
          : pathname.startsWith("/business-portal/")
            ? pathname.slice("/business-portal".length)
            : "";
    const suffix = window.location.search + window.location.hash;
    window.location.replace(`${PORTAL_ENTRY}${rest}${suffix}`);
    return true;
  }

  // /runs/:ws/:id → portal/runs/:ws/:id
  const runsMatch = pathname.match(/^\/runs\/([^/]+)\/([^/]+)$/);
  if (runsMatch) {
    const suffix = window.location.search + window.location.hash;
    window.location.replace(`${PORTAL_ENTRY}/runs/${runsMatch[1]}/${runsMatch[2]}${suffix}`);
    return true;
  }

  return false;
}
