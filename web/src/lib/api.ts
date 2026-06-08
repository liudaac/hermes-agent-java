const BASE = "";

// Ephemeral session token for protected endpoints.
// Injected into index.html by the server — never fetched via API.
declare global {
  interface Window {
    __HERMES_SESSION_TOKEN__?: string;
  }
}
let _sessionToken: string | null = null;

export async function fetchJSON<T>(url: string, init?: RequestInit): Promise<T> {
  // Inject the session token into all /api/ requests.
  const headers = new Headers(init?.headers);
  const token = window.__HERMES_SESSION_TOKEN__;
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(`${BASE}${url}`, { ...init, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${text}`);
  }
  return res.json();
}

async function getSessionToken(): Promise<string> {
  if (_sessionToken) return _sessionToken;
  const injected = window.__HERMES_SESSION_TOKEN__;
  if (injected) {
    _sessionToken = injected;
    return _sessionToken;
  }
  throw new Error("Session token not available — page must be served by the Hermes dashboard server");
}

export const api = {
  getStatus: () => fetchJSON<StatusResponse>("/api/status"),
  getSessions: (limit = 20, offset = 0) =>
    fetchJSON<PaginatedSessions>(`/api/sessions?limit=${limit}&offset=${offset}`),
  getSessionMessages: (id: string) =>
    fetchJSON<SessionMessagesResponse>(`/api/sessions/${encodeURIComponent(id)}/messages`),
  deleteSession: (id: string) =>
    fetchJSON<{ ok: boolean }>(`/api/sessions/${encodeURIComponent(id)}`, {
      method: "DELETE",
    }),
  getLogs: (params: { file: string; lines?: number; level?: string; component?: string }) => {
    const qs = new URLSearchParams();
    qs.set("file", params.file);
    if (params.lines) qs.set("lines", String(params.lines));
    if (params.level && params.level !== "ALL") qs.set("level", params.level);
    if (params.component && params.component !== "all") qs.set("component", params.component);
    return fetchJSON<LogsResponse>(`/api/logs?${qs.toString()}`);
  },
  getLogFiles: () => fetchJSON<LogFilesResponse>("/api/logs/files"),
  deleteLogFile: (file: string) =>
    fetchJSON<{ ok: boolean; file: string }>(`/api/logs?file=${encodeURIComponent(file)}`, {
      method: "DELETE",
    }),
  getLogAggregate: (params: {
    files?: string[];
    lines?: number;
    level?: string;
    component?: string;
  }) => {
    const qs = new URLSearchParams();
    if (params.files && params.files.length > 0)
      qs.set("files", params.files.join(","));
    if (params.lines) qs.set("lines", String(params.lines));
    if (params.level && params.level !== "ALL") qs.set("level", params.level);
    if (params.component && params.component !== "all")
      qs.set("component", params.component);
    return fetchJSON<LogAggregateResponse>(
      `/api/logs/aggregate?${qs.toString()}`,
    );
  },
  openLogTail: async (params: {
    file: string;
    level?: string;
    component?: string;
  }): Promise<EventSource> => {
    const token = await getSessionToken();
    const qs = new URLSearchParams();
    qs.set("file", params.file);
    qs.set("token", token);
    if (params.level && params.level !== "ALL") qs.set("level", params.level);
    if (params.component && params.component !== "all")
      qs.set("component", params.component);
    return new EventSource(`/api/logs/tail?${qs.toString()}`);
  },
  getAnalytics: (days: number) =>
    fetchJSON<AnalyticsResponse>(`/api/analytics/usage?days=${days}`),
  getConfig: () => fetchJSON<Record<string, unknown>>("/api/config"),
  getDefaults: () => fetchJSON<Record<string, unknown>>("/api/config/defaults"),
  getSchema: () => fetchJSON<{ fields: Record<string, unknown>; category_order: string[] }>("/api/config/schema"),
  getModelInfo: () => fetchJSON<ModelInfoResponse>("/api/model/info"),
  saveConfig: (config: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean }>("/api/config", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ config }),
    }),
  getConfigRaw: () => fetchJSON<{ yaml: string }>("/api/config/raw"),
  saveConfigRaw: (yaml_text: string) =>
    fetchJSON<{ ok: boolean }>("/api/config/raw", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ yaml_text }),
    }),
  getEnvVars: () => fetchJSON<Record<string, EnvVarInfo>>("/api/env"),
  setEnvVar: (key: string, value: string) =>
    fetchJSON<{ ok: boolean }>("/api/env", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key, value }),
    }),
  deleteEnvVar: (key: string) =>
    fetchJSON<{ ok: boolean }>("/api/env", {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key }),
    }),
  revealEnvVar: async (key: string) => {
    const token = await getSessionToken();
    return fetchJSON<{ key: string; value: string }>("/api/env/reveal", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ key }),
    });
  },

  // Cron jobs
  getCronJobs: () => fetchJSON<CronJob[]>("/api/cron/jobs"),
  createCronJob: (job: { prompt: string; schedule: string; name?: string; deliver?: string }) =>
    fetchJSON<CronJob>("/api/cron/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(job),
    }),
  pauseCronJob: (id: string) =>
    fetchJSON<{ ok: boolean }>(`/api/cron/jobs/${id}/pause`, { method: "POST" }),
  resumeCronJob: (id: string) =>
    fetchJSON<{ ok: boolean }>(`/api/cron/jobs/${id}/resume`, { method: "POST" }),
  triggerCronJob: (id: string) =>
    fetchJSON<CronTriggerResult>(`/api/cron/jobs/${id}/trigger`, { method: "POST" }),
  deleteCronJob: (id: string) =>
    fetchJSON<{ ok: boolean }>(`/api/cron/jobs/${id}`, { method: "DELETE" }),
  getCronJobRuns: (id: string) =>
    fetchJSON<{ id: string; runs: CronRunRecord[] }>(`/api/cron/jobs/${id}/runs`),
  previewCronSchedule: (schedule: string, count = 5) =>
    fetchJSON<CronSchedulePreview>(
      `/api/cron/preview?schedule=${encodeURIComponent(schedule)}&count=${count}`,
    ),
  openCronRunStream: async (id: string): Promise<EventSource> => {
    const token = await getSessionToken();
    return new EventSource(
      `/api/cron/jobs/${encodeURIComponent(id)}/runs/stream?token=${encodeURIComponent(token)}`,
    );
  },

  // Skills & Toolsets
  getSkills: () => fetchJSON<SkillInfo[]>("/api/skills"),
  toggleSkill: (name: string, enabled: boolean) =>
    fetchJSON<{ ok: boolean }>("/api/skills/toggle", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, enabled }),
    }),
  getToolsets: () => fetchJSON<ToolsetInfo[]>("/api/tools/toolsets"),
  getToolGroups: () => fetchJSON<ToolGroup[]>("/api/tools"),
  getToolDetail: (name: string) =>
    fetchJSON<ToolDetail>(`/api/tools/${encodeURIComponent(name)}`),

  // Session search (FTS5)
  searchSessions: (q: string) =>
    fetchJSON<SessionSearchResponse>(`/api/sessions/search?q=${encodeURIComponent(q)}`),

  // OAuth provider management
  getOAuthProviders: () =>
    fetchJSON<OAuthProvidersResponse>("/api/providers/oauth"),
  disconnectOAuthProvider: async (providerId: string) => {
    const token = await getSessionToken();
    return fetchJSON<{ ok: boolean; provider: string }>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}`,
      {
        method: "DELETE",
        headers: { Authorization: `Bearer ${token}` },
      },
    );
  },
  startOAuthLogin: async (providerId: string) => {
    const token = await getSessionToken();
    return fetchJSON<OAuthStartResponse>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}/start`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: "{}",
      },
    );
  },
  submitOAuthCode: async (providerId: string, sessionId: string, code: string) => {
    const token = await getSessionToken();
    return fetchJSON<OAuthSubmitResponse>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}/submit`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ session_id: sessionId, code }),
      },
    );
  },
  pollOAuthSession: (providerId: string, sessionId: string) =>
    fetchJSON<OAuthPollResponse>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}/poll/${encodeURIComponent(sessionId)}`,
    ),
  cancelOAuthSession: async (sessionId: string) => {
    const token = await getSessionToken();
    return fetchJSON<{ ok: boolean }>(
      `/api/providers/oauth/sessions/${encodeURIComponent(sessionId)}`,
      {
        method: "DELETE",
        headers: { Authorization: `Bearer ${token}` },
      },
    );
  },

  // Tenants
  getTenants: () => fetchJSON<TenantsResponse>("/api/tenants"),
  createTenant: (tenantId: string) =>
    fetchJSON<TenantActionResponse>("/api/tenants", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tenantId }),
    }),
  getTenant: (tenantId: string) =>
    fetchJSON<TenantSummary>(`/api/tenants/${encodeURIComponent(tenantId)}`),
  deleteTenant: (tenantId: string) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}`, {
      method: "DELETE",
    }),
  suspendTenant: (tenantId: string) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/suspend`, {
      method: "POST",
    }),
  resumeTenant: (tenantId: string) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/resume`, {
      method: "POST",
    }),
  getTenantSkills: (tenantId: string) =>
    fetchJSON<TenantSkillsResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/skills`),
  getTenantQuota: (tenantId: string) =>
    fetchJSON<TenantQuota>(`/api/tenants/${encodeURIComponent(tenantId)}/quota`),
  updateTenantQuota: (tenantId: string, quota: Partial<TenantQuota>) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/quota`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(quota),
    }),
  getTenantUsage: (tenantId: string) =>
    fetchJSON<TenantUsage>(`/api/tenants/${encodeURIComponent(tenantId)}/usage`),
  getTenantSecurity: (tenantId: string) =>
    fetchJSON<TenantSecurity>(`/api/tenants/${encodeURIComponent(tenantId)}/security`),
  updateTenantSecurity: (tenantId: string, security: Partial<TenantSecurity>) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/security`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(security),
    }),
  getTenantAudit: (tenantId: string, limit = 100) =>
    fetchJSON<TenantAuditResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/audit?limit=${limit}`),
  getTenantConfig: (tenantId: string) =>
    fetchJSON<TenantConfigResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/config`),
  updateTenantConfig: (tenantId: string, config: Partial<TenantConfigPayload>) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/config`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(config),
    }),

  // Gateway / update actions
  restartGateway: () =>
    fetchJSON<ActionResponse>("/api/gateway/restart", { method: "POST" }),
  updateHermes: () =>
    fetchJSON<ActionResponse>("/api/hermes/update", { method: "POST" }),
  getActionStatus: (name: string, lines = 200) =>
    fetchJSON<ActionStatusResponse>(
      `/api/actions/${encodeURIComponent(name)}/status?lines=${lines}`,
    ),

  // Dashboard plugins
  getPlugins: () =>
    fetchJSON<PluginManifestResponse[]>("/api/dashboard/plugins"),
  rescanPlugins: () =>
    fetchJSON<{ ok: boolean; count: number }>("/api/dashboard/plugins/rescan"),

  // Dashboard themes
  getThemes: () =>
    fetchJSON<DashboardThemesResponse>("/api/dashboard/themes"),
  setTheme: (name: string) =>
    fetchJSON<{ ok: boolean; theme: string }>("/api/dashboard/theme", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    }),

  // Server-side tenant comparison runs (GatewayServerV2)
  listCompareRuns: async () => {
    const token = window.__HERMES_SESSION_TOKEN__;
    const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
    const res = await fetch(`${gatewayUrl}/api/compare/runs`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => res.statusText);
      throw new Error(`${res.status}: ${text}`);
    }
    return res.json() as Promise<{ ok: boolean; runs: CompareRun[] }>;
  },
  createCompareRun: async (params: { topic: string; rounds: number; tenant_ids: string[] }) => {
    const token = window.__HERMES_SESSION_TOKEN__;
    const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
    const res = await fetch(`${gatewayUrl}/api/compare/runs`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(params),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => res.statusText);
      throw new Error(`${res.status}: ${text}`);
    }
    return res.json() as Promise<CompareRunResponse>;
  },
  getCompareRun: async (id: string) => {
    const token = window.__HERMES_SESSION_TOKEN__;
    const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
    const res = await fetch(`${gatewayUrl}/api/compare/runs/${encodeURIComponent(id)}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => res.statusText);
      throw new Error(`${res.status}: ${text}`);
    }
    return res.json() as Promise<CompareRunResponse>;
  },
  streamCompareRun: async (id: string, params: {
    onEvent: (event: string, data: unknown) => void;
    onError: (err: Error) => void;
  }): Promise<void> => {
    const token = window.__HERMES_SESSION_TOKEN__;
    const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
    const res = await fetch(`${gatewayUrl}/api/compare/runs/${encodeURIComponent(id)}/stream`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => res.statusText);
      throw new Error(`${res.status}: ${text}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let currentEvent = "run";
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
    } finally {
      reader.releaseLock();
    }
  },
  stopCompareRun: async (id: string) => {
    const token = window.__HERMES_SESSION_TOKEN__;
    const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
    const res = await fetch(`${gatewayUrl}/api/compare/runs/${encodeURIComponent(id)}/stop`, {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => res.statusText);
      throw new Error(`${res.status}: ${text}`);
    }
    return res.json() as Promise<{ ok: boolean }>;
  },

  // Chat playground (GatewayServerV2 SSE)
  chatStream: async (params: {
    message: string;
    tenant_id: string;
    session_id?: string;
    system_prompt?: string;
    model_params?: Record<string, number | boolean | string>;
    user_id?: string;
    onEvent: (event: string, data: unknown) => void;
    onError: (err: Error) => void;
  }): Promise<void> => {
    const token = window.__HERMES_SESSION_TOKEN__;
    const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
    const res = await fetch(`${gatewayUrl}/api/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(params),
    });
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => res.statusText);
      throw new Error(`${res.status}: ${text}`);
    }
    const reader = res.body.getReader();
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
              params.onEvent("done", {});
              continue;
            }
            try {
              const data = JSON.parse(raw);
              params.onEvent(currentEvent, data);
            } catch {
              params.onEvent(currentEvent, raw);
            }
          }
        }
      }
    } catch (e) {
      params.onError(e instanceof Error ? e : new Error(String(e)));
    } finally {
      reader.releaseLock();
    }
  },
};

export interface CompareRunParticipant {
  tenant_id: string;
  session_id: string;
}

export interface CompareRunEvent {
  tenant_id: string;
  role: "user" | "assistant" | "error" | string;
  content: string;
  timestamp: string;
}

export interface CompareRun {
  id: string;
  topic: string;
  rounds: number;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "STOPPED" | "FAILED";
  participants: CompareRunParticipant[];
  events?: CompareRunEvent[];
  conclusion?: string;
  error?: string;
  created_at: string;
  updated_at: string;
  event_count: number;
}

export interface CompareRunResponse {
  ok: boolean;
  run: CompareRun;
  error?: string;
}

export interface ActionResponse {
  name: string;
  ok: boolean;
  pid: number;
}

export interface ActionStatusResponse {
  exit_code: number | null;
  lines: string[];
  name: string;
  pid: number | null;
  running: boolean;
}

export interface PlatformStatus {
  error_code?: string;
  error_message?: string;
  state: string;
  updated_at: string;
}

export interface StatusResponse {
  active_sessions: number;
  config_path: string;
  config_version: number;
  env_path: string;
  gateway_exit_reason: string | null;
  gateway_health_url: string | null;
  gateway_pid: number | null;
  gateway_platforms: Record<string, PlatformStatus>;
  gateway_running: boolean;
  gateway_state: string | null;
  gateway_updated_at: string | null;
  hermes_home: string;
  latest_config_version: number;
  release_date: string;
  version: string;
}

export interface SessionInfo {
  id: string;
  source: string | null;
  model: string | null;
  title: string | null;
  started_at: number;
  ended_at: number | null;
  last_active: number;
  is_active: boolean;
  message_count: number;
  tool_call_count: number;
  input_tokens: number;
  output_tokens: number;
  preview: string | null;
}

export interface PaginatedSessions {
  sessions: SessionInfo[];
  total: number;
  limit: number;
  offset: number;
}

export interface EnvVarInfo {
  is_set: boolean;
  redacted_value: string | null;
  description: string;
  url: string | null;
  category: string;
  is_password: boolean;
  tools: string[];
  advanced: boolean;
}

export interface SessionMessage {
  role: "user" | "assistant" | "system" | "tool";
  content: string | null;
  tool_calls?: Array<{
    id: string;
    function: { name: string; arguments: string };
  }>;
  tool_name?: string;
  tool_call_id?: string;
  timestamp?: number;
}

export interface SessionMessagesResponse {
  session_id: string;
  messages: SessionMessage[];
}

export interface LogFileInfo {
  name: string;
  path: string;
  size: number;
  modified: number;
}

export interface LogFilesResponse {
  files: LogFileInfo[];
}

export interface LogsResponse {
  file: string;
  lines: string[];
}

export interface LogAggregateEntry {
  file: string;
  line: string;
}

export interface LogAggregateResponse {
  files: string[];
  count: number;
  entries: LogAggregateEntry[];
}

export interface AnalyticsDailyEntry {
  day: string;
  input_tokens: number;
  output_tokens: number;
  cache_read_tokens: number;
  reasoning_tokens: number;
  estimated_cost: number;
  actual_cost: number;
  sessions: number;
}

export interface AnalyticsModelEntry {
  model: string;
  input_tokens: number;
  output_tokens: number;
  estimated_cost: number;
  sessions: number;
}

export interface AnalyticsSkillEntry {
  skill: string;
  view_count: number;
  manage_count: number;
  total_count: number;
  percentage: number;
  last_used_at: number | null;
}

export interface AnalyticsSkillsSummary {
  total_skill_loads: number;
  total_skill_edits: number;
  total_skill_actions: number;
  distinct_skills_used: number;
}

export interface AnalyticsResponse {
  daily: AnalyticsDailyEntry[];
  by_model: AnalyticsModelEntry[];
  totals: {
    total_input: number;
    total_output: number;
    total_cache_read: number;
    total_reasoning: number;
    total_estimated_cost: number;
    total_actual_cost: number;
    total_sessions: number;
  };
  skills: {
    summary: AnalyticsSkillsSummary;
    top_skills: AnalyticsSkillEntry[];
  };
}

export interface CronJob {
  id: string;
  name?: string;
  prompt: string;
  schedule: { kind: string; expr: string; display: string };
  schedule_display: string;
  enabled: boolean;
  state: string;
  deliver?: string;
  last_run_at?: string | null;
  next_run_at?: string | null;
  last_error?: string | null;
}

export interface CronRunRecord {
  at: string;
  ok: boolean;
  duration_ms: number;
  output: string | null;
  error: string | null;
}

export interface CronTriggerResult {
  ok: boolean;
  id: string;
  duration_ms: number;
  output: string | null;
  error: string | null;
  job: CronJob;
}

export interface CronSchedulePreview {
  schedule: { kind: string; expr: string; display: string };
  valid: boolean;
  upcoming: string[];
  timezone: string;
}

export interface SkillInfo {
  name: string;
  description: string;
  category: string;
  enabled: boolean;
}

export interface ToolsetInfo {
  name: string;
  label: string;
  description: string;
  enabled: boolean;
  configured: boolean;
  tools: string[];
}

export interface ToolGroup {
  name: string;
  description: string;
  emoji: string;
  available: boolean;
  tools: string[];
  tool_details: ToolSummary[];
  source: string;
}

export interface ToolSummary {
  name: string;
  toolset: string;
  description: string;
  emoji: string | null;
  async: boolean;
  requires_env: string[] | null;
  max_result_size_chars: number | null;
}

export interface ToolDetail extends ToolSummary {
  available: boolean;
  schema: Record<string, unknown>;
  parameter_count?: number;
  source: string;
}

export interface SessionSearchResult {
  session_id: string;
  snippet: string;
  role: string | null;
  source: string | null;
  model: string | null;
  session_started: number | null;
}

export interface SessionSearchResponse {
  results: SessionSearchResult[];
}

// ── Model info types ──────────────────────────────────────────────────

export interface ModelInfoResponse {
  model: string;
  provider: string;
  auto_context_length: number;
  config_context_length: number;
  effective_context_length: number;
  capabilities: {
    supports_tools?: boolean;
    supports_vision?: boolean;
    supports_reasoning?: boolean;
    context_window?: number;
    max_output_tokens?: number;
    model_family?: string;
  };
}

// ── Tenant types ────────────────────────────────────────────────────────

export interface TenantSummary {
  tenantId: string;
  state: string;
  createdAt?: string;
  lastActivity?: string;
  activeAgents: number;
  activeSessions: number;
  quota?: Record<string, unknown>;
}

export interface TenantsResponse {
  tenants: TenantSummary[];
  total: number;
}

export interface TenantActionResponse {
  ok?: boolean;
  success?: boolean;
  tenantId: string;
  state?: string;
  message?: string;
}

export interface TenantSkillInfo {
  name: string;
  description?: string;
  source?: string;
  version?: string;
  readOnly?: boolean;
  scope: "tenant";
  tenantId: string;
}

export interface TenantSkillsResponse {
  tenantId: string;
  scope: "tenant";
  skills: TenantSkillInfo[];
  installedSkills: string[];
  total: number;
  totalSkills: number;
}

export interface TenantQuota {
  maxDailyRequests: number;
  maxDailyTokens: number;
  maxConcurrentAgents: number;
  maxConcurrentSessions: number;
  maxStorageBytes: number;
  maxMemoryBytes: number;
  requestsPerSecond: number;
  requestsPerMinute: number;
  maxToolCallsPerSession: number;
  maxFileSizeBytes: number;
  maxExecutionTimeSeconds: number;
  allowCodeExecution: boolean;
  maxPrivateSkills: number;
  maxInstalledSkills: number;
}

export interface TenantUsage {
  tenantId: string;
  dailyRequests: number;
  maxDailyRequests: number;
  dailyTokens: number;
  maxDailyTokens: number;
  activeAgents: number;
  storageUsage: number;
  storage: number;
  memory: number;
}

export interface TenantSecurity {
  tenantId: string;
  allowCodeExecution: boolean;
  requireSandbox: boolean;
  allowNetworkAccess: boolean;
  allowFileRead: boolean;
  allowFileWrite: boolean;
  allowedLanguages: string[];
  allowedHosts: string[];
  allowedTools: string[];
  deniedTools: string[];
  deniedPaths: string[];
}

export interface TenantAuditEvent {
  timestamp: string;
  event: string;
  type: string;
  details: Record<string, unknown>;
}

export interface TenantAuditResponse {
  tenantId: string;
  logs: TenantAuditEvent[];
  events: TenantAuditEvent[];
  total: number;
}

export interface TenantConfigResponse {
  tenant_id: string;
  system_prompt: string;
  temperature: number;
  max_tokens: number;
  model: string;
  provider: string;
}

export interface TenantConfigPayload {
  system_prompt?: string;
  temperature?: number;
  max_tokens?: number;
  model?: string;
  provider?: string;
}

// ── OAuth provider types ────────────────────────────────────────────────

export interface OAuthProviderStatus {
  logged_in: boolean;
  source?: string | null;
  source_label?: string | null;
  token_preview?: string | null;
  expires_at?: string | null;
  has_refresh_token?: boolean;
  last_refresh?: string | null;
  error?: string;
}

export interface OAuthProvider {
  id: string;
  name: string;
  /** "pkce" (browser redirect + paste code), "device_code" (show code + URL),
   *  or "external" (delegated to a separate CLI like Claude Code or Qwen). */
  flow: "pkce" | "device_code" | "external";
  cli_command: string;
  docs_url: string;
  status: OAuthProviderStatus;
}

export interface OAuthProvidersResponse {
  providers: OAuthProvider[];
}

/** Discriminated union — the shape of /start depends on the flow. */
export type OAuthStartResponse =
  | {
      session_id: string;
      flow: "pkce";
      auth_url: string;
      expires_in: number;
    }
  | {
      session_id: string;
      flow: "device_code";
      user_code: string;
      verification_url: string;
      expires_in: number;
      poll_interval: number;
    };

export interface OAuthSubmitResponse {
  ok: boolean;
  status: "approved" | "error";
  message?: string;
}

export interface OAuthPollResponse {
  session_id: string;
  status: "pending" | "approved" | "denied" | "expired" | "error";
  error_message?: string | null;
  expires_at?: number | null;
}

// ── Dashboard theme types ──────────────────────────────────────────────

export interface DashboardThemeSummary {
  description: string;
  label: string;
  name: string;
}

export interface DashboardThemesResponse {
  active: string;
  themes: DashboardThemeSummary[];
}

// ── Dashboard plugin types ─────────────────────────────────────────────

export interface PluginManifestResponse {
  name: string;
  label: string;
  description: string;
  icon: string;
  version: string;
  tab: { path: string; position: string };
  entry: string;
  css?: string | null;
  has_api: boolean;
  source: string;
}
