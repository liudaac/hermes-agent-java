export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE" | "PATCH";

export interface ApiEndpoint {
  method: HttpMethod;
  path: string;
  description: string;
  category: string;
  params?: { name: string; type: "path" | "query" | "body"; description: string; required?: boolean }[];
}

export const ENDPOINT_CATEGORIES = [
  "Agent Runtime",
  "Business",
  "Memory",
  "Skills",
  "Tools",
  "Org",
  "Sessions",
  "Logs",
  "Analytics",
  "Cron",
  "OAuth",
  "Env",
  "Jarvis",
  "Webhooks",
  "Admin",
  "Spaces",
  "Users",
  "Metrics",
  "Session Assets",
  "Improvement",
  "Learning",
] as const;

export const ENDPOINTS: ApiEndpoint[] = [
  // ── Agent Runtime ──
  { method: "POST", path: "/api/chat/stream", description: "Stream a chat message to an agent (SSE)", category: "Agent Runtime", params: [{ name: "message", type: "body", description: "The message to send", required: true }, { name: "tenant_id", type: "body", description: "Tenant ID", required: true }, { name: "session_id", type: "body", description: "Optional session ID" }] },
  { method: "GET", path: "/api/status", description: "Get platform status (gateway, agents, platforms)", category: "Agent Runtime" },
  { method: "GET", path: "/api/dashboard/plugins", description: "List dashboard plugins", category: "Agent Runtime" },
  { method: "POST", path: "/api/dashboard/plugins/rescan", description: "Rescan and reload plugins", category: "Agent Runtime" },
  { method: "GET", path: "/api/dashboard/themes", description: "List available themes", category: "Agent Runtime" },
  { method: "PUT", path: "/api/dashboard/theme", description: "Set active theme", category: "Agent Runtime", params: [{ name: "name", type: "body", description: "Theme name", required: true }] },
  { method: "GET", path: "/api/model/info", description: "Get current model information", category: "Agent Runtime" },

  // ── Sessions ──
  { method: "GET", path: "/api/sessions", description: "List recent sessions", category: "Sessions", params: [{ name: "limit", type: "query", description: "Max results (default 20)" }, { name: "offset", type: "query", description: "Pagination offset" }] },
  { method: "GET", path: "/api/sessions/{id}/messages", description: "Get messages for a session", category: "Sessions", params: [{ name: "id", type: "path", description: "Session ID", required: true }] },
  { method: "DELETE", path: "/api/sessions/{id}", description: "Delete a session", category: "Sessions", params: [{ name: "id", type: "path", description: "Session ID", required: true }] },
  { method: "GET", path: "/api/sessions/search", description: "Search session messages", category: "Sessions", params: [{ name: "q", type: "query", description: "Search query", required: true }] },

  // ── Memory ──
  { method: "GET", path: "/api/memory/{tenantId}", description: "Get tenant memory entries", category: "Memory", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "POST", path: "/api/memory/{tenantId}", description: "Add a memory entry", category: "Memory", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "DELETE", path: "/api/memory/{tenantId}/{entryId}", description: "Delete a memory entry", category: "Memory", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }, { name: "entryId", type: "path", description: "Entry ID", required: true }] },
  { method: "POST", path: "/api/memory/{tenantId}/search", description: "Search tenant memory", category: "Memory", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },

  // ── Skills ──
  { method: "GET", path: "/api/skills", description: "List all capabilities", category: "Skills" },
  { method: "PUT", path: "/api/skills/toggle", description: "Enable/disable a skill", category: "Skills", params: [{ name: "name", type: "body", description: "Skill name", required: true }, { name: "enabled", type: "body", description: "Enable or disable", required: true }] },

  // ── Tools ──
  { method: "GET", path: "/api/tools", description: "List all tool groups", category: "Tools" },
  { method: "GET", path: "/api/tools/toolsets", description: "List all toolsets", category: "Tools" },
  { method: "GET", path: "/api/tools/{name}", description: "Get tool details", category: "Tools", params: [{ name: "name", type: "path", description: "Tool name", required: true }] },

  // ── Org ──
  { method: "GET", path: "/api/org/{tenantId}/overview", description: "Get org overview for tenant", category: "Org", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "GET", path: "/api/org/{tenantId}/identities", description: "List org identities", category: "Org", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "GET", path: "/api/org/{tenantId}/teams", description: "List agent teams", category: "Org", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "POST", path: "/api/org/{tenantId}/teams", description: "Create an agent team", category: "Org", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "GET", path: "/api/org/{tenantId}/roles", description: "List agent roles", category: "Org", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "POST", path: "/api/org/{tenantId}/roles", description: "Create an agent role", category: "Org", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },

  // ── Logs ──
  { method: "GET", path: "/api/logs", description: "Get log lines", category: "Logs", params: [{ name: "file", type: "query", description: "Log file name", required: true }, { name: "lines", type: "query", description: "Number of lines" }, { name: "level", type: "query", description: "Log level filter" }] },
  { method: "GET", path: "/api/logs/files", description: "List available log files", category: "Logs" },
  { method: "DELETE", path: "/api/logs", description: "Delete a log file", category: "Logs", params: [{ name: "file", type: "query", description: "Log file name", required: true }] },
  { method: "GET", path: "/api/logs/aggregate", description: "Aggregate multiple log files", category: "Logs" },
  { method: "GET", path: "/api/logs/tail", description: "Stream log lines (SSE)", category: "Logs", params: [{ name: "file", type: "query", description: "Log file name", required: true }] },

  // ── Analytics ──
  { method: "GET", path: "/api/analytics/usage", description: "Get usage analytics", category: "Analytics", params: [{ name: "days", type: "query", description: "Number of days (default 30)" }] },

  // ── Cron ──
  { method: "GET", path: "/api/cron/jobs", description: "List cron jobs", category: "Cron" },
  { method: "POST", path: "/api/cron/jobs", description: "Create a cron job", category: "Cron", params: [{ name: "prompt", type: "body", description: "Agent prompt", required: true }, { name: "schedule", type: "body", description: "Cron expression", required: true }, { name: "name", type: "body", description: "Optional name" }, { name: "deliver", type: "body", description: "Delivery channel" }] },
  { method: "POST", path: "/api/cron/jobs/{id}/pause", description: "Pause a cron job", category: "Cron", params: [{ name: "id", type: "path", description: "Job ID", required: true }] },
  { method: "POST", path: "/api/cron/jobs/{id}/resume", description: "Resume a cron job", category: "Cron", params: [{ name: "id", type: "path", description: "Job ID", required: true }] },
  { method: "POST", path: "/api/cron/jobs/{id}/trigger", description: "Trigger a cron job now", category: "Cron", params: [{ name: "id", type: "path", description: "Job ID", required: true }] },
  { method: "DELETE", path: "/api/cron/jobs/{id}", description: "Delete a cron job", category: "Cron", params: [{ name: "id", type: "path", description: "Job ID", required: true }] },
  { method: "GET", path: "/api/cron/jobs/{id}/runs", description: "Get cron job run history", category: "Cron", params: [{ name: "id", type: "path", description: "Job ID", required: true }] },
  { method: "GET", path: "/api/cron/preview", description: "Preview cron schedule", category: "Cron", params: [{ name: "schedule", type: "query", description: "Cron expression", required: true }, { name: "count", type: "query", description: "Number of previews" }] },

  // ── OAuth ──
  { method: "GET", path: "/api/providers/oauth", description: "List OAuth providers", category: "OAuth" },
  { method: "DELETE", path: "/api/providers/oauth/{providerId}", description: "Disconnect an OAuth provider", category: "OAuth", params: [{ name: "providerId", type: "path", description: "Provider ID", required: true }] },
  { method: "POST", path: "/api/providers/oauth/{providerId}/start", description: "Start OAuth login flow", category: "OAuth", params: [{ name: "providerId", type: "path", description: "Provider ID", required: true }] },
  { method: "POST", path: "/api/providers/oauth/{providerId}/submit", description: "Submit OAuth authorization code", category: "OAuth", params: [{ name: "providerId", type: "path", description: "Provider ID", required: true }, { name: "session_id", type: "body", description: "Session ID", required: true }, { name: "code", type: "body", description: "Auth code", required: true }] },
  { method: "GET", path: "/api/providers/oauth/{providerId}/poll/{sessionId}", description: "Poll OAuth session status", category: "OAuth", params: [{ name: "providerId", type: "path", description: "Provider ID", required: true }, { name: "sessionId", type: "path", description: "Session ID", required: true }] },
  { method: "DELETE", path: "/api/providers/oauth/sessions/{sessionId}", description: "Cancel an OAuth session", category: "OAuth", params: [{ name: "sessionId", type: "path", description: "Session ID", required: true }] },

  // ── Env ──
  { method: "GET", path: "/api/env", description: "List environment variables", category: "Env" },
  { method: "PUT", path: "/api/env", description: "Set an environment variable", category: "Env", params: [{ name: "key", type: "body", description: "Variable name", required: true }, { name: "value", type: "body", description: "Variable value", required: true }] },
  { method: "DELETE", path: "/api/env", description: "Delete an environment variable", category: "Env", params: [{ name: "key", type: "body", description: "Variable name", required: true }] },
  { method: "POST", path: "/api/env/reveal", description: "Reveal an environment variable's true value", category: "Env", params: [{ name: "key", type: "body", description: "Variable name", required: true }] },

  // ── Webhooks ──
  { method: "GET", path: "/api/v1/webhooks", description: "List registered webhooks", category: "Webhooks" },
  { method: "POST", path: "/api/v1/webhooks", description: "Register a webhook", category: "Webhooks", params: [{ name: "url", type: "body", description: "Webhook URL", required: true }, { name: "events", type: "body", description: "Event types", required: true }, { name: "secret", type: "body", description: "Optional signing secret" }] },
  { method: "DELETE", path: "/api/v1/webhooks", description: "Delete a webhook", category: "Webhooks", params: [{ name: "url", type: "body", description: "Webhook URL", required: true }] },

  // ── Config ──
  { method: "GET", path: "/api/config", description: "Get platform configuration", category: "Admin" },
  { method: "PUT", path: "/api/config", description: "Save platform configuration", category: "Admin", params: [{ name: "config", type: "body", description: "Config object", required: true }] },
  { method: "GET", path: "/api/config/defaults", description: "Get default configuration", category: "Admin" },
  { method: "GET", path: "/api/config/schema", description: "Get configuration schema", category: "Admin" },
  { method: "GET", path: "/api/config/raw", description: "Get raw YAML config", category: "Admin" },
  { method: "PUT", path: "/api/config/raw", description: "Save raw YAML config", category: "Admin", params: [{ name: "yaml_text", type: "body", description: "YAML content", required: true }] },

  // ── Spaces ──
  { method: "GET", path: "/api/spaces", description: "List all spaces", category: "Spaces" },
  { method: "POST", path: "/api/spaces", description: "Create a space", category: "Spaces" },
  { method: "GET", path: "/api/spaces/{id}", description: "Get space details", category: "Spaces", params: [{ name: "id", type: "path", description: "Space ID", required: true }] },
  { method: "PUT", path: "/api/spaces/{id}", description: "Update a space", category: "Spaces", params: [{ name: "id", type: "path", description: "Space ID", required: true }] },
  { method: "DELETE", path: "/api/spaces/{id}", description: "Delete a space", category: "Spaces", params: [{ name: "id", type: "path", description: "Space ID", required: true }] },

  // ── Users ──
  { method: "GET", path: "/api/users", description: "List users", category: "Users" },
  { method: "POST", path: "/api/users", description: "Create a user", category: "Users" },
  { method: "GET", path: "/api/users/{id}", description: "Get user details", category: "Users", params: [{ name: "id", type: "path", description: "User ID", required: true }] },
  { method: "PUT", path: "/api/users/{id}", description: "Update a user", category: "Users", params: [{ name: "id", type: "path", description: "User ID", required: true }] },
  { method: "DELETE", path: "/api/users/{id}", description: "Delete a user", category: "Users", params: [{ name: "id", type: "path", description: "User ID", required: true }] },

  // ── Tenants ──
  { method: "GET", path: "/api/tenants", description: "List all tenants", category: "Admin" },
  { method: "POST", path: "/api/tenants", description: "Create a tenant", category: "Admin" },
  { method: "GET", path: "/api/tenants/{tenantId}", description: "Get tenant details", category: "Admin", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "PUT", path: "/api/tenants/{tenantId}", description: "Update tenant", category: "Admin", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "DELETE", path: "/api/tenants/{tenantId}", description: "Delete tenant", category: "Admin", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "POST", path: "/api/tenants/{tenantId}/suspend", description: "Suspend a tenant", category: "Admin", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },
  { method: "POST", path: "/api/tenants/{tenantId}/resume", description: "Resume a tenant", category: "Admin", params: [{ name: "tenantId", type: "path", description: "Tenant ID", required: true }] },

  // ── Metrics ──
  { method: "GET", path: "/api/metrics", description: "Get platform metrics", category: "Metrics" },
  { method: "GET", path: "/api/metrics/agents", description: "Get agent metrics", category: "Metrics" },
  { method: "GET", path: "/api/metrics/sessions", description: "Get session metrics", category: "Metrics" },

  // ── Session Assets ──
  { method: "GET", path: "/api/sessions/{id}/assets", description: "List session assets", category: "Session Assets", params: [{ name: "id", type: "path", description: "Session ID", required: true }] },
  { method: "GET", path: "/api/sessions/{id}/assets/{assetId}", description: "Download a session asset", category: "Session Assets", params: [{ name: "id", type: "path", description: "Session ID", required: true }, { name: "assetId", type: "path", description: "Asset ID", required: true }] },

  // ── Business ──
  { method: "GET", path: "/api/v1/business/dlq", description: "Get dead letter queue items", category: "Business" },
  { method: "POST", path: "/api/v1/business/dlq/{itemId}/retry", description: "Retry a DLQ item", category: "Business", params: [{ name: "itemId", type: "path", description: "Item ID", required: true }] },
  { method: "POST", path: "/api/v1/business/dlq/{itemId}/resolve", description: "Resolve a DLQ item", category: "Business", params: [{ name: "itemId", type: "path", description: "Item ID", required: true }] },
  { method: "GET", path: "/api/v1/business/takeovers", description: "List active takeovers", category: "Business" },
  { method: "POST", path: "/api/v1/business/takeovers/{id}/confirm", description: "Confirm a takeover", category: "Business", params: [{ name: "id", type: "path", description: "Takeover ID", required: true }] },
  { method: "POST", path: "/api/v1/business/takeovers/{id}/release", description: "Release a takeover", category: "Business", params: [{ name: "id", type: "path", description: "Takeover ID", required: true }] },
  { method: "GET", path: "/api/v1/business/workflows", description: "List workflows", category: "Business" },
  { method: "POST", path: "/api/v1/business/workflows/{id}/checkpoint", description: "Approve workflow checkpoint", category: "Business", params: [{ name: "id", type: "path", description: "Workflow ID", required: true }] },
  { method: "GET", path: "/api/v1/business/sla/templates", description: "List SLA templates", category: "Business" },

  // ── Improvement ──
  { method: "GET", path: "/api/improvement/suggestions", description: "List improvement suggestions", category: "Improvement" },
  { method: "POST", path: "/api/improvement/suggestions/{id}/apply", description: "Apply an improvement suggestion", category: "Improvement", params: [{ name: "id", type: "path", description: "Suggestion ID", required: true }] },

  // ── Learning ──
  { method: "GET", path: "/api/learning/lessons", description: "List learned lessons", category: "Learning" },
  { method: "POST", path: "/api/learning/lessons", description: "Record a new lesson", category: "Learning" },

  // ── Jarvis ──
  { method: "GET", path: "/api/jarvis/status", description: "Get Jarvis overlay status", category: "Jarvis" },
  { method: "POST", path: "/api/jarvis/toggle", description: "Toggle Jarvis overlay", category: "Jarvis" },
];
