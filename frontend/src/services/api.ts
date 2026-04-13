import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

export interface AgentStatus {
  running: boolean;
  port: number;
  adapters: string[];
  active_threads: number;
  version: string;
  uptime: number;
  connected: boolean;
}

export interface MessageRequest {
  content: string;
  sessionId?: string;
  platform?: string;
  channel?: string;
}

export interface MessageResponse {
  status: string;
  message_id?: string;
  content?: string;
}

export interface ToolInfo {
  name: string;
  description: string;
  emoji: string;
}

export interface Session {
  id: string;
  platform: string;
  user: string;
  lastActivity: number;
  messageCount: number;
}

export interface Config {
  model: {
    provider: string;
    model: string;
  };
  display: {
    personality: string;
  };
  tools: {
    enabled: string[];
  };
}

export const agentApi = {
  // Health check
  health: () => api.get('/health'),

  // Get agent status
  getStatus: () => api.get<AgentStatus>('/api/status'),

  // Send message
  sendMessage: (data: MessageRequest) => 
    api.post<MessageResponse>('/api/message', data),

  // Get available tools
  getTools: () => api.get<ToolInfo[]>('/api/tools'),

  // Get configuration
  getConfig: () => api.get<Config>('/api/config'),

  // Update configuration
  updateConfig: (data: Partial<Config>) => 
    api.post('/api/config', data),

  // Get active sessions
  getSessions: () => api.get<Session[]>('/api/sessions'),

  // Get session messages
  getSessionMessages: (sessionId: string) => 
    api.get(`/api/sessions/${sessionId}/messages`),

  // Webhook for platforms
  sendWebhook: (platform: string, data: unknown) =>
    api.post(`/webhook/${platform}`, data),
};

export default api;
