/**
 * Shared HTTP client primitives used across portal, ops, and noc.
 *
 * - fetchJSON<T>: auto-injects Bearer token from window.__HERMES_SESSION_TOKEN__,
 *   parses JSON, throws on non-2xx.
 * - getSessionToken(): returns the injected token synchronously (portal/legacy).
 * - waitForSessionToken(): async version that retries briefly if the token
 *   hasn't been injected yet (useful for dev-mode HMR races).
 */

export const API_BASE = "";

declare global {
  interface Window {
    __HERMES_SESSION_TOKEN__?: string;
  }
}

export async function fetchJSON<T>(url: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  const token = window.__HERMES_SESSION_TOKEN__;
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(`${API_BASE}${url}`, { ...init, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${text}`);
  }
  if (res.status === 204) return {} as T;
  const text = await res.text();
  if (!text.trim()) return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`Invalid JSON from ${url}: ${msg}`);
  }
}

export function getSessionToken(): string {
  const t = window.__HERMES_SESSION_TOKEN__;
  if (!t) {
    throw new Error("Session token not available — page must be served by the Hermes dashboard server");
  }
  return t;
}

/** Async alias so callers can `await getSessionToken()` uniformly. */
export async function waitForSessionToken(): Promise<string> {
  return getSessionToken();
}

/**
 * Gateway-direct fetch helper — for endpoints hosted by GatewayServerV2
 * (compare runs, chat playground). Honors VITE_HERMES_GATEWAY_URL, defaulting
 * to http://127.0.0.1:8080. Copies Authorization header from the session token.
 */
export async function gatewayFetch<T>(
  path: string,
  init?: RequestInit,
  gatewayUrl: string = import.meta.env?.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080",
): Promise<T> {
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
