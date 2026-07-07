/**
 * Portal HTTP client — fetches the business APIs under /api/v1/business/*
 * and /api/v1/workspaces/*. The session token is injected into the page
 * HTML by the Python backend (mirrors ops behavior).
 */
const BASE = "";

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
  const res = await fetch(`${BASE}${url}`, { ...init, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${text}`);
  }
  if (res.status === 204) return {} as T;
  const text = await res.text();
  if (!text.trim()) return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch (err: any) {
    throw new Error(`Invalid JSON from ${url}: ${err?.message ?? String(err)}`);
  }
}

export function getSessionToken(): string {
  const t = window.__HERMES_SESSION_TOKEN__;
  if (!t) throw new Error("No session token — portal must be served by `hermes dashboard`.");
  return t;
}
