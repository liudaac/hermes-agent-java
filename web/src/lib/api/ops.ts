/**
 * Ops Console — platform core API.
 *
 * Tenant-agnostic platform operations:
 *  - Status, sessions, logs, analytics
 *  - Config, env vars
 *  - Cron jobs
 *  - Skills & toolsets
 */
import { fetchJSON, getSessionToken } from "./_base";
import type { StatusResponse } from "./types/common";
import type {
  PaginatedSessions,
  SessionMessagesResponse,
  EnvVarInfo,
  LogsResponse,
  LogFilesResponse,
  LogAggregateResponse,
  AnalyticsResponse,
  CronJob,
  CronRunRecord,
  CronTriggerResult,
  CronSchedulePreview,
  SkillInfo,
  ToolsetInfo,
  ToolGroup,
  ToolDetail,
  SessionSearchResult,
  SessionSearchResponse,
  ModelInfoResponse,
} from "./types/ops";

export const opsApi = {
  // ── Status ──
  getStatus: () => fetchJSON<StatusResponse>("/api/status"),

  // ── Sessions ──
  getSessions: (limit = 20, offset = 0) =>
    fetchJSON<PaginatedSessions>(`/api/sessions?limit=${limit}&offset=${offset}`),
  getSessionMessages: (id: string) =>
    fetchJSON<SessionMessagesResponse>(`/api/sessions/${encodeURIComponent(id)}/messages`),
  deleteSession: (id: string) =>
    fetchJSON<{ ok: boolean }>(`/api/sessions/${encodeURIComponent(id)}`, { method: "DELETE" }),
  searchSessions: (q: string) =>
    fetchJSON<SessionSearchResponse>(`/api/sessions/search?q=${encodeURIComponent(q)}`),

  // ── Logs ──
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
    fetchJSON<{ ok: boolean; file: string }>(`/api/logs?file=${encodeURIComponent(file)}`, { method: "DELETE" }),
  getLogAggregate: (params: { files?: string[]; lines?: number; level?: string; component?: string }) => {
    const qs = new URLSearchParams();
    if (params.files && params.files.length > 0) qs.set("files", params.files.join(","));
    if (params.lines) qs.set("lines", String(params.lines));
    if (params.level && params.level !== "ALL") qs.set("level", params.level);
    if (params.component && params.component !== "all") qs.set("component", params.component);
    return fetchJSON<LogAggregateResponse>(`/api/logs/aggregate?${qs.toString()}`);
  },
  openLogTail: async (params: { file: string; level?: string; component?: string }): Promise<EventSource> => {
    const token = await getSessionToken();
    const qs = new URLSearchParams();
    qs.set("file", params.file);
    qs.set("token", token);
    if (params.level && params.level !== "ALL") qs.set("level", params.level);
    if (params.component && params.component !== "all") qs.set("component", params.component);
    return new EventSource(`/api/logs/tail?${qs.toString()}`);
  },

  // ── Analytics ──
  getAnalytics: (days: number) =>
    fetchJSON<AnalyticsResponse>(`/api/analytics/usage?days=${days}`),

  // ── Config ──
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

  // ── Cron ──
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
    fetchJSON<CronSchedulePreview>(`/api/cron/preview?schedule=${encodeURIComponent(schedule)}&count=${count}`),

  // ── Skills & Toolsets ──
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
};

/** Re-export session search type for callers. */
export type { SessionSearchResult };
