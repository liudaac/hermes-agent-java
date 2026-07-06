/**
 * Top-level route redirects.
 *
 * - `/`             → `/portal` (or the space indicated by ?space=)
 * - Old paths       → new space-scoped paths (see spaces.ts)
 * - `*`             → catch-all → `/portal`
 *
 * This component renders nothing — it only emits <Navigate> redirects.
 */

import { Navigate, useLocation } from "react-router-dom";
import { migrateOldPath, resolveDefaultSpace, SPACE_PATHS } from "./spaces";

export function RootRedirect() {
  const loc = useLocation();
  const space = resolveDefaultSpace();
  return <Navigate to={SPACE_PATHS[space]} replace state={{ from: loc.pathname }} />;
}

export function LegacyRedirect() {
  const loc = useLocation();
  const target = migrateOldPath(loc.pathname);
  if (target) {
    return <Navigate to={target} replace />;
  }
  // No mapping — fall through to 404 / default
  return <Navigate to="/" replace />;
}
