/**
 * Admin API - tenant management, model routing, config.
 *
 * Moved from ops/src/lib/api/ops-tenant.ts + ops.ts (config section).
 * OAuth moved to devportal/src/lib/api/devportal.ts.
 */
import { fetchJSON } from "@hermes/ui";

// ── Types ──────────────────────────────────────────────────

export interface TenantSummary {
  tenantId: string;
  status: string;
  createdAt: number;
  lastActiveAt: number;
  sessionCount: number;
  modelAlias?: string;
}

export interface TenantsResponse {
  ok: boolean;
  tenants: TenantSummary[];
  total: number;
}

export interface TenantActionResponse {
  ok: boolean;
  tenantId: string;
  action: string;
}

export interface TenantQuota {
  dailyTokenLimit: number;
  dailyTokenUsed: number;
  concurrentSessions: number;
  maxConcurrentSessions: number;
  rateLimitPerMinute: number;
}

export interface TenantUsage {
  todayTokens: number;
  todayRequests: number;
  weekTokens: number;
  weekRequests: number;
  monthTokens: number;
  monthRequests: number;
}

export interface TenantSecurity {
  sandboxEnabled: boolean;
  networkRestricted: boolean;
  fileSystemRestricted: boolean;
  allowedDomains: string[];
  blockedDomains: string[];
}

export interface TenantAuditResponse {
  ok: boolean;
  entries: Array<{
    timestamp: number;
    action: string;
    actor: string;
    details: string;
  }>;
  total: number;
}

export interface TenantConfigResponse {
  ok: boolean;
  config: Record<string, unknown>;
}

export interface TenantConfigPayload {
  modelAlias?: string;
  [key: string]: unknown;
}

export interface ModelInfoResponse {
  ok: boolean;
  model: string;
  provider: string;
  contextWindow: number;
  maxTokens: number;
  temperature: number;
}

// ── API ────────────────────────────────────────────────────

export const adminApi = {
  // ── Tenants ──
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
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}`, { method: "DELETE" }),
  suspendTenant: (tenantId: string) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/suspend`, { method: "POST" }),
  resumeTenant: (tenantId: string) =>
    fetchJSON<TenantActionResponse>(`/api/tenants/${encodeURIComponent(tenantId)}/resume`, { method: "POST" }),
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

  // ── Platform config ──
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

  // ── Admin: tenants CRUD (new endpoints) ──
  getAdminTenantConfig: (tenantId: string) =>
    fetchJSON<{ ok: boolean; config: Record<string, unknown> }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/config`),
  updateAdminTenantConfig: (tenantId: string, config: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/config`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(config),
    }),
  patchAdminTenantConfig: (tenantId: string, patch: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/config`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    }),
  getAdminTenantKeys: (tenantId: string) =>
    fetchJSON<{ ok: boolean; keys: Array<{ provider: string; hasKey: boolean; masked: string }> }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/keys`),
  setAdminTenantKey: (tenantId: string, provider: string, apiKey: string) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/keys/${encodeURIComponent(provider)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ apiKey }),
    }),
  deleteAdminTenantKey: (tenantId: string, provider: string) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/keys/${encodeURIComponent(provider)}`, { method: "DELETE" }),
  getAdminTenantRoutes: (tenantId: string) =>
    fetchJSON<{ ok: boolean; routes: Array<{ alias: string; model: string; provider: string; baseUrl?: string }> }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/routes`),
  setAdminTenantRoute: (tenantId: string, alias: string, route: { model: string; provider: string; baseUrl?: string }) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/routes/${encodeURIComponent(alias)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(route),
    }),
  deleteAdminTenantRoute: (tenantId: string, alias: string) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/routes/${encodeURIComponent(alias)}`, { method: "DELETE" }),
  getAdminTenantQuota: (tenantId: string) =>
    fetchJSON<{ ok: boolean; quota: Record<string, unknown> }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/quota`),
  updateAdminTenantQuota: (tenantId: string, quota: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/quota`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(quota),
    }),
  getAdminTenantBilling: (tenantId: string) =>
    fetchJSON<{ ok: boolean; billing: Record<string, unknown> }>(`/api/admin/tenants/${encodeURIComponent(tenantId)}/billing`),
  getAdminProviders: () =>
    fetchJSON<{ ok: boolean; providers: Array<{ id: string; name: string; baseUrl: string }> }>(`/api/admin/platform/providers`),
  getAdminPlatformRoutes: () =>
    fetchJSON<{ ok: boolean; routes: Array<{ alias: string; model: string; provider: string }> }>(`/api/admin/platform/routes`),
  invalidateCache: (tenantId?: string) =>
    fetchJSON<{ ok: boolean }>(tenantId ? `/api/admin/cache/invalidate/${encodeURIComponent(tenantId)}` : "/api/admin/cache/invalidate", { method: "POST" }),
  getCacheStats: () =>
    fetchJSON<{ ok: boolean; stats: Record<string, unknown> }>("/api/admin/cache/stats"),

  // ── Evolution & Improvement ──
  getImprovementSignals: (params?: { scope?: string; userId?: string; spaceId?: string }) => {
    const p = params ?? {};
    const qs = new URLSearchParams();
    if (p.scope) qs.set("scope", p.scope);
    if (p.userId) qs.set("userId", p.userId);
    if (p.spaceId) qs.set("spaceId", p.spaceId);
    return fetchJSON<{ ok: boolean; signals: unknown[]; scope: string }>("/api/improvement/signals?" + qs.toString());
  },
  getImprovementProposals: (scope?: string) => {
    const qs = scope ? "?scope=" + encodeURIComponent(scope) : "";
    return fetchJSON<{ ok: boolean; proposals: unknown[]; scope: string }>("/api/improvement/proposals" + qs);
  },
  getImprovementAdaptations: (userId: string) =>
    fetchJSON<{ ok: boolean; preferences: unknown; capabilities: unknown }>("/api/improvement/adaptations?userId=" + encodeURIComponent(userId)),
  acceptProposal: (tenantId: string, proposalId: string) =>
    fetchJSON<{ ok: boolean }>("/api/improvement/" + encodeURIComponent(tenantId) + "/proposals/" + encodeURIComponent(proposalId) + "/accept", { method: "POST" }),
  rejectProposal: (tenantId: string, proposalId: string) =>
    fetchJSON<{ ok: boolean }>("/api/improvement/" + encodeURIComponent(tenantId) + "/proposals/" + encodeURIComponent(proposalId) + "/reject", { method: "POST" }),

  // ── Org governance (summary endpoints) ──
  getOrgSummary: () => fetchJSON<unknown>("/api/org/summary"),
  getOrgIdentity: () => fetchJSON<unknown>("/api/org/identity"),
  getOrgAuth: () => fetchJSON<unknown>("/api/org/auth"),
  getOrgKnowledge: () => fetchJSON<unknown>("/api/org/knowledge"),
  getOrgWorkflow: () => fetchJSON<unknown>("/api/org/workflow"),
  getOrgMarket: () => fetchJSON<unknown>("/api/org/market"),
  getOrgCost: () => fetchJSON<unknown>("/api/org/cost"),
  getOrgObserve: () => fetchJSON<unknown>("/api/org/observe"),
  getOrgDistributed: () => fetchJSON<unknown>("/api/org/distributed"),
  getOrgEvolution: () => fetchJSON<unknown>("/api/org/evolution"),
  getOrgCompliance: () => fetchJSON<unknown>("/api/org/compliance"),
  getOrgHandoff: () => fetchJSON<unknown>("/api/org/handoff"),

  // ── Org Control Center ──
  getControlOverview: () => fetchJSON<unknown>("/api/org/control/overview"),
  getControlTeams: () => fetchJSON<unknown>("/api/org/control/teams"),
  getControlIntents: () => fetchJSON<unknown>("/api/org/control/intents"),
  getControlDelegatedTasks: () => fetchJSON<unknown>("/api/org/control/delegated-tasks"),
  submitDelegatedTask: (tenantId: string, taskId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/org/control/delegated-tasks/${encodeURIComponent(tenantId)}/${encodeURIComponent(taskId)}/submit`, { method: "POST" }),
  verifyDelegatedTask: (tenantId: string, taskId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/org/control/delegated-tasks/${encodeURIComponent(tenantId)}/${encodeURIComponent(taskId)}/verify`, { method: "POST" }),
  executeDelegatedTask: (tenantId: string, taskId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/org/control/delegated-tasks/${encodeURIComponent(tenantId)}/${encodeURIComponent(taskId)}/execute`, { method: "POST" }),
  replayIntent: (tenantId: string, runId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/org/control/intents/${encodeURIComponent(tenantId)}/${encodeURIComponent(runId)}/replay`, { method: "POST" }),
  rerouteIntent: (tenantId: string, runId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/org/control/intents/${encodeURIComponent(tenantId)}/${encodeURIComponent(runId)}/reroute`, { method: "POST" }),
  agentOverride: (tenantId: string, agentId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/org/control/agents/${encodeURIComponent(tenantId)}/${encodeURIComponent(agentId)}/override`, { method: "POST" }),
  getControlTraces: () => fetchJSON<unknown>("/api/org/control/traces"),
  getControlEvolution: () => fetchJSON<unknown>("/api/org/control/evolution"),
  getControlAnomalies: () => fetchJSON<unknown>("/api/org/control/anomalies"),
  getControlAudit: () => fetchJSON<unknown>("/api/org/control/audit"),
};
