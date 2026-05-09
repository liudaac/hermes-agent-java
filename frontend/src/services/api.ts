// Unified API client for Hermes Agent
const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

// Types
export interface StatusResponse {
  version: string
  gateway_running: boolean
  gateway_state?: string
  gateway_pid?: number
  gateway_health_url?: string
  gateway_exit_reason?: string
  gateway_platforms?: Record<string, PlatformStatus>
  active_sessions: number
}

export interface PlatformStatus {
  state: 'connected' | 'disconnected' | 'fatal'
  error_message?: string
  updated_at?: string
}

export interface SessionInfo {
  id: string
  title?: string
  model?: string
  message_count: number
  last_active: string
  is_active: boolean
  source?: string
  preview?: string
}

export interface ActionStatusResponse {
  running: boolean
  exit_code?: number
  lines: string[]
}

export interface ConfigSchema {
  sections: ConfigSection[]
}

export interface ConfigSection {
  name: string
  title: string
  fields: ConfigField[]
}

export interface ConfigField {
  key: string
  type: 'string' | 'number' | 'boolean' | 'select' | 'password'
  label: string
  description?: string
  default?: string | number | boolean
  options?: { label: string; value: string }[]
  secret?: boolean
}

export interface SkillInfo {
  name: string
  version: string
  description?: string
  enabled: boolean
}

export interface CronJob {
  id: string
  name: string
  schedule: string
  command: string
  enabled: boolean
  last_run?: string
  next_run?: string
}

// API Client
class ApiClient {
  private async fetch<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers
      }
    })
    if (!response.ok) {
      throw new Error(`API error: ${response.status} ${response.statusText}`)
    }
    return response.json()
  }

  // Status
  async getStatus(): Promise<StatusResponse> {
    return this.fetch('/status')
  }

  async getSessions(limit = 50): Promise<{ sessions: SessionInfo[] }> {
    return this.fetch(`/sessions?limit=${limit}`)
  }

  // Actions
  async restartGateway(): Promise<void> {
    await this.fetch('/actions/restart-gateway', { method: 'POST' })
  }

  async updateHermes(): Promise<void> {
    await this.fetch('/actions/update', { method: 'POST' })
  }

  async getActionStatus(name: string): Promise<ActionStatusResponse> {
    return this.fetch(`/actions/${name}/status`)
  }

  // Config
  async getConfigSchema(): Promise<ConfigSchema> {
    return this.fetch('/config/schema')
  }

  async getConfig(): Promise<Record<string, any>> {
    return this.fetch('/config')
  }

  async updateConfig(config: Record<string, any>): Promise<void> {
    await this.fetch('/config', {
      method: 'PUT',
      body: JSON.stringify(config)
    })
  }

  // Environment / API Keys
  async getEnvVars(): Promise<Record<string, string>> {
    return this.fetch('/env')
  }

  async setEnvVar(key: string, value: string): Promise<void> {
    await this.fetch('/env', {
      method: 'PUT',
      body: JSON.stringify({ [key]: value })
    })
  }

  async deleteEnvVar(key: string): Promise<void> {
    await this.fetch(`/env/${key}`, { method: 'DELETE' })
  }

  // Skills
  async getSkills(): Promise<SkillInfo[]> {
    return this.fetch('/skills')
  }

  async toggleSkill(name: string, enabled: boolean): Promise<void> {
    await this.fetch(`/skills/${name}`, {
      method: 'PUT',
      body: JSON.stringify({ enabled })
    })
  }

  // Cron Jobs
  async getCronJobs(): Promise<CronJob[]> {
    return this.fetch('/cron')
  }

  async createCronJob(job: Omit<CronJob, 'id'>): Promise<CronJob> {
    return this.fetch('/cron', {
      method: 'POST',
      body: JSON.stringify(job)
    })
  }

  async updateCronJob(id: string, job: Partial<CronJob>): Promise<void> {
    await this.fetch(`/cron/${id}`, {
      method: 'PUT',
      body: JSON.stringify(job)
    })
  }

  async deleteCronJob(id: string): Promise<void> {
    await this.fetch(`/cron/${id}`, { method: 'DELETE' })
  }

  // Logs
  async getLogs(service?: string, lines = 100): Promise<string[]> {
    const params = new URLSearchParams()
    if (service) params.append('service', service)
    params.append('lines', lines.toString())
    return this.fetch(`/logs?${params}`)
  }

  // Analytics
  async getAnalytics(): Promise<{
    total_sessions: number
    total_messages: number
    active_tenants: number
    tool_calls: Record<string, number>
  }> {
    return this.fetch('/analytics')
  }

  // Chat
  async sendMessage(content: string, sessionId = 'frontend-session'): Promise<{ data: { content: string } }> {
    return this.fetch('/chat', {
      method: 'POST',
      body: JSON.stringify({ content, sessionId })
    })
  }

  // Tenant
  async getTenants(): Promise<{ tenants: Tenant[] }> {
    return this.fetch('/tenants')
  }

  async createTenant(id: string): Promise<void> {
    await this.fetch('/tenants', {
      method: 'POST',
      body: JSON.stringify({ id })
    })
  }

  async deleteTenant(id: string): Promise<void> {
    await this.fetch(`/tenants/${id}`, { method: 'DELETE' })
  }

  async suspendTenant(id: string): Promise<void> {
    await this.fetch(`/tenants/${id}/suspend`, { method: 'POST' })
  }

  async resumeTenant(id: string): Promise<void> {
    await this.fetch(`/tenants/${id}/resume`, { method: 'POST' })
  }

  async getTenantQuota(id: string): Promise<TenantQuota> {
    return this.fetch(`/tenants/${id}/quota`)
  }

  async updateTenantQuota(id: string, quota: Partial<TenantQuota>): Promise<void> {
    await this.fetch(`/tenants/${id}/quota`, {
      method: 'PUT',
      body: JSON.stringify(quota)
    })
  }

  async getTenantUsage(id: string): Promise<TenantUsage> {
    return this.fetch(`/tenants/${id}/usage`)
  }

  async getTenantSecurity(id: string): Promise<TenantSecurity> {
    return this.fetch(`/tenants/${id}/security`)
  }

  async updateTenantSecurity(id: string, security: Partial<TenantSecurity>): Promise<void> {
    await this.fetch(`/tenants/${id}/security`, {
      method: 'PUT',
      body: JSON.stringify(security)
    })
  }

  async getTenantAuditLogs(id: string, limit = 100): Promise<{ events: AuditEvent[] }> {
    return this.fetch(`/tenants/${id}/audit?limit=${limit}`)
  }
}

export const api = new ApiClient()

// Legacy exports for compatibility
export const agentApi = {
  sendMessage: async (request: { content: string; sessionId?: string }) => {
    const response = await api.sendMessage(request.content, request.sessionId)
    return response
  }
}

export const tenantApi = {
  listTenants: () => api.getTenants(),
  createTenant: (id: string) => api.createTenant(id),
  deleteTenant: (id: string) => api.deleteTenant(id),
  suspendTenant: (id: string) => api.suspendTenant(id),
  resumeTenant: (id: string) => api.resumeTenant(id),
  getQuota: (id: string) => api.getTenantQuota(id),
  updateQuota: (id: string, quota: Partial<TenantQuota>) => api.updateTenantQuota(id, quota),
  getUsage: (id: string) => api.getTenantUsage(id),
  getSecurity: (id: string) => api.getTenantSecurity(id),
  updateSecurity: (id: string, security: Partial<TenantSecurity>) => api.updateTenantSecurity(id, security),
  getAuditLogs: (id: string, limit: number) => api.getTenantAuditLogs(id, limit)
}

// Types
export interface MessageRequest {
  content: string
  sessionId?: string
}

export interface Tenant {
  id: string
  state: 'ACTIVE' | 'SUSPENDED' | 'DESTROYED'
  createdAt: string
  lastActivity: string
  activeAgents: number
  activeSessions: number
}

export interface TenantQuota {
  maxDailyRequests: number
  maxDailyTokens: number
  maxConcurrentAgents: number
  maxConcurrentSessions: number
  maxStorageBytes: number
  maxMemoryBytes: number
  requestsPerSecond: number
  requestsPerMinute: number
  maxToolCallsPerSession: number
  maxExecutionTimeSeconds: number
}

export interface TenantUsage {
  storage: number
  quota: {
    storageUsage: number
    maxStorage: number
    dailyRequests: number
    maxDailyRequests: number
    dailyTokens: number
    maxDailyTokens: number
  }
}

export interface TenantSecurity {
  allowCodeExecution: boolean
  requireSandbox: boolean
  allowNetworkAccess: boolean
  allowedLanguages: string[]
  allowedTools: string[]
  deniedTools: string[]
}

export interface AuditEvent {
  timestamp: string
  type: string
  details: Record<string, unknown>
}

export const api = new ApiClient()

// Legacy agent API for ChatPanel compatibility
export const agentApi = {
  sendMessage: async (request: { content: string; sessionId?: string }) => {
    const response = await api.sendMessage(request.content, request.sessionId)
    return response
  }
}

export interface MessageRequest {
  content: string
  sessionId?: string
}
