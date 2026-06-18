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
  if (res.status === 204) {
    return {} as T;
  }
  const text = await res.text();
  if (!text.trim()) {
    return {} as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch (err: any) {
    throw new Error(`Invalid JSON from ${url}: ${err?.message || String(err)}`);
  }
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


  // Business Portal
  getBusinessHome: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<BusinessHomeResponse>(`/api/v1/business/home${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getBusinessTeams: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<BusinessTeamsResponse>(`/api/v1/business/teams${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getBusinessRuns: (workspaceId?: string, limit = 20, scenarioId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    if (scenarioId) qs.set("scenarioId", scenarioId);
    qs.set("limit", String(limit));
    return fetchJSON<BusinessRunsResponse>(`/api/v1/business/runs?${qs.toString()}`);
  },
  getBusinessApprovals: (workspaceId?: string, status = "ALL") => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    qs.set("status", status);
    return fetchJSON<BusinessApprovalsResponse>(`/api/v1/business/approvals?${qs.toString()}`);
  },
  getBusinessInsights: (workspaceId?: string, scenarioId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    if (scenarioId) qs.set("scenarioId", scenarioId);
    return fetchJSON<BusinessInsightsResponse>(`/api/v1/business/insights${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getBusinessScenarios: (workspaceId: string) =>
    fetchJSON<BusinessScenariosResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios`),
  getBusinessPromptAssets: (workspaceId: string) =>
    fetchJSON<BusinessPromptAssetsResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/prompt-assets`),

  createBusinessScenario: (workspaceId: string, payload: CreateBusinessScenarioPayload) =>
    fetchJSON<CreateBusinessScenarioResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  executeBusinessScenario: (workspaceId: string, scenarioId: string, userInput: string) =>
    fetchJSON<CreateBusinessRunResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios/${encodeURIComponent(scenarioId)}/execute`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userInput }),
    }),
  createBusinessPromptAsset: (workspaceId: string, payload: CreateBusinessPromptAssetPayload) =>
    fetchJSON<CreateBusinessPromptAssetResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/prompt-assets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),

  createBusinessWorkspace: (payload: CreateBusinessWorkspacePayload) =>
    fetchJSON<CreateBusinessWorkspaceResponse>("/api/v1/workspaces", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),

  createBusinessTeamBlueprint: (workspaceId: string, payload: CreateBusinessTeamBlueprintPayload) =>
    fetchJSON<CreateBusinessTeamBlueprintResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  getBusinessTeamBlueprint: (workspaceId: string, teamId: string) =>
    fetchJSON<{ ok: boolean; team: BusinessTeamCard }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints/${encodeURIComponent(teamId)}`,
    ),
  createTeamBlueprintDraftVersion: (workspaceId: string, teamId: string, payload: { changeSummary: string; agents?: AgentBlueprintPayload[] }) =>
    fetchJSON<{ ok: boolean; version: { version: number; status: string } }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints/${encodeURIComponent(teamId)}/versions`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  activateTeamBlueprintVersion: (workspaceId: string, teamId: string, version: number) =>
    fetchJSON<{ ok: boolean; activeVersion: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints/${encodeURIComponent(teamId)}/versions/${version}/activate`,
      { method: "POST" },
    ),

  createBusinessRun: (workspaceId: string, payload: CreateBusinessRunPayload) =>
    fetchJSON<CreateBusinessRunResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/runs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),

  createBusinessApproval: (workspaceId: string, payload: CreateBusinessApprovalPayload) =>
    fetchJSON<CreateBusinessApprovalResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),

  approveBusinessApproval: (workspaceId: string, approvalId: string, payload: ResolveBusinessApprovalPayload) =>
    fetchJSON<ResolveBusinessApprovalResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/approve`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  rejectBusinessApproval: (workspaceId: string, approvalId: string, payload: ResolveBusinessApprovalPayload) =>
    fetchJSON<ResolveBusinessApprovalResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/reject`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  requestBusinessApprovalInfo: (workspaceId: string, approvalId: string, payload: RequestBusinessApprovalInfoPayload) =>
    fetchJSON<ResolveBusinessApprovalResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/request-info`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  resumeExecution: (workspaceId: string, approvalId: string, scenarioId: string, userInput: string) =>
    fetchJSON<CreateBusinessRunResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/resume-execution`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ scenarioId, userInput }),
      },
    ),
  // EvalSet APIs
  listEvalSets: (workspaceId: string, scenarioId: string) =>
    fetchJSON<{ ok: boolean; evalSets: unknown[]; total: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios/${encodeURIComponent(scenarioId)}/evalsets`,
    ),
  createEvalSet: (workspaceId: string, scenarioId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; evalSetId: string; evalSet: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios/${encodeURIComponent(scenarioId)}/evalsets`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),

  // Canary Release APIs
  listCanaries: (workspaceId: string, teamId: string) =>
    fetchJSON<{ ok: boolean; canaries: unknown[] }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries`,
    ),
  startCanary: (workspaceId: string, teamId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  getActiveCanary: (workspaceId: string, teamId: string) =>
    fetchJSON<{ ok: boolean; canary: unknown | null }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/active`,
    ),
  updateCanaryTraffic: (workspaceId: string, teamId: string, releaseId: string, trafficPercent: number) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/${encodeURIComponent(releaseId)}/traffic`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ trafficPercent }),
      },
    ),
  promoteCanary: (workspaceId: string, teamId: string, releaseId: string) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/${encodeURIComponent(releaseId)}/promote`,
      { method: "POST" },
    ),
  rollbackCanary: (workspaceId: string, teamId: string, releaseId: string) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/${encodeURIComponent(releaseId)}/rollback`,
      { method: "POST" },
    ),

  // Active Memory APIs
  listMemories: (workspaceId: string) =>
    fetchJSON<{ ok: boolean; memories: unknown[]; total: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/memories`,
    ),
  createMemory: (workspaceId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; memoryId: string; memory: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/memories`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  recallMemories: (workspaceId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; memories: unknown[]; count: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/memories/recall`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),

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

// ── Business Portal types ──────────────────────────────────────────────

export interface BusinessAction {
  id: string;
  title: string;
  description?: string;
}

export interface BusinessInsightRecord {
  insightId: string;
  workspaceId?: string;
  title: string;
  finding: string;
  possibleCause?: string;
  recommendation?: string;
  expectedBenefit?: string;
  suggestedAction?: string;
  severity?: string;
  metrics?: Record<string, unknown>;
  generatedAt?: string;
}

export interface BusinessHomeResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  summary: {
    workspaceCount: number;
    teamCount: number;
    runCount: number;
    pendingApprovals: number;
    openInsights: number;
  };
  today: Record<string, number>;
  needsAttention: BusinessAction[];
  risk: { level: string; [key: string]: unknown };
  teamStatus: { total: number; normal: number; needsAttention: number; emptyState?: string };
  insights: BusinessInsightRecord[];
  nextActions: BusinessAction[];
  workspaces: WorkspaceRecord[];
  emptyState?: string;
}

export interface WorkspaceRecord {
  workspaceId: string;
  tenantId: string;
  name: string;
  description?: string;
  owner?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  metadata?: Record<string, unknown>;
}

export interface BusinessTeamCard {
  workspaceId: string;
  teamId: string;
  name: string;
  scenario?: string;
  scenarioId?: string;
  activeVersion: number;
  versionCount: number;
  status: string;
}

export interface BusinessTeamsResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  teams: BusinessTeamCard[];
  total: number;
  emptyState?: string;
}

export interface BusinessRunStep {
  stepId?: string;
  title?: string;
  summary?: string;
  actor?: string;
  evidence?: string;
  status?: string;
  timestamp?: string;
  metadata?: Record<string, unknown>;
}

export interface BusinessRunRecord {
  runId: string;
  workspaceId: string;
  teamId?: string;
  scenario?: string;
  scenarioId?: string;
  taskTitle: string;
  taskInput?: string;
  resultSummary: string;
  conclusionReason?: string;
  systemAction?: string;
  riskJudgement?: string;
  nextSuggestion?: string;
  technicalTraceRef?: string;
  steps?: BusinessRunStep[];
  metrics?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  status: string;
  tokensUsed?: number;
  estimatedCost?: number;
  createdAt?: string;
}

export interface BusinessRunsResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  teamId?: string;
  runs: BusinessRunRecord[];
  total: number;
  emptyState?: string;
  nextActions?: BusinessAction[];
}

export interface BusinessApprovalRecord {
  approvalId: string;
  workspaceId: string;
  teamId?: string;
  title: string;
  summary: string;
  reasonRequired?: string;
  approveEffect?: string;
  rejectEffect?: string;
  recommendation?: string;
  riskLevel: string;
  status: string;
  evidence?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  resolvedAt?: string;
  resolvedBy?: string;
  resolutionReason?: string;
  requestedInfo?: string;
}

export interface BusinessApprovalsResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  approvals: BusinessApprovalRecord[];
  total: number;
  emptyState?: string;
}

export interface BusinessInsightsResponse {
  ok: boolean;
  entry: string;
  metrics: Record<string, number>;
  insights: BusinessInsightRecord[];
  total: number;
  nextActions: BusinessAction[];
  emptyState?: string;
}

export interface CreateBusinessWorkspacePayload {
  workspaceId: string;
  name: string;
  description?: string;
  owner?: string;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessWorkspaceResponse {
  ok: boolean;
  workspaceId: string;
  tenantId: string;
  workspace: WorkspaceRecord;
  message?: string;
}

export interface AgentBlueprintPayload {
  agentId: string;
  displayName: string;
  responsibility?: string;
  knowledgeRefs?: string[];
  allowedTools?: string[];
  allowedSkills?: string[];
  approvalRules?: string[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessTeamBlueprintPayload {
  teamId: string;
  name: string;
  description?: string;
  scenario?: string;
  scenarioId?: string;
  operatingManual?: string;
  promptAssetRefs?: string[];
  agents?: AgentBlueprintPayload[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessTeamBlueprintResponse {
  ok: boolean;
  workspaceId: string;
  teamId: string;
  team: unknown;
  message?: string;
}

export interface CreateBusinessRunPayload {
  teamId?: string;
  scenario?: string;
  scenarioId?: string;
  taskTitle: string;
  taskInput?: string;
  resultSummary: string;
  conclusionReason?: string;
  systemAction?: string;
  riskJudgement?: string;
  nextSuggestion?: string;
  status?: string;
  technicalTraceRef?: string;
  steps?: BusinessRunStep[];
  metrics?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessRunResponse {
  ok: boolean;
  workspaceId: string;
  runId: string;
  run: BusinessRunRecord;
  message?: string;
}

export interface CreateBusinessApprovalPayload {
  teamId?: string;
  title: string;
  summary: string;
  reasonRequired?: string;
  approveEffect?: string;
  rejectEffect?: string;
  recommendation?: string;
  riskLevel?: string;
  evidence?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessApprovalResponse {
  ok: boolean;
  workspaceId: string;
  approvalId: string;
  approval: BusinessApprovalRecord;
  message?: string;
}

export interface ResolveBusinessApprovalPayload {
  actor?: string;
  reason?: string;
}

export interface RequestBusinessApprovalInfoPayload {
  actor?: string;
  requestedInfo?: string;
}

export interface ResolveBusinessApprovalResponse {
  ok: boolean;
  workspaceId: string;
  approvalId: string;
  status: string;
  approval: BusinessApprovalRecord;
  message?: string;
}

export interface BusinessScenarioRecord {
  workspaceId: string;
  scenarioId: string;
  name: string;
  description?: string;
  entryTeamId?: string;
  status?: string;
  successCriteria?: string[];
  approvalRules?: string[];
  metadata?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessScenariosResponse {
  ok: boolean;
  workspaceId: string;
  scenarios: BusinessScenarioRecord[];
  total: number;
}

export interface CreateBusinessScenarioPayload {
  scenarioId: string;
  name: string;
  description?: string;
  entryTeamId?: string;
  successCriteria?: string[];
  approvalRules?: string[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessScenarioResponse {
  ok: boolean;
  workspaceId: string;
  scenarioId: string;
  scenario: BusinessScenarioRecord;
  message?: string;
}

export interface BusinessPromptAssetRecord {
  workspaceId: string;
  assetId: string;
  name: string;
  purpose?: string;
  content?: string;
  version: number;
  status: string;
  tags?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessPromptAssetsResponse {
  ok: boolean;
  workspaceId: string;
  promptAssets: BusinessPromptAssetRecord[];
  total: number;
}

export interface CreateBusinessPromptAssetPayload {
  assetId: string;
  name: string;
  purpose?: string;
  content?: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessPromptAssetResponse {
  ok: boolean;
  workspaceId: string;
  assetId: string;
  promptAsset: BusinessPromptAssetRecord;
  message?: string;
}
