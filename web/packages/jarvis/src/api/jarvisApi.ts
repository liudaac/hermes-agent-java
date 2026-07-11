/**
 * jarvisApi — typed client for the Jarvis backend endpoints.
 *
 * Endpoints (all under /api/jarvis/*):
 *   - POST /chat            — send a user message, get a Jarvis reply
 *   - POST /intent          — classify user input into portal/ops/noc/cross
 *   - GET  /stream          — SSE channel for proactive suggestions (v1)
 *   - POST /approval/:id    — record a dangerous-op approval (gate pass)
 *
 * The session token is read from the same `window.__HERMES_SESSION_TOKEN__`
 * that the dashboard server injects. (Same pattern as the ops/portal/noc
 * api clients.)
 */

export type IntentName = "portal" | "ops" | "noc" | "cross";

export interface ChatRequest {
  message: string;
  context?: {
    space?: "portal" | "ops" | "noc";
    workspaceId?: string;
    activeResource?: { kind: string; id: string; label?: string };
  };
  /** Conversation history (most recent first). */
  history?: Array<{ role: "user" | "jarvis"; text: string }>;
}

export interface ChatResponse {
  reply: string;
  /** Optional tool calls the backend decided to make on the user's behalf. */
  toolCalls?: Array<{
    name: string;
    args: Record<string, unknown>;
    status: "pending" | "ok" | "error";
    result?: unknown;
  }>;
  /** Optional cross-space link suggestion. */
  crossSpaceLink?: { to: string; label: string };
  /** If the reply requires user approval, the gate info. */
  approval?: {
    approvalId: string;
    title: string;
    risk: "low" | "medium" | "high";
  };
}

export interface IntentResult {
  intent: IntentName;
  confidence: number;
  source: "heuristic" | "prompt" | "fallback";
}

declare global {
  interface Window {
    __HERMES_SESSION_TOKEN__?: string;
    /** Short-lived (10-min) signed token for SSE query-param auth; refreshed on page load. */
    __HERMES_SSE_TOKEN__?: string;
  }
}

function authHeaders(): HeadersInit {
  const h: Record<string, string> = { "Content-Type": "application/json" };
  const t = window.__HERMES_SESSION_TOKEN__;
  if (t) h.Authorization = `Bearer ${t}`;
  return h;
}

/**
 * Return a usable SSE auth token, preferring the short-lived signed token injected
 * at page load. Falls back to the permanent session token for backward compatibility.
 */
function sseToken(): string | undefined {
  return window.__HERMES_SSE_TOKEN__ || window.__HERMES_SESSION_TOKEN__;
}

/**
 * Refresh the short-lived SSE token from /api/auth/sse-token.
 * Called on page load and every 9 minutes (token TTL is 10 minutes).
 * Silently falls back to the permanent session token on failure.
 */
let refreshTimer: ReturnType<typeof setInterval> | null = null;
async function refreshSseToken(): Promise<void> {
  try {
    const res = await fetch("/api/auth/sse-token", {
      headers: authHeaders(),
    });
    if (!res.ok) return;
    const data = (await res.json()) as { token: string; expires_in: number };
    window.__HERMES_SSE_TOKEN__ = data.token;
  } catch {
    // offline / auth issue — keep using previous or fallback
  }
}

function startSseTokenRefresh(): void {
  if (refreshTimer) return;
  // Refresh every 9 minutes (540s) to stay under the 10-minute TTL with headroom.
  refreshTimer = setInterval(refreshSseToken, 9 * 60 * 1000);
  // Also refresh a moment after page load to get a fresh token (the injected one is
  // from page render, may already be a few seconds old).
  setTimeout(refreshSseToken, 2000);
}

// Kick off token refresh as soon as this module loads.
if (typeof window !== "undefined") {
  startSseTokenRefresh();
}

async function jsonFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { ...authHeaders(), ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${text}`);
  }
  if (res.status === 204) return {} as T;
  return (await res.json()) as T;
}

export const jarvisApi = {
  chat: (req: ChatRequest) =>
    jsonFetch<ChatResponse>("/api/jarvis/chat", {
      method: "POST",
      body: JSON.stringify(req),
    }),

  classifyIntent: (input: string) =>
    jsonFetch<IntentResult>("/api/jarvis/intent", {
      method: "POST",
      body: JSON.stringify({ input }),
    }),

  resolveApproval: (approvalId: string, decision: "approve" | "reject") =>
    jsonFetch<{ ok: boolean; approved: boolean; reply?: string; decision?: string }>(`/api/jarvis/approval/${encodeURIComponent(approvalId)}`, {
      method: "POST",
      body: JSON.stringify({ decision }),
    }),

  /**
   * SSE stream of proactive suggestions. Returns the EventSource.
   *
   * Pass `workspaceId` to receive only events for that workspace.
   * Pass `all: true` for an admin/super-user view that crosses
   * workspaces (only used by ops / noc). Pass neither and the
   * connection is a no-op (defense in depth — the server delivers
   * nothing to unscoped clients).
   */
  openSuggestionStream(
    onSuggestion: (s: Suggestion) => void,
    opts: { workspaceId?: string; all?: boolean } = {}
  ): EventSource {
    const buildUrl = () => {
      const params = new URLSearchParams();
      const t = sseToken();
      if (t) params.set("token", t);
      if (opts.workspaceId) params.set("workspaceId", opts.workspaceId);
      if (opts.all) params.set("all", "true");
      const qs = params.toString();
      return qs ? `/api/jarvis/stream?${qs}` : "/api/jarvis/stream";
    };

    // Proxy EventSource that can transparently reconnect on auth failure.
    // The returned object mirrors EventSource's close()/addEventListener/readyState/url
    // so callers don't see the swap.
    let es = new EventSource(buildUrl());
    let reconnectAttempts = 0;
    const listeners: Array<{ type: string; handler: EventListener }> = [];
    const wire = (source: EventSource) => {
      source.addEventListener("suggestion", (e) => {
        try {
          const s = JSON.parse((e as MessageEvent).data) as Suggestion;
          onSuggestion(s);
        } catch {
          // ignore parse errors
        }
      });
      for (const { type, handler } of listeners) {
        source.addEventListener(type, handler);
      }
      source.addEventListener("error", async () => {
        if (source.readyState === EventSource.CLOSED) return;
        if (reconnectAttempts >= 3) {
          source.close();
          return;
        }
        reconnectAttempts++;
        source.close();
        await refreshSseToken();
        es = new EventSource(buildUrl());
        wire(es);
      });
    };
    wire(es);
    return {
      get url() { return es.url; },
      get readyState() { return es.readyState; },
      get withCredentials() { return es.withCredentials; },
      close: () => es.close(),
      addEventListener: (type: string, handler: EventListener) => {
        listeners.push({ type, handler });
        es.addEventListener(type, handler);
      },
      removeEventListener: (type: string, handler: EventListener) => {
        const i = listeners.findIndex((l) => l.type === type && l.handler === handler);
        if (i >= 0) listeners.splice(i, 1);
        es.removeEventListener(type, handler);
      },
      dispatchEvent: () => false,
      onmessage: null,
      onerror: null,
      onopen: null,
      CONNECTING: EventSource.CONNECTING,
      OPEN: EventSource.OPEN,
      CLOSED: EventSource.CLOSED,
    } as unknown as EventSource;
  },
};

export interface Suggestion {
  id: string;
  title: string;
  body: string;
  /** Where the suggestion routes to when clicked. */
  linkTo?: string;
  /** Severity for the right-rail dot color. */
  severity: "info" | "warning" | "critical";
  createdAt: string;
}
