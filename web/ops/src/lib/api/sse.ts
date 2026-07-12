/**
 * Ops-specific SSE stream factories.
 *
 * Generic primitives (bindEventSource / readChunkedSSE / parseSseData)
 * are re-exported from @hermes/ui. This file wires up endpoint URLs for
 * the Ops console pages.
 *
 * Factories that need custom addEventListener wiring (log tail, cron
 * run) return bare EventSource; callers attach their own listeners.
 * Factories that drive page state via onEvent callbacks (business run /
 * business event stream) pre-bind handlers for convenience.
 */

import { getSessionToken, bindEventSource } from "@hermes/ui";

// Re-export generics for callers that previously imported them from here.
export { bindEventSource } from "@hermes/ui";
export { readChunkedSSE, parseSseData } from "@hermes/ui";

// ── Stream factories ─────────────────────────────────────────────

/** Open a log tail EventSource. Caller attaches "ready" / "line" listeners. */
export async function openLogTail(params: {
  file: string;
  level?: string;
  component?: string;
}): Promise<EventSource> {
  const token = await getSessionToken();
  const qs = new URLSearchParams();
  qs.set("file", params.file);
  qs.set("token", token);
  if (params.level && params.level !== "ALL") qs.set("level", params.level);
  if (params.component && params.component !== "all") qs.set("component", params.component);
  return new EventSource(`/api/logs/tail?${qs.toString()}`);
}

/** Open a cron run stream. Caller attaches "run" listeners. */
export async function openCronRunStream(id: string): Promise<EventSource> {
  const token = await getSessionToken();
  return new EventSource(
    `/api/cron/jobs/${encodeURIComponent(id)}/runs/stream?token=${encodeURIComponent(token)}`,
  );
}

/** Open a business run stream with pre-bound named-event handlers. */
export function openBusinessRunStream(
  workspaceId: string,
  runId: string,
  onEvent: (event: string, data: Record<string, unknown>) => void,
  onError?: (err: Event) => void,
): EventSource {
  const es = new EventSource(
    `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/runs/${encodeURIComponent(runId)}/stream`,
  );
  bindEventSource(es, {
    message: (data) => onEvent("message", data as Record<string, unknown>),
    "run.state": (data) => onEvent("run.state", data as Record<string, unknown>),
    "run.started": (data) => onEvent("run.started", data as Record<string, unknown>),
    "step.started": (data) => onEvent("step.started", data as Record<string, unknown>),
    "step.completed": (data) => onEvent("step.completed", data as Record<string, unknown>),
    "step.failed": (data) => onEvent("step.failed", data as Record<string, unknown>),
    "run.completed": (data) => onEvent("run.completed", data as Record<string, unknown>),
    "run.failed": (data) => onEvent("run.failed", data as Record<string, unknown>),
  });
  if (onError) es.onerror = onError;
  return es;
}

/** Business event shape delivered by /api/v1/business/events/stream. */
export interface BusinessEvent {
  type: string;
  workspaceId: string;
  payload: Record<string, unknown>;
  timestamp: string;
}

/** Open legacy business-event stream with a pre-wired message handler. */
export function openBusinessEventStream(
  onEvent: (e: BusinessEvent) => void,
  onError?: (err: Event) => void,
): EventSource {
  const es = new EventSource("/api/v1/business/events/stream");
  bindEventSource(es, {
    message: (data) => onEvent(data as BusinessEvent),
  });
  if (onError) es.onerror = onError;
  return es;
}
