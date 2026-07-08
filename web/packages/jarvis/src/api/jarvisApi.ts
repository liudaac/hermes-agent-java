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
  }
}

function authHeaders(): HeadersInit {
  const h: Record<string, string> = { "Content-Type": "application/json" };
  const t = window.__HERMES_SESSION_TOKEN__;
  if (t) h.Authorization = `Bearer ${t}`;
  return h;
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
    jsonFetch<{ ok: boolean }>(`/api/jarvis/approval/${encodeURIComponent(approvalId)}`, {
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
    const params = new URLSearchParams();
    // SSE can't set custom headers, so we authenticate via ?token=
    const t = window.__HERMES_SESSION_TOKEN__;
    if (t) params.set("token", t);
    if (opts.workspaceId) params.set("workspaceId", opts.workspaceId);
    if (opts.all) params.set("all", "true");
    const qs = params.toString();
    const url = qs ? `/api/jarvis/stream?${qs}` : "/api/jarvis/stream";
    const es = new EventSource(url);
    es.addEventListener("suggestion", (e) => {
      try {
        const s = JSON.parse((e as MessageEvent).data) as Suggestion;
        onSuggestion(s);
      } catch {
        // ignore parse errors
      }
    });
    return es;
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
