/**
 * intentRoutes — maps classified intents to the right tool calls.
 *
 * Per design.md §11.3 + §13, Jarvis routes the user's question to the
 * right product API:
 *   - portal → portalApi  (teams / runs / approvals / templates)
 *   - ops    → opsApi     (tenants / skills / sessions / logs)
 *   - noc    → nocApi     (traces / workflow / DLQ / SLA)
 *   - cross  → orchestrate across all three
 *
 * The route table is consumed by `useIntentRouter`. The actual
 * dispatching happens in `overlay/ConversationFlow.tsx` which owns
 * the typed API clients.
 */

import type { IntentName } from "./jarvisApi";

export interface RouteTarget {
  product: IntentName;
  /** Best-effort guess at the resource kind, e.g. "approvals". */
  resourceKind?:
    | "teams"
    | "approvals"
    | "templates"
    | "runs"
    | "tenants"
    | "skills"
    | "logs"
    | "traces"
    | "dlq"
    | "sla"
    | "workflows";
  /** Free-form reason — surfaced to the user as "why". */
  rationale: string;
}

export function routeForIntent(intent: IntentName): RouteTarget {
  switch (intent) {
    case "portal":
      return { product: "portal", resourceKind: "teams", rationale: "数字员工/审批/场景 → Portal" };
    case "ops":
      return { product: "ops", resourceKind: "tenants", rationale: "租户/技能/日志 → Ops" };
    case "noc":
      return { product: "noc", resourceKind: "traces", rationale: "告警/追踪/治理 → NOC" };
    case "cross":
    default:
      return { product: "cross", rationale: "跨空间协调" };
  }
}
