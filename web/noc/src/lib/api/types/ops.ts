/**
 * Ops Console types — multi-tenant platform administration.
 */
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

