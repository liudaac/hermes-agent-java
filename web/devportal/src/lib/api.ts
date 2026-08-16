/**
 * DevPortal API surface.
 *
 * Re-exports shared HTTP primitives from @hermes/ui and provides
 * DevPortal-specific API methods (env, oauth, webhooks).
 */
import { fetchJSON, getSessionToken } from "@hermes/ui";

// ── Types ─────────────────────────────────────────────────────────

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
  flow: "pkce" | "device_code" | "external";
  cli_command: string;
  docs_url: string;
  status: OAuthProviderStatus;
}

export interface OAuthProvidersResponse {
  providers: OAuthProvider[];
}

export type OAuthStartResponse =
  | { session_id: string; flow: "pkce"; auth_url: string; expires_in: number }
  | { session_id: string; flow: "device_code"; user_code: string; verification_url: string; expires_in: number; poll_interval: number };

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

export interface WebhookSubscription {
  id?: string;
  url: string;
  events: string[];
  secret?: string;
  status?: string;
  created_at?: string;
}

// ── API methods ───────────────────────────────────────────────────

export const devPortalApi = {
  // ── Env vars ──
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
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ key }),
    });
  },

  // ── OAuth ──
  getOAuthProviders: async (): Promise<OAuthProvidersResponse> => {
    const token = await getSessionToken();
    return fetchJSON<OAuthProvidersResponse>("/api/providers/oauth", {
      headers: { Authorization: `Bearer ${token}` },
    });
  },

  disconnectOAuthProvider: async (providerId: string) => {
    const token = await getSessionToken();
    return fetchJSON<{ ok: boolean; provider: OAuthProvider }>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}`,
      { method: "DELETE", headers: { Authorization: `Bearer ${token}` } },
    );
  },

  startOAuthLogin: async (providerId: string) => {
    const token = await getSessionToken();
    return fetchJSON<OAuthStartResponse>(
      `/api/providers/oauth/${encodeURIComponent(providerId)}/start`,
      { method: "POST", headers: { Authorization: `Bearer ${token}` } },
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

  // ── Webhooks ──
  getWebhooks: () => fetchJSON<{ subscriptions: WebhookSubscription[] }>("/api/v1/webhooks"),

  registerWebhook: (url: string, events: string[], secret?: string) =>
    fetchJSON<{ status: string; url: string; events: string[] }>("/api/v1/webhooks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, events, secret }),
    }),

  deleteWebhook: (url: string) =>
    fetchJSON<{ ok: boolean }>("/api/v1/webhooks", {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    }),

  testWebhook: (url: string) =>
    fetchJSON<{ ok: boolean }>("/api/v1/webhooks/test", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    }),
};

// ── Flat `api` object for `import { api } from "@/lib/api"` ───────
export const api = {
  ...devPortalApi,
};

// Re-export shared primitives
export { fetchJSON, getSessionToken };
