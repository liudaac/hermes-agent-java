/**
 * Shared SSE primitives for @hermes/ui.
 *
 * Zero external deps (browser globals + React types only). Both
 * portal/ops/noc consume these via the Vite `@hermes/ui` alias.
 *
 *   - parseSseData     : safe JSON.parse
 *   - bindEventSource  : wire a native EventSource to named-event handlers
 *   - readChunkedSSE   : consume fetch+ReadableStream SSE (POST-based streams)
 */

export function parseSseData(raw: unknown): unknown {
  if (typeof raw !== "string") return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return { raw };
  }
}

/**
 * Listen to a native EventSource and dispatch named events.
 *
 * @param es        an open EventSource
 * @param handlers  map of event name → handler; "message" / "default" catch unnamed events
 */
export function bindEventSource(
  es: EventSource,
  handlers: Record<string, (data: unknown) => void>,
): void {
  es.onmessage = (e) => {
    const data = parseSseData(e.data);
    (handlers.message ?? handlers.default)?.(data);
  };
  for (const [name, handler] of Object.entries(handlers)) {
    if (name === "message" || name === "default") continue;
    es.addEventListener(name, (e: MessageEvent) => {
      handler(parseSseData(e.data));
    });
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
