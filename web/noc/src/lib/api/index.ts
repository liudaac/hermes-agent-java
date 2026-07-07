/**
 * NOC API surface. Composes the noc-specific APIs (and a few ops
 * helpers reused by the control center).
 */

import { nocApi } from "./noc";
import { opsApi } from "./ops";
import { opsTenantApi } from "./ops-tenant";

export { nocApi, opsApi, opsTenantApi };
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
// Composes all NOC-relevant APIs into a single flat object.
export const api = {
  ...opsApi,
  ...opsTenantApi,
  ...nocApi,
};
