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

export interface MessageRequest {
  content: string
  sessionId?: string
}

export interface Tenant {
  id: string
  name?: string
  status: string
  created_at?: number
}

export interface TenantStats {
  active_tenants: number
  suspended_tenants: number
  total_registered: number
}

export interface TenantQuota {
  daily_requests: number
  max_tokens: number
  max_sessions: number
  used_requests: number
  used_tokens: number
}

export interface TenantUsage {
  total_requests: number
  total_tokens: number
  sessions: number
  uptime: number
}

export interface TenantSecurity {
  file_access: boolean
  web_access: boolean
  shell_access: boolean
  max_file_size: number
}

export interface AuditEvent {
  timestamp: number
  type: string
  details: Record<string, unknown>
}

export interface LogEntry {
  timestamp: number
  level: string
  message: string
  source: string
}

export interface AnalyticsData {
  total_messages: number
  total_sessions: number
  avg_response_time: number
  top_platforms: Record<string, number>
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

  // Sessions
  async getSessions(limit?: number): Promise<SessionInfo[]> {
    const params = limit ? `?limit=${limit}` : ''
    return this.fetch(`/sessions${params}`)
  }

  async getSessionMessages(id: string): Promise<{ sessionId: string; messages: any[] }> {
    return this.fetch(`/sessions/${id}/messages`)
  }

  // Actions
  async restartGateway(): Promise<{ status: string; timestamp: number }> {
    return this.fetch('/actions/restart-gateway', { method: 'POST' })
  }

  async updateHermes(): Promise<{ status: string; version: string }> {
    return this.fetch('/actions/update', { method: 'POST' })
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

  async updateConfig(config: Record<string, any>): Promise<{ status: string }> {
    return this.fetch('/config', {
      method: 'POST',
      body: JSON.stringify(config)
    })
  }

  // Environment / API Keys
  async getEnvVars(): Promise<Record<string, { value: string; updated_at: number }>> {
    return this.fetch('/env')
  }

  async setEnvVar(key: string, value: string): Promise<{ status: string }> {
    return this.fetch('/env', {
      method: 'PUT',
      body: JSON.stringify({ key, value })
    })
  }

  async deleteEnvVar(key: string): Promise<{ status: string }> {
    return this.fetch(`/env/${key}`, { method: 'DELETE' })
  }

  // Skills
  async getSkills(): Promise<SkillInfo[]> {
    return this.fetch('/skills')
  }

  async toggleSkill(name: string, enabled: boolean): Promise<{ status: string }> {
    return this.fetch(`/skills/${name}`, {
      method: 'PUT',
      body: JSON.stringify({ enabled })
    })
  }

  // Cron Jobs
  async getCronJobs(): Promise<CronJob[]> {
    return this.fetch('/cron')
  }

  async createCronJob(job: Omit<CronJob, 'id'>): Promise<{ id: string; status: string }> {
    return this.fetch('/cron', {
      method: 'POST',
      body: JSON.stringify(job)
    })
  }

  async updateCronJob(id: string, job: Partial<CronJob>): Promise<{ status: string }> {
    return this.fetch(`/cron/${id}`, {
      method: 'PUT',
      body: JSON.stringify(job)
    })
  }

  async deleteCronJob(id: string): Promise<{ status: string }> {
    return this.fetch(`/cron/${id}`, { method: 'DELETE' })
  }

  // Logs
  async getLogs(level?: string, limit = 100): Promise<LogEntry[]> {
    const params = new URLSearchParams()
    if (level) params.append('level', level)
    params.append('limit', limit.toString())
    return this.fetch(`/logs?${params}`)
  }

  // Analytics
  async getAnalytics(): Promise<AnalyticsData> {
    return this.fetch('/analytics')
  }

  // Chat (non-streaming)
  async sendChatMessage(message: string, sessionId?: string): Promise<{
    response: string
    session_id: string
    timestamp: number
  }> {
    return this.fetch('/chat', {
      method: 'POST',
      body: JSON.stringify({ message, session_id: sessionId })
    })
  }

  // Chat (SSE streaming)
  streamChatMessage(
    message: string,
    sessionId: string,
    onChunk: (content: string) => void,
    onDone: () => void,
    onError: (error: string) => void
  ): EventSource {
    const encodedMessage = encodeURIComponent(message)
    const sid = sessionId || 'frontend-' + Date.now()
    const url = `${API_BASE}/chat/stream?session_id=${sid}`
    
    // Use fetch with POST for the message body
    const controller = new AbortController()
    
    fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, session_id: sid }),
      signal: controller.signal
    }).then(async (response) => {
      if (!response.ok) {
        onError(`HTTP error: ${response.status}`)
        return
      }
      
      const reader = response.body?.getReader()
      const decoder = new TextDecoder()
      
      if (!reader) {
        onError('No response body')
        return
      }
      
      let buffer = ''
      
      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          
          buffer += decoder.decode(value, { stream: true })
          
          // Process SSE events
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''
          
          let currentEvent = ''
          let currentData = ''
          
          for (const line of lines) {
            if (line.startsWith('event: ')) {
              currentEvent = line.slice(7)
            } else if (line.startsWith('data: ')) {
              currentData = line.slice(6)
            } else if (line === '' && currentEvent) {
              try {
                if (currentEvent === 'message') {
                  const data = JSON.parse(currentData)
                  onChunk(data.content || '')
                } else if (currentEvent === 'done') {
                  onDone()
                } else if (currentEvent === 'error') {
                  const data = JSON.parse(currentData)
                  onError(data.error || 'Unknown error')
                }
              } catch (e) {
                // Ignore parse errors
              }
              currentEvent = ''
              currentData = ''
            }
          }
        }
        onDone()
      } catch (e) {
        onError(e instanceof Error ? e.message : 'Stream error')
      }
    }).catch((e) => {
      onError(e instanceof Error ? e.message : 'Request error')
    })
    
    // Return a mock EventSource that can be used to abort
    return {
      close: () => controller.abort(),
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => true,
      onopen: null,
      onmessage: null,
      onerror: null,
      readyState: 1,
      url,
      withCredentials: false
    } as EventSource
  }

  // Tenants
  async getTenants(): Promise<TenantStats> {
    return this.fetch('/tenants')
  }

  async createTenant(): Promise<{ id: string; status: string }> {
    return this.fetch('/tenants', { method: 'POST' })
  }

  async getTenant(id: string): Promise<Tenant> {
    return this.fetch(`/tenants/${id}`)
  }

  async deleteTenant(id: string): Promise<{ status: string }> {
    return this.fetch(`/tenants/${id}`, { method: 'DELETE' })
  }

  async suspendTenant(id: string): Promise<{ status: string }> {
    return this.fetch(`/tenants/${id}/suspend`, { method: 'POST' })
  }

  async resumeTenant(id: string): Promise<{ status: string }> {
    return this.fetch(`/tenants/${id}/resume`, { method: 'POST' })
  }

  async getTenantQuota(id: string): Promise<TenantQuota> {
    return this.fetch(`/tenants/${id}/quota`)
  }

  async updateTenantQuota(id: string, quota: Partial<TenantQuota>): Promise<{ status: string }> {
    return this.fetch(`/tenants/${id}/quota`, {
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

  async updateTenantSecurity(id: string, security: Partial<TenantSecurity>): Promise<{ status: string }> {
    return this.fetch(`/tenants/${id}/security`, {
      method: 'PUT',
      body: JSON.stringify(security)
    })
  }

  async getTenantAuditLogs(id: string): Promise<AuditEvent[]> {
    return this.fetch(`/tenants/${id}/audit`)
  }
}

export const api = new ApiClient()

// Legacy exports for compatibility
export const agentApi = {
  sendMessage: async (request: MessageRequest) => {
    const response = await api.sendChatMessage(request.content, request.sessionId)
    return { data: { content: response.response } }
  }
}

export const tenantApi = {
  listTenants: () => api.getTenants(),
  createTenant: () => api.createTenant(),
  getTenant: (id: string) => api.getTenant(id),
  deleteTenant: (id: string) => api.deleteTenant(id),
  suspendTenant: (id: string) => api.suspendTenant(id),
  resumeTenant: (id: string) => api.resumeTenant(id),
  getQuota: (id: string) => api.getTenantQuota(id),
  updateQuota: (id: string, quota: Partial<TenantQuota>) => api.updateTenantQuota(id, quota),
  getUsage: (id: string) => api.getTenantUsage(id),
  getSecurity: (id: string) => api.getTenantSecurity(id),
  updateSecurity: (id: string, security: Partial<TenantSecurity>) => api.updateTenantSecurity(id, security),
  getAuditLogs: (id: string) => api.getTenantAuditLogs(id)
}
