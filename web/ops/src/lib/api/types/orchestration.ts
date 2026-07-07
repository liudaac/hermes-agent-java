/**
 * Orchestration types — SLA, DLQ, takeover, workflow, connectors.
 * Bridge between Portal and NOC.
 */
// ── Extended Orchestration API Types ──────────────────────────────────

export interface SLATemplate {
  name: string;
  warnThresholdMs: number;
  breachThresholdMs: number;
  escalationTarget: string;
  actionOnBreach: string;
}

export interface SLATemplatesResponse {
  ok: boolean;
  templates: Record<string, SLATemplate>;
}

export interface DLQItem {
  itemId: string;
  runId: string;
  workspaceId: string;
  teamId?: string;
  scenarioId?: string;
  taskTitle: string;
  reason: string;
  enqueuedAt: string;
  retryCount: number;
  newRunId?: string;
  status: string;
}

export interface DLQResponse {
  ok: boolean;
  workspaceId?: string;
  items: DLQItem[];
  stats: { total: number; pending: number; retried: number; resolved: number };
}

export interface ApprovalAnalyticsSummary {
  avgResolutionTimeMinutes: number;
  approvalRate: number;
  rejectionReasons: Record<string, number>;
  topBottlenecks: { approvalId: string; title: string; riskLevel: string; pendingMinutes: number; createdAt: string }[];
  pendingCount: number;
  totalCount: number;
}

export interface ApprovalAnalyticsResponse {
  ok: boolean;
  workspaceId?: string;
  summary: ApprovalAnalyticsSummary;
}

export interface TakeoverSession {
  takeoverId: string;
  workspaceId: string;
  runId: string;
  operatorId: string;
  teamId: string;
  startedAt: string;
  status: string;
  endedAt?: string;
}

export interface WorkflowStatusResponse {
  ok: boolean;
  status: {
    found: boolean;
    workflowId: string;
    name: string;
    status: string;
    progress: number;
    currentStep: string | null;
    stepsTotal: number;
    stepsCompleted: number;
    waitingForHuman: boolean;
    createdAt: string;
  };
}

export interface ConnectorInfo {
  name: string;
  label: string;
  description: string;
  healthy: boolean;
}

export interface ConnectorsResponse {
  ok: boolean;
  connectors: ConnectorInfo[];
  health: { total: number; healthy: number; unhealthy: number; details: unknown[] };
}

export interface VerticalSeedResponse {
  ok: boolean;
  workspaceId: string;
  scenarios: { teamId: string; scenarioId: string; scenarioType: string }[];
}
