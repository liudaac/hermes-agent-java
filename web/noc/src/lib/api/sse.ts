/**
 * SSE (Server-Sent Events) stream utilities.
 *
 * The dashboard uses EventSource for one-way streams (log tail, cron runs,
 * compare runs) and fetch+ReadableStream for chunked SSE (chat playground,
 * business run stream).
 *
 * This module centralizes the parsing logic and re-exports thin wrappers
 * for each stream.
 */

import { getSessionToken } from "./_base";

/**
 * Listen to a raw EventSource and dispatch named events.
 *
 * @param es  an open EventSource
 * @param handlers  map of event name → handler; "message" is the default
 */
export function bindEventSource(
  es: EventSource,
  handlers: Record<string, (data: unknown) => void>,
): void {
  es.onmessage = (e) => {
    const data = parseData(e.data);
    (handlers.message ?? handlers.default)?.(data);
  };
  for (const [name, handler] of Object.entries(handlers)) {
    if (name === "message" || name === "default") continue;
    es.addEventListener(name, (e: MessageEvent) => {
      handler(parseData(e.data));
    });
  }
}

function parseData(raw: unknown): unknown {
  if (typeof raw !== "string") return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return { raw };
  }
}

/**
 * Read a chunked SSE response (fetch + ReadableStream) and dispatch named
 * events via the callback. Used for chat playground and any POST-based SSE.
 */
export async function readChunkedSSE(
  response: Response,
  onEvent: (event: string, data: unknown) => void,
  onError?: (err: Error) => void,
): Promise<void> {
  if (!response.body) {
    onError?.(new Error("Response has no body"));
    return;
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let currentEvent = "message";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (line.startsWith("event:")) {
          currentEvent = line.slice(6).trim();
          continue;
        }
        if (line.startsWith("data:")) {
          const raw = line.slice(5).trim();
          if (raw === "[DONE]") {
            onEvent("done", {});
            continue;
          }
          try {
            onEvent(currentEvent, JSON.parse(raw));
          } catch {
            onEvent(currentEvent, raw);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e : new Error(String(e)));
  } finally {
    reader.releaseLock();
  }
}

// ── Stream factories ─────────────────────────────────────────────

/** Open a log tail EventSource. */
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

/** Open a cron run stream. */
export async function openCronRunStream(id: string): Promise<EventSource> {
  const token = await getSessionToken();
  return new EventSource(
    `/api/cron/jobs/${encodeURIComponent(id)}/runs/stream?token=${encodeURIComponent(token)}`,
  );
}

/** Open a business run stream. */
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

/** Open a business event stream. */
export interface BusinessEvent {
  type: string;
  workspaceId: string;
  payload: Record<string, unknown>;
  timestamp: string;
}

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
