/**
 * Public API surface for the dashboard.
 *
 * Three-space refactor (Portal / Ops / NOC). Each space has its own
 * focused API object; this module composes them back into a single
 * `api` object for backward compatibility with existing imports
 * (`import { api } from "@/lib/api"`).
 *
 * Migration: prefer importing from the scoped modules for new code.
 *
 *   import { portalApi } from "@/lib/api/portal";
 *   import { opsApi } from "@/lib/api/ops";
 *   import { nocApi } from "@/lib/api/noc";
 *   import { openLogTail, openBusinessRunStream, ... } from "@/lib/api/sse";
 */

import { opsApi } from "./ops";
import { opsTenantApi } from "./ops-tenant";
import { portalApi } from "./portal";
import { nocApi } from "./noc";

export { opsApi, opsTenantApi, portalApi, nocApi };
export * from "./_base";
export {
  bindEventSource,
  readChunkedSSE,
  openLogTail,
  openCronRunStream,
  openBusinessRunStream,
  openBusinessEventStream,
} from "./sse";
export type { BusinessEvent } from "./sse";

// Re-export common types for downstream convenience
export type * from "./types/common";
export type * from "./types/ops";
export type * from "./types/portal";
export type * from "./types/noc";
export type * from "./types/orchestration";
export type * from "./types/templates";

// ── Legacy `api` object ──────────────────────────────────────────
// Composes all three space APIs into a single flat object for
// `import { api } from "@/lib/api"`. New code should use the
// space-scoped APIs directly.
export const api = {
  ...opsApi,
  ...opsTenantApi,
  ...portalApi,
  ...nocApi,
};
