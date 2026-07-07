/**
 * NOC (Org Control Center) API.
 *
 *  - Compare runs (gateway-direct SSE)
 *  - Chat playground (gateway-direct SSE)
 *  - Dashboard plugins
 *  - Dashboard themes
 *  - DLQ / takeover / SLA / workflow governance endpoints
 *    (historically lived in portal.ts — they are NOC concerns)
 */
import { fetchJSON, gatewayFetch } from "./_base";
import type { PluginManifestResponse } from "./types/noc";
import type { DashboardThemesResponse } from "./types/noc";
import type { CompareRun, CompareRunResponse } from "./types/common";
import type {
  SLATemplatesResponse,
  DLQResponse,
  ApprovalAnalyticsResponse,
  TakeoverSession,
  WorkflowStatusResponse,
} from "./types/orchestration";

/**
 * Read SSE chunked stream from gateway (compare runs, chat playground).
 * Returns the stream reader; caller is responsible for cleanup.
 */
async function readGatewaySSE(
  path: string,
  params: {
    onEvent: (event: string, data: unknown) => void;
    onError: (err: Error) => void;
  },
): Promise<void> {
  try {
    const res = await gatewayFetch<Response>(path, { method: "GET" });
    if (!res.body) {
      throw new Error("SSE response has no body");
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let currentEvent = "message";
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
            params.onEvent("done", {});
            continue;
          }
          try {
            params.onEvent(currentEvent, JSON.parse(raw));
          } catch {
            params.onEvent(currentEvent, raw);
          }
        }
      }
    }
  } catch (e) {
    params.onError(e instanceof Error ? e : new Error(String(e)));
  }
}

export const nocApi = {
  // ── Compare runs (GatewayServerV2) ──
  listCompareRuns: () =>
    gatewayFetch<{ ok: boolean; runs: CompareRun[] }>("/api/compare/runs"),
  createCompareRun: (params: { topic: string; rounds: number; tenant_ids: string[] }) =>
    gatewayFetch<CompareRunResponse>("/api/compare/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(params),
    }),
  getCompareRun: (id: string) =>
    gatewayFetch<CompareRunResponse>(`/api/compare/runs/${encodeURIComponent(id)}`),
  streamCompareRun: (id: string, params: {
    onEvent: (event: string, data: unknown) => void;
    onError: (err: Error) => void;
  }): Promise<void> =>
    readGatewaySSE(`/api/compare/runs/${encodeURIComponent(id)}/stream`, params),
  stopCompareRun: (id: string) =>
    gatewayFetch<{ ok: boolean }>(`/api/compare/runs/${encodeURIComponent(id)}/stop`, { method: "POST" }),

  // ── Chat playground (GatewayServerV2 SSE) ──
  chatStream: (params: {
    message: string;
    tenant_id: string;
    session_id?: string;
    system_prompt?: string;
    model_params?: Record<string, number | boolean | string>;
    user_id?: string;
    onEvent: (event: string, data: unknown) => void;
    onError: (err: Error) => void;
  }): Promise<void> => {
    // POST body required for chat; handle inline since readGatewaySSE does GET
    return (async () => {
      try {
        const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
        const headers = new Headers();
        headers.set("Content-Type", "application/json");
        const token = window.__HERMES_SESSION_TOKEN__;
        if (token) headers.set("Authorization", `Bearer ${token}`);
        const res = await fetch(`${gatewayUrl}/api/chat/stream`, {
          method: "POST",
          headers,
          body: JSON.stringify(params),
        });
        if (!res.ok || !res.body) {
          throw new Error(`${res.status}: ${await res.text().catch(() => res.statusText)}`);
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let currentEvent = "message";
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
                params.onEvent("done", {});
                continue;
              }
              try {
                params.onEvent(currentEvent, JSON.parse(raw));
              } catch {
                params.onEvent(currentEvent, raw);
              }
            }
          }
        }
      } catch (e) {
        params.onError(e instanceof Error ? e : new Error(String(e)));
      }
    })();
  },

  // ── Dashboard plugins ──
  getPlugins: () => fetchJSON<PluginManifestResponse[]>("/api/dashboard/plugins"),
  rescanPlugins: () => fetchJSON<{ ok: boolean; count: number }>("/api/dashboard/plugins/rescan"),

  // ── Dashboard themes ──
  getThemes: () => fetchJSON<DashboardThemesResponse>("/api/dashboard/themes"),
  setTheme: (name: string) =>
    fetchJSON<{ ok: boolean; theme: string }>("/api/dashboard/theme", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    }),

  // ── NOC operational governance (DLQ, takeover, SLA, workflow) ──
  getSLATemplates: () => fetchJSON<SLATemplatesResponse>("/api/v1/business/sla/templates"),
  getDLQ: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<DLQResponse>(`/api/v1/business/dlq${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  retryDLQItem: (itemId: string) =>
    fetchJSON<{ ok: boolean; itemId: string; status: string }>(
      `/api/v1/business/dlq/${encodeURIComponent(itemId)}/retry`, { method: "POST" },
    ),
  resolveDLQItem: (itemId: string) =>
    fetchJSON<{ ok: boolean; itemId: string; status: string }>(
      `/api/v1/business/dlq/${encodeURIComponent(itemId)}/resolve`, { method: "POST" },
    ),
  getApprovalAnalytics: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<ApprovalAnalyticsResponse>(`/api/v1/business/approval-analytics${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getTakeovers: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<{ ok: boolean; takeovers: TakeoverSession[] }>(`/api/v1/business/takeovers${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  requestTakeover: (workspaceId: string, runId: string, operatorId: string) =>
    fetchJSON<{ ok: boolean; takeover: TakeoverSession }>("/api/v1/business/takeovers", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ workspaceId, runId, operatorId }),
    }),
  confirmTakeover: (takeoverId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/v1/business/takeovers/${encodeURIComponent(takeoverId)}/confirm`, { method: "POST" }),
  releaseTakeover: (takeoverId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/v1/business/takeovers/${encodeURIComponent(takeoverId)}/release`, { method: "POST" }),

  getWorkflows: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<{ ok: boolean; workflows: unknown[] }>(`/api/v1/business/workflows${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getWorkflowStatus: (workflowId: string) =>
    fetchJSON<WorkflowStatusResponse>(`/api/v1/business/workflows/${encodeURIComponent(workflowId)}`),
  approveWorkflowCheckpoint: (workflowId: string, decision: string) =>
    fetchJSON<{ ok: boolean }>(`/api/v1/business/workflows/${encodeURIComponent(workflowId)}/checkpoint`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision }),
    }),
};

// ── NOC operational methods merged into nocApi above. ─────────────
