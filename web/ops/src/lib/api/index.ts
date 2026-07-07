/**
 * Ops API surface. Composes the ops-specific APIs into a single
 * `api` object for `import { api } from "@/lib/api"`.
 *
 * Note: this is the ops SPA. The Compare/Chat playground endpoints
 * (chatStream, getCompareRun, ...) are also used from the ops console
 * because ops admins need to compare tenants and try the chat runtime
 * directly. We re-export nocApi here for that reason.
 *
 * Cross-product operations happen via full-page nav to
 * /portal/index.html or /noc/index.html.
 */

import { opsApi } from "./ops";
import { opsTenantApi } from "./ops-tenant";
import { nocApi } from "./noc";

export { opsApi, opsTenantApi, nocApi };
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

export type * from "./types/common";
export type * from "./types/ops";
export type * from "./types/orchestration";
export type * from "./types/noc";

// ── Legacy `api` object ──────────────────────────────────────────
// Composes all ops-relevant APIs into a single flat object for
// `import { api } from "@/lib/api"`. The Compare/Chat playground
// methods (chatStream, getCompareRun, ...) live in nocApi but are
// used by ops pages.
export const api = {
  ...opsApi,
  ...opsTenantApi,
  ...nocApi,
};
