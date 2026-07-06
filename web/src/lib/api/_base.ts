/**
 * Base HTTP client — shared by all space-scoped API modules.
 *
 * Aligned with the three-space front-end refactor:
 *   - portal.ts  (Business Portal — /api/v1/business/*)
 *   - ops.ts     (Ops Console — /api/tenants, /api/skills, /api/cron, ...)
 *   - noc.ts     (Org Control Center — NOC/SOC APIs)
 *   - sse.ts     (SSE streams)
 *
 * Each module imports fetchJSON from here. The legacy `api` object is
 * re-exported from `index.ts` for backward compatibility — existing
 * callers continue to work as the code is migrated.
 */

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

/**
 * Get the session token for endpoints that need explicit Bearer auth
 * (SSE streams, OAuth, gateway-direct calls).
 */
export async function getSessionToken(): Promise<string> {
  if (_sessionToken) return _sessionToken;
  const injected = window.__HERMES_SESSION_TOKEN__;
  if (injected) {
    _sessionToken = injected;
    return _sessionToken;
  }
  throw new Error("Session token not available — page must be served by the Hermes dashboard server");
}

/**
 * Gateway-direct fetch — for endpoints hosted by GatewayServerV2
 * (compare runs, chat playground). Reads the gateway URL from
 * VITE_HERMES_GATEWAY_URL, falling back to 127.0.0.1:8080.
 */
export async function gatewayFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const gatewayUrl = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";
  const headers = new Headers(init?.headers);
  const token = window.__HERMES_SESSION_TOKEN__;
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(`${gatewayUrl}${path}`, { ...init, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}
