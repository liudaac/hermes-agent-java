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
/** Active SSE token scope, set by openSuggestionStream and used during refresh. */
let activeScope: { workspaceId?: string; all?: boolean } = {};

async function refreshSseToken(scope?: { workspaceId?: string; all?: boolean }): Promise<void> {
  try {
    const params = new URLSearchParams();
    const s = scope ?? activeScope;
    if (s.workspaceId) params.set("workspaceId", s.workspaceId);
    if (s.all) params.set("all", "true");
    const qs = params.toString();
    const url = "/api/auth/sse-token" + (qs ? `?${qs}` : "");
    const res = await fetch(url, { headers: authHeaders() });
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
  refreshTimer = setInterval(() => {
    void refreshSseToken();
  }, 9 * 60 * 1000);
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
   * SSE stream of proactive suggestions. Returns a closable proxy
   * EventSource.
   *
   * Scope is carried in a SIGNED SHORT-LIVED TOKEN fetched from
   * /api/auth/sse-token?workspaceId=xxx or ?all=true. The server
   * decodes scope from the token signature — URL params for workspaceId/all
   * are deliberately NOT trusted anymore. If the token is invalid/expired
   * the server returns 401; the proxy EventSource transparently refreshes
   * the scoped token and reconnects (up to 3 retries).
   */
  openSuggestionStream(
    onSuggestion: (s: Suggestion) => void,
    opts: { workspaceId?: string; all?: boolean } = {}
  ): EventSource {
    // Remember scope for periodic refresh and reconnect.
    activeScope = opts;

    const buildUrl = async () => {
      // Ensure we have a fresh scoped token. This is a fast request
      // (server just signs a payload), and we only hit it on connect.
      await refreshSseToken(opts);
      const t = sseToken();
      return t ? `/api/jarvis/stream?token=${encodeURIComponent(t)}` : "/api/jarvis/stream";
    };

    // Proxy EventSource — handles async initial URL + reconnect-on-auth-failure.
    let es: EventSource | null = null;
    let reconnectAttempts = 0;
    let closed = false;
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
        if (closed || source.readyState === EventSource.CLOSED) return;
        if (reconnectAttempts >= 3) {
          source.close();
          return;
        }
        reconnectAttempts++;
        source.close();
        es = null;
        await refreshSseToken(opts);
        const url = await buildUrl();
        const next = new EventSource(url);
        es = next;
        wire(next);
      });
      source.addEventListener("open", () => {
        reconnectAttempts = 0;
      });
    };

    // Kick off: build URL (async token fetch), then open.
    const ready = (async () => {
      const url = await buildUrl();
      if (closed) return;
      es = new EventSource(url);
      wire(es);
    })();

    return {
      get url() { return es?.url ?? ""; },
      get readyState() { return es?.readyState ?? EventSource.CONNECTING; },
      get withCredentials() { return false; },
      close: () => {
        closed = true;
        es?.close();
        es = null;
      },
      addEventListener: (type: string, handler: EventListener) => {
        listeners.push({ type, handler });
        es?.addEventListener(type, handler);
      },
      removeEventListener: (type: string, handler: EventListener) => {
        const i = listeners.findIndex((l) => l.type === type && l.handler === handler);
        if (i >= 0) listeners.splice(i, 1);
        es?.removeEventListener(type, handler);
      },
      dispatchEvent: () => false,
      onmessage: null,
      onerror: null,
      onopen: null,
      CONNECTING: EventSource.CONNECTING,
      OPEN: EventSource.OPEN,
      CLOSED: EventSource.CLOSED,
      // Expose ready promise for tests/debugging (non-standard).
      ready,
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
