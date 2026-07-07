/**
 * Common types shared across spaces (Portal, Ops, NOC).
 */
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
