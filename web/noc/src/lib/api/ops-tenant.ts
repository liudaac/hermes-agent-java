/**
 * Ops Console — tenant & gateway admin API.
 *
 *  - Multi-tenant management (tenants/*)
 *  - OAuth provider management
 *  - Gateway/Hermes self-update actions
 */
import { fetchJSON, getSessionToken } from "./_base";
import type {
  TenantsResponse,
  TenantActionResponse,
  TenantSummary,
  TenantSkillsResponse,
  TenantQuota,
  TenantUsage,
  TenantSecurity,
  TenantAuditResponse,
  TenantConfigResponse,
  TenantConfigPayload,
} from "./types/ops";
import type { ActionResponse, ActionStatusResponse } from "./types/common";
import type {
  OAuthProvidersResponse,
  OAuthStartResponse,
  OAuthSubmitResponse,
  OAuthPollResponse,
} from "./types/noc";

export const opsTenantApi = {
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

  // ── Gateway / self-update actions ──
  restartGateway: () =>
    fetchJSON<ActionResponse>("/api/gateway/restart", { method: "POST" }),
  updateHermes: () =>
    fetchJSON<ActionResponse>("/api/hermes/update", { method: "POST" }),
  getActionStatus: (name: string, lines = 200) =>
    fetchJSON<ActionStatusResponse>(`/api/actions/${encodeURIComponent(name)}/status?lines=${lines}`),

  // ── OAuth provider management ──
  getOAuthProviders: () =>
    fetchJSON<OAuthProvidersResponse>("/api/providers/oauth"),
  disconnectOAuthProvider: async (providerId: string) => {
    const token = await getSessionToken();
    return fetchJSON<{ ok: boolean; provider: string }>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}`,
      { method: "DELETE", headers: { Authorization: `Bearer ${token}` } },
    );
  },
  startOAuthLogin: async (providerId: string) => {
    const token = await getSessionToken();
    return fetchJSON<OAuthStartResponse>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}/start`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
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
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
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
      { method: "DELETE", headers: { Authorization: `Bearer ${token}` } },
    );
  },
};
