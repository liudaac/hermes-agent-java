import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

export interface AgentStatus {
  status: string;
  version: string;
  uptime: number;
  connected: boolean;
}

export interface MessageRequest {
  content: string;
  sessionId?: string;
  platform?: string;
}

export interface MessageResponse {
  messageId: string;
  content: string;
  status: string;
}

export interface ToolInfo {
  name: string;
  description: string;
  emoji: string;
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

  // Webhook for platforms
  sendWebhook: (platform: string, data: unknown) =>
    api.post(`/webhook/${platform}`, data),
};

export default api;
