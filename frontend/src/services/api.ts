import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

export interface AgentStatus {
  running: boolean
  port: number
  adapters: string[]
  active_threads: number
  version: string
  uptime: number
  connected: boolean
}

export interface MessageRequest {
  content: string
  sessionId?: string
  platform?: string
  channel?: string
}

export interface MessageResponse {
  status: string
  message_id?: string
  content?: string
}

export interface ToolInfo {
  name: string
  description: string
  emoji: string
}

export interface Session {
  id: string
  platform: string
  user: string
  lastActivity: number
  messageCount: number
}

export interface Config {
  model: {
    provider: string
    model: string
  }
  display: {
    personality: string
  }
  tools: {
    enabled: string[]
  }
}

export interface Tenant {
  id: string
  state: string
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
  maxFileSizeBytes: number
  maxExecutionTimeSeconds: number
  allowCodeExecution: boolean
  maxPrivateSkills: number
  maxInstalledSkills: number
}

export interface TenantUsage {
  storage: number
  memory: number
  quota: {
    dailyRequests: number
    maxDailyRequests: number
    dailyTokens: number
    maxDailyTokens: number
    activeAgents: number
    maxConcurrentAgents: number
    storageUsage: number
    maxStorage: number
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

export const agentApi = {
  health: () => api.get('/health'),

  getStatus: () => api.get<AgentStatus>('/api/status'),

  sendMessage: (data: MessageRequest) => 
    api.post<MessageResponse>('/api/message', data),

  getTools: () => api.get<ToolInfo[]>('/api/tools'),

  getConfig: () => api.get<Config>('/api/config'),

  updateConfig: (data: Partial<Config>) => 
    api.post('/api/config', data),

  getSessions: () => api.get<Session[]>('/api/sessions'),

  getSessionMessages: (sessionId: string) => 
    api.get(`/api/sessions/${sessionId}/messages`),

  sendWebhook: (platform: string, data: unknown) =>
    api.post(`/webhook/${platform}`, data),
}

export const tenantApi = {
  listTenants: () => api.get<{ tenants: Tenant[] }>('/api/tenants'),

  createTenant: (id: string, createdBy?: string) =>
    api.post('/api/tenants', { id, createdBy }),

  getTenant: (tenantId: string) => api.get<Tenant>(`/api/tenants/${tenantId}`),

  deleteTenant: (tenantId: string, preserveData?: boolean) =>
    api.delete(`/api/tenants/${tenantId}`, { params: { preserveData } }),

  suspendTenant: (tenantId: string) =>
    api.post(`/api/tenants/${tenantId}/suspend`),

  resumeTenant: (tenantId: string) =>
    api.post(`/api/tenants/${tenantId}/resume`),

  getQuota: (tenantId: string) => api.get<TenantQuota>(`/api/tenants/${tenantId}/quota`),

  updateQuota: (tenantId: string, quota: Partial<TenantQuota>) =>
    api.put(`/api/tenants/${tenantId}/quota`, quota),

  getUsage: (tenantId: string) => api.get<TenantUsage>(`/api/tenants/${tenantId}/usage`),

  getSecurity: (tenantId: string) => api.get<TenantSecurity>(`/api/tenants/${tenantId}/security`),

  updateSecurity: (tenantId: string, security: Partial<TenantSecurity>) =>
    api.put(`/api/tenants/${tenantId}/security`, security),

  getAuditLogs: (tenantId: string, limit?: number) =>
    api.get(`/api/tenants/${tenantId}/audit`, { params: { limit } }),

  getSessions: (tenantId: string) =>
    api.get(`/api/tenants/${tenantId}/sessions`),

  getSkills: (tenantId: string) =>
    api.get(`/api/tenants/${tenantId}/skills`),
}

export default api
